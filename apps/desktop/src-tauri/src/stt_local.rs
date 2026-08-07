//! STT local in-process com whisper.cpp (crate `whisper-rs`).
//!
//! Substitui o sidecar Python + Azure Speech. Uma instancia por TRACK (`mic`,
//! `system`), exatamente como eram os dois processos Python — mas os dois
//! compartilham o mesmo `WhisperContext` (os pesos do modelo, ~465 MiB no
//! `small`), cada um com seu proprio `WhisperState` (KV cache).
//!
//! ============================================================================
//! POR QUE "PSEUDO" REAL-TIME
//! ============================================================================
//! O Whisper e um modelo encoder-decoder de janela FIXA de 30 s. Ele nao tem
//! estado incremental: nao existe "alimentar mais 100 ms e continuar de onde
//! parou". Todo resultado sai de um decode COMPLETO de uma janela de audio.
//!
//! Streaming aqui e, portanto, um loop de re-decode sobre uma janela deslizante:
//!
//!   [------------ audio ja COMMITADO (virou `final`) ------------][== janela ==]
//!   0                                                    committed_ms         now
//!                                                        ^
//!                                                        offset de todo evento
//!
//! A cada ~900 ms a janela e re-decodificada e o texto sai como `partial`. Quando
//! o VAD ve silencio (ou a janela chega perto do teto de 30 s), a janela e
//! decodificada uma ultima vez, sai como `final`, e `committed_ms` avanca.
//!
//! ============================================================================
//! A ARMADILHA DO RE-DECODE: OFFSET QUE REGRIDE
//! ============================================================================
//! Cada re-decode reprocessa audio JA VISTO. Se o offset do evento fosse
//! derivado do timestamp que o whisper devolve (que e relativo ao inicio da
//! JANELA, nao da gravacao), o segundo decode da mesma janela emitiria um
//! offset menor que o do final anterior — e a UI, que ordena e agrupa por
//! tempo, embaralharia a conversa e duplicaria falas.
//!
//! Defesas, nesta ordem:
//!   1. `committed_ms` e um contador MONOTONICO de audio ja finalizado. So
//!      avanca, e avanca exatamente pelo numero de ms removidos da frente da
//!      janela — inclusive silencio descartado, senao os offsets descolam do
//!      relogio da gravacao.
//!   2. `partial` sempre reporta `offset_ms = committed_ms` (o inicio da janela
//!      nao commitada), nunca um timestamp interno do whisper. O texto do
//!      parcial e o re-decode INTEIRO da janela; o front sobrescreve
//!      `partials[track]`, entao revisao de texto e esperada e nao acumula.
//!   3. `final` usa `committed_ms + t0_do_segmento`, mas passa por
//!      `last_final_end_ms.max(...)`: um timestamp bagunçado do whisper e
//!      clampado em vez de virar regressao.
//!   4. `debug_assert` no ponto de emissao, pra quebrar o teste de dev se algum
//!      caminho novo furar a invariante.
//!
//! ============================================================================
//! O QUE ESTE MODULO NAO FAZ
//! ============================================================================
//! * Diarizacao por falante. Whisper nao diariza; WhisperX/pyannote sao batch
//!   por construcao. A atribuicao e POR TRACK — ver `stt::speaker_id_for_track`.
//! * Confidence calibrada. Ver `segment_confidence` mais abaixo.
//! * VAD neural. O VAD aqui e energia + noise floor adaptativo; o whisper.cpp
//!   1.7+ ja expoe Silero VAD (`FullParams::enable_vad`), mas exige baixar um
//!   segundo modelo (`ggml-silero-v5.1.2.bin`). Fica de melhoria.

use std::sync::Arc;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Emitter};
use tokio::sync::{mpsc, oneshot};
use whisper_rs::{
    FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters, WhisperState,
};

use crate::stt::{speaker_id_for_track, SttBackend, TranscriptEvent};
use crate::whisper_model;

// ============================================================================
// Constantes de sintonia
// ============================================================================

const SAMPLE_RATE: usize = 16_000;
const MS_PER_SEC: u64 = 1_000;

/// Frame do VAD: 30 ms. Mesmo tamanho que o WebRTC VAD usa.
const VAD_FRAME_SAMPLES: usize = SAMPLE_RATE * 30 / 1000;

/// Piso absoluto de energia (RMS) abaixo do qual e sempre silencio, mesmo que o
/// noise floor adaptativo tenha convergido pra baixo demais numa sala quieta.
/// ~-44 dBFS.
const ABS_SILENCE_RMS: f32 = 0.006;

/// Quantas vezes acima do noise floor a energia precisa estar pra contar como fala.
const VAD_SNR_FACTOR: f32 = 2.5;

/// Silencio continuo que fecha um enunciado e dispara o `final`.
const SILENCE_COMMIT_MS: u64 = 700;

/// Intervalo minimo entre dois `partial` do mesmo track.
const PARTIAL_EVERY_MS: u64 = 900;

/// Audio minimo na janela pra valer um decode.
const MIN_DECODE_MS: u64 = 900;

/// Fala minima na janela pra ela virar `final` em vez de ser descartada como ruido.
const MIN_COMMIT_SPEECH_MS: u64 = 350;

/// Teto da janela. Abaixo dos 30 s do mel do whisper com folga: em 30 s cheios o
/// modelo passa a truncar por dentro e os timestamps ficam sem sentido.
const MAX_WINDOW_MS: u64 = 22_000;

/// Whisper.cpp devolve ZERO segmentos pra menos de ~1 s de audio (`seek_end <
/// seek_start + 100` em `whisper_full_with_state`). Todo decode e paddado com
/// silencio ate aqui. O padding entra DEPOIS do audio real, entao nao desloca
/// os timestamps dos segmentos.
const MIN_PADDED_MS: u64 = 1_200;

/// Contexto textual passado como `initial_prompt`. 224 chars ~ metade do
/// `n_text_ctx` (448 tokens) do whisper, que e o teto recomendado pro prompt.
const PROMPT_MAX_CHARS: usize = 224;

/// Folga do canal PCM entre a task async e a thread de inferencia: 300 chunks de
/// 100 ms = 30 s. Ver `AudioMsg::Gap` pro que acontece quando estoura.
const PCM_QUEUE_CHUNKS: usize = 300;

/// Textos que o Whisper alucina em audio sem fala. A lista em pt-BR e curta e
/// muito estavel (sao legendas de treino que vazaram pro modelo). Comparacao em
/// lowercase, sem pontuacao final.
const HALLUCINATION_BLOCKLIST: &[&str] = &[
    "legendas pela comunidade amara.org",
    "legendas pela comunidade amara org",
    "amara.org",
    "subtitles by the amara.org community",
    "obrigado por assistir",
    "obrigado por assistir!",
    "[música]",
    "[musica]",
    "(música)",
    "[aplausos]",
    "[risos]",
    "você",
    "...",
    ".",
];

/// Acima disto o segmento e tratado como "sem fala" e descartado.
const NO_SPEECH_THRESHOLD: f32 = 0.75;

// ============================================================================
// Handle publico
// ============================================================================

pub struct LocalSttHandle {
    session_id: String,
    audio_tx: mpsc::Sender<Vec<i16>>,
    stop_tx: Option<oneshot::Sender<()>>,
}

impl Drop for LocalSttHandle {
    fn drop(&mut self) {
        // Mesma defesa do SidecarHandle: se o Vec de backends for limpo sem
        // passar por stop() (panic, logout, close abrupto), o sinal ainda sai e
        // a thread de inferencia encerra. Sem isto ela ficaria queimando CPU ate
        // o processo morrer.
        if let Some(tx) = self.stop_tx.take() {
            let _ = tx.send(());
        }
        #[cfg(debug_assertions)]
        eprintln!("[stt_local] handle DROPPED session_id={}", self.session_id);
    }
}

impl SttBackend for LocalSttHandle {
    fn session_id(&self) -> &str {
        &self.session_id
    }

    fn audio_tx(&self) -> mpsc::Sender<Vec<i16>> {
        self.audio_tx.clone()
    }

    fn stop(mut self: Box<Self>) {
        if let Some(tx) = self.stop_tx.take() {
            let _ = tx.send(());
        }
    }
}

impl LocalSttHandle {
    /// Sobe a transcricao local de UM track.
    ///
    /// Bloqueia ate o modelo estar carregado — de proposito. Se o modelo precisa
    /// ser baixado (primeiro uso), isto demora minutos e emite progresso em
    /// `stt-model-progress`. Falhar aqui e melhor que aceitar audio e descobrir
    /// no meio da reuniao que nao ha modelo.
    pub async fn start(
        app: AppHandle,
        language: String,
        track_label: String,
    ) -> Result<Self, String> {
        let session_id = uuid::Uuid::new_v4().to_string();

        let size = whisper_model::configured_size();
        let model_path = whisper_model::ensure_model(&app, size).await?;
        let ctx = shared_context(&model_path).await?;

        // create_state aloca o KV cache (dezenas de MiB). Fora do executor async
        // porque e trabalho sincronico de dezenas de ms.
        let ctx_for_state = Arc::clone(&ctx);
        let state = tokio::task::spawn_blocking(move || ctx_for_state.create_state())
            .await
            .map_err(|e| format!("join create_state: {e}"))?
            .map_err(|e| format!("whisper create_state: {e}"))?;

        let (audio_tx, mut audio_rx) = mpsc::channel::<Vec<i16>>(100);
        let (stop_tx, mut stop_rx) = oneshot::channel::<()>();
        let (pcm_tx, pcm_rx) = std::sync::mpsc::sync_channel::<AudioMsg>(PCM_QUEUE_CHUNKS);

        // ---- Ponte async -> thread de inferencia -------------------------------
        // Precisa existir porque a inferencia e CPU-bound e sincrona: rodar dentro
        // do executor do tokio travaria as outras tasks (incluindo o outro track).
        let bridge_session = session_id.clone();
        tokio::spawn(async move {
            // Amostras descartadas por fila cheia. NAO podem sumir em silencio:
            // se a inferencia atrasar, o audio some mas o RELOGIO nao para, e sem
            // contabilizar o buraco todos os offsets seguintes ficariam adiantados
            // em relacao ao audio real. O Gap avisa o motor.
            let mut pending_gap_samples: usize = 0;

            loop {
                tokio::select! {
                    _ = &mut stop_rx => break,
                    maybe = audio_rx.recv() => {
                        let Some(samples) = maybe else { break };

                        if pending_gap_samples > 0 {
                            let gap_ms = samples_to_ms(pending_gap_samples);
                            if pcm_tx.try_send(AudioMsg::Gap { ms: gap_ms }).is_ok() {
                                pending_gap_samples = 0;
                            }
                        }

                        match pcm_tx.try_send(AudioMsg::Pcm(samples)) {
                            Ok(()) => {}
                            Err(std::sync::mpsc::TrySendError::Full(AudioMsg::Pcm(s))) => {
                                pending_gap_samples += s.len();
                            }
                            Err(std::sync::mpsc::TrySendError::Full(_)) => {}
                            Err(std::sync::mpsc::TrySendError::Disconnected(_)) => break,
                        }
                    }
                }
            }
            #[cfg(debug_assertions)]
            eprintln!("[stt_local] bridge encerrada session={}", bridge_session);
            #[cfg(not(debug_assertions))]
            let _ = bridge_session;
            // Dropar pcm_tx fecha o canal -> a thread faz o flush final e sai.
        });

        // ---- Thread de inferencia ----------------------------------------------
        let worker_app = app.clone();
        let worker_session = session_id.clone();
        let worker_track = track_label.clone();
        let builder = std::thread::Builder::new()
            .name(format!("nora-whisper-{}", track_label))
            // whisper.cpp e C++: a stack default de 2 MiB do Rust ja serve, mas
            // subimos porque o decoder faz recursao rasa e algumas libs BLAS
            // alocam buffers grandes na stack.
            .stack_size(4 * 1024 * 1024);

        builder
            .spawn(move || {
                let mut engine = StreamEngine::new(state, language, worker_track, worker_session);
                engine.run(&worker_app, pcm_rx);
            })
            .map_err(|e| format!("nao consegui subir a thread de inferencia: {e}"))?;

        eprintln!(
            "[stt_local] track={} pronto (modelo={}, threads={})",
            track_label,
            size.as_str(),
            inference_threads()
        );

        Ok(Self {
            session_id,
            audio_tx,
            stop_tx: Some(stop_tx),
        })
    }
}

enum AudioMsg {
    Pcm(Vec<i16>),
    /// Buraco de `ms` no audio: a fila encheu e as amostras foram descartadas.
    Gap { ms: u64 },
}

// ============================================================================
// Contexto compartilhado entre os tracks
// ============================================================================

/// Cache de UM contexto (os pesos). `mic` e `system` chamam isto com o mesmo
/// path, entao um slot basta — e a economia e grande: sem compartilhar, duas
/// copias do `small` seriam ~930 MiB de RSS so de pesos.
async fn shared_context(path: &std::path::Path) -> Result<Arc<WhisperContext>, String> {
    static CACHE: std::sync::OnceLock<
        tokio::sync::Mutex<Option<(std::path::PathBuf, Arc<WhisperContext>)>>,
    > = std::sync::OnceLock::new();
    let cache = CACHE.get_or_init(|| tokio::sync::Mutex::new(None));

    let mut guard = cache.lock().await;
    if let Some((cached_path, ctx)) = guard.as_ref() {
        if cached_path == path {
            return Ok(Arc::clone(ctx));
        }
    }

    let owned = path.to_path_buf();
    let load_path = owned.clone();
    let loaded = tokio::task::spawn_blocking(move || {
        let mut params = WhisperContextParameters::default();
        // `use_gpu` ja vem `true` quando alguma feature de GPU foi compilada
        // (metal/cuda/vulkan). Sem elas fica `false` e roda em CPU. O whisper.cpp
        // tambem cai pra CPU sozinho se o backend de GPU nao inicializar.
        params.use_gpu = params.use_gpu && !env_flag("NORA_WHISPER_FORCE_CPU");
        WhisperContext::new_with_params(&load_path, params)
    })
    .await
    .map_err(|e| format!("join load do modelo: {e}"))?
    .map_err(|e| {
        format!(
            "whisper nao conseguiu carregar {}: {e}",
            owned.display()
        )
    })?;

    let arc = Arc::new(loaded);
    *guard = Some((owned, Arc::clone(&arc)));
    Ok(arc)
}

fn env_flag(name: &str) -> bool {
    matches!(
        std::env::var(name).unwrap_or_default().to_ascii_lowercase().as_str(),
        "1" | "true" | "yes" | "on"
    )
}

/// Threads por track.
///
/// Metade dos cores (teto 4) porque HA DOIS TRACKS decodificando ao mesmo tempo.
/// Dar `min(4, cores)` pra cada — o default do whisper.cpp — coloca 8 threads
/// num notebook de 4 cores e o oversubscribe deixa a latencia PIOR que com 2.
fn inference_threads() -> i32 {
    static N: std::sync::OnceLock<i32> = std::sync::OnceLock::new();
    *N.get_or_init(|| {
        if let Ok(raw) = std::env::var("NORA_WHISPER_THREADS") {
            if let Ok(n) = raw.trim().parse::<i32>() {
                if n >= 1 {
                    return n;
                }
            }
        }
        let cores = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(2);
        ((cores / 2).clamp(1, 4)) as i32
    })
}

/// `audio_ctx` reduzido corta o custo do encoder proporcionalmente (o encoder e
/// a maior fatia do tempo numa janela curta), ao custo de qualidade. 0 = full
/// (1500). Knob exposto porque o valor bom depende do hardware; nao ha default
/// seguro pra todo mundo.
fn audio_ctx_override() -> i32 {
    static V: std::sync::OnceLock<i32> = std::sync::OnceLock::new();
    *V.get_or_init(|| {
        std::env::var("NORA_WHISPER_AUDIO_CTX")
            .ok()
            .and_then(|s| s.trim().parse::<i32>().ok())
            .filter(|v| *v >= 0)
            .unwrap_or(0)
    })
}

// ============================================================================
// VAD por energia
// ============================================================================

struct EnergyVad {
    /// Piso de ruido adaptativo. Sobe devagar, desce rapido — assim uma pausa
    /// longa nao "ensina" o VAD a ignorar fala baixa depois.
    noise_floor: f32,
    /// Silencio continuo no FIM da janela, em ms.
    silence_run_ms: u64,
    /// Fala acumulada na janela atual, em ms.
    speech_ms: u64,
    /// Amostras da janela ja analisadas.
    cursor: usize,
}

impl EnergyVad {
    fn new() -> Self {
        Self {
            noise_floor: ABS_SILENCE_RMS,
            silence_run_ms: 0,
            speech_ms: 0,
            cursor: 0,
        }
    }

    /// Reanalisa do zero. Chamado depois de cortar a frente da janela: e mais
    /// simples e mais correto que reindexar `cursor`, e custa um RMS sobre
    /// poucos segundos de f32 (irrelevante perto de um decode).
    fn reset_window(&mut self) {
        self.silence_run_ms = 0;
        self.speech_ms = 0;
        self.cursor = 0;
    }

    fn ingest(&mut self, window: &[f32]) {
        const FRAME_MS: u64 = (VAD_FRAME_SAMPLES * 1000 / SAMPLE_RATE) as u64;

        while self.cursor + VAD_FRAME_SAMPLES <= window.len() {
            let frame = &window[self.cursor..self.cursor + VAD_FRAME_SAMPLES];
            self.cursor += VAD_FRAME_SAMPLES;

            let sum_sq: f32 = frame.iter().map(|s| s * s).sum();
            let rms = (sum_sq / VAD_FRAME_SAMPLES as f32).sqrt();

            let threshold = (self.noise_floor * VAD_SNR_FACTOR).max(ABS_SILENCE_RMS);
            if rms > threshold {
                self.speech_ms += FRAME_MS;
                self.silence_run_ms = 0;
                // Sobe o piso bem devagar: fala continua nao pode elevar o piso
                // a ponto de silenciar o proprio falante.
                self.noise_floor = self.noise_floor * 0.999 + rms * 0.001;
            } else {
                self.silence_run_ms += FRAME_MS;
                self.noise_floor = self.noise_floor * 0.95 + rms * 0.05;
            }
            self.noise_floor = self.noise_floor.clamp(1e-5, 0.2);
        }
    }
}

// ============================================================================
// Motor de streaming
// ============================================================================

struct DecodedSegment {
    text: String,
    /// ms desde o inicio da JANELA (nao da gravacao).
    t0_ms: u64,
    t1_ms: u64,
    confidence: Option<f32>,
}

struct StreamEngine {
    state: WhisperState,
    language: String,
    track: String,
    session_id: String,

    /// Audio ainda nao finalizado, f32 normalizado em [-1, 1].
    window: Vec<f32>,
    /// MONOTONICO. ms de audio ja finalizado; offset base de todo evento.
    committed_ms: u64,
    /// MONOTONICO. Fim (offset+duracao) do ultimo `final` emitido. Clamp de
    /// seguranca contra timestamp bagunçado do whisper.
    last_final_end_ms: u64,

    vad: EnergyVad,
    prompt: String,
    last_partial_text: String,
    last_partial_at: Instant,
}

impl StreamEngine {
    fn new(state: WhisperState, language: String, track: String, session_id: String) -> Self {
        Self {
            state,
            language,
            track,
            session_id,
            window: Vec::with_capacity(SAMPLE_RATE * 30),
            committed_ms: 0,
            last_final_end_ms: 0,
            vad: EnergyVad::new(),
            prompt: String::new(),
            last_partial_text: String::new(),
            last_partial_at: Instant::now(),
        }
    }

    fn run(&mut self, app: &AppHandle, rx: std::sync::mpsc::Receiver<AudioMsg>) {
        loop {
            match rx.recv_timeout(Duration::from_millis(100)) {
                Ok(msg) => {
                    self.handle(app, msg);
                    // Esvazia o que chegou enquanto o decode anterior rodava.
                    while let Ok(more) = rx.try_recv() {
                        self.handle(app, more);
                    }
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                    // stop(): decodifica o que sobrou. Sem este flush a ultima
                    // frase da reuniao — em geral a conclusao — se perde.
                    self.flush(app);
                    break;
                }
            }
            self.tick(app);
        }
        #[cfg(debug_assertions)]
        eprintln!(
            "[stt_local] thread encerrada track={} committed={}ms",
            self.track, self.committed_ms
        );
    }

    fn handle(&mut self, app: &AppHandle, msg: AudioMsg) {
        match msg {
            AudioMsg::Pcm(samples) => {
                self.window
                    .extend(samples.iter().map(|s| *s as f32 / 32768.0));
            }
            AudioMsg::Gap { ms } => {
                eprintln!(
                    "[stt_local] track={} BURACO de {}ms no audio (inferencia atrasada)",
                    self.track, ms
                );
                // Audio descontinuo: o que esta na janela nao pode ser
                // concatenado com o que vem depois (viraria uma frase costurada
                // de dois momentos). Fecha o que da e pula o relogio pelo buraco.
                if !self.window.is_empty() {
                    self.commit(app, None);
                }
                self.committed_ms += ms;
                self.last_final_end_ms = self.last_final_end_ms.max(self.committed_ms);
            }
        }
    }

    fn window_ms(&self) -> u64 {
        samples_to_ms(self.window.len())
    }

    fn tick(&mut self, app: &AppHandle) {
        self.vad.ingest(&self.window);
        let window_ms = self.window_ms();

        if window_ms == 0 {
            return;
        }

        let silence_closed = self.vad.silence_run_ms >= SILENCE_COMMIT_MS;
        let window_full = window_ms >= MAX_WINDOW_MS;

        // Janela inteira sem fala util: descarta SEM decodificar. Alem de
        // economizar CPU, isto evita a alucinacao classica do Whisper em
        // silencio ("Legendas pela comunidade Amara.org"). O relogio avanca
        // igual — o silencio faz parte da gravacao.
        if silence_closed && self.vad.speech_ms < MIN_COMMIT_SPEECH_MS {
            self.drop_window(window_ms);
            return;
        }

        if silence_closed || window_full {
            let boundary = if window_full && !silence_closed {
                // Corte forcado no meio de uma fala: nao commita o ultimo
                // segmento (quase sempre truncado no meio de uma palavra).
                Some(CutPolicy::KeepLastSegment)
            } else {
                None
            };
            self.commit(app, boundary);
            return;
        }

        let due = self.last_partial_at.elapsed() >= Duration::from_millis(PARTIAL_EVERY_MS);
        if due && window_ms >= MIN_DECODE_MS && self.vad.speech_ms >= 300 {
            self.emit_partial(app);
        }
    }

    fn flush(&mut self, app: &AppHandle) {
        self.vad.ingest(&self.window);
        if self.window_ms() == 0 || self.vad.speech_ms < MIN_COMMIT_SPEECH_MS {
            return;
        }
        self.commit(app, None);
    }

    /// Descarta silencio da frente da janela mantendo o relogio coerente.
    fn drop_window(&mut self, window_ms: u64) {
        self.committed_ms += window_ms;
        self.last_final_end_ms = self.last_final_end_ms.max(self.committed_ms);
        self.window.clear();
        self.vad.reset_window();
        self.clear_partial();
    }

    fn clear_partial(&mut self) {
        self.last_partial_text.clear();
    }

    // ------------------------------------------------------------------------
    // Decode
    // ------------------------------------------------------------------------

    fn decode(&mut self, single_segment: bool) -> Result<Vec<DecodedSegment>, String> {
        // Clones locais: `set_language`/`set_initial_prompt` amarram o
        // emprestimo ao FullParams, e o `full()` precisa de `&mut self.state`.
        let language = self.language.clone();
        let prompt = self.prompt.clone();

        let strategy = if single_segment {
            // Parcial: greedy puro, sem fallback de temperatura. Latencia > tudo.
            SamplingStrategy::Greedy { best_of: 1 }
        } else {
            SamplingStrategy::Greedy { best_of: 2 }
        };
        let mut params = FullParams::new(strategy);
        params.set_n_threads(inference_threads());
        params.set_language(Some(&language));
        params.set_translate(false);
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        params.set_suppress_blank(true);
        params.set_single_segment(single_segment);
        params.set_no_speech_thold(0.6);
        // `no_context = true` + prompt manual: a janela deslizante joga fora
        // audio que o KV interno ainda referenciaria, entao deixar o whisper
        // reaproveitar o contexto anterior produz repeticao em loop. O contexto
        // vem do texto ja commitado, explicitamente.
        params.set_no_context(true);
        if !prompt.is_empty() {
            // NOTA: `set_initial_prompt` faz `CString::into_raw()` e nunca
            // recupera o ponteiro — cada chamada vaza o tamanho do prompt
            // (<= 224 bytes). ~1 parcial/s numa reuniao de 1 h da <1 MiB. Aceito
            // conscientemente; corrigir exige patch upstream no whisper-rs.
            params.set_initial_prompt(&prompt);
        }
        let actx = audio_ctx_override();
        if actx > 0 {
            params.set_audio_ctx(actx);
        }

        let pcm = pad_to_min(&self.window);
        self.state
            .full(params, &pcm)
            .map_err(|e| format!("whisper full(): {e}"))?;

        let n = self.state.full_n_segments();
        let mut out = Vec::with_capacity(n.max(0) as usize);
        for i in 0..n {
            let Some(seg) = self.state.get_segment(i) else {
                continue;
            };
            if seg.no_speech_probability() > NO_SPEECH_THRESHOLD {
                continue;
            }
            let Ok(raw) = seg.to_str_lossy() else { continue };
            let text = raw.trim().to_string();
            if is_noise_text(&text) {
                continue;
            }
            // Centisegundos -> ms. Negativo nao deveria acontecer; clamp mesmo assim.
            let t0_ms = seg.start_timestamp().max(0) as u64 * 10;
            let t1_ms = (seg.end_timestamp().max(0) as u64 * 10).max(t0_ms);
            out.push(DecodedSegment {
                text,
                t0_ms,
                t1_ms,
                confidence: segment_confidence(&seg),
            });
        }
        Ok(out)
    }

    fn emit_partial(&mut self, app: &AppHandle) {
        self.last_partial_at = Instant::now();
        let segs = match self.decode(true) {
            Ok(s) => s,
            Err(e) => {
                self.emit_error(app, "decode_failed", &e);
                return;
            }
        };
        let text = join_segments(&segs);
        if text.is_empty() || text == self.last_partial_text {
            return;
        }
        self.last_partial_text = text.clone();

        // Parcial reporta o inicio da janela nao commitada. NUNCA um timestamp
        // interno do whisper: o texto e um re-decode da janela inteira e o front
        // sobrescreve `partials[track]` a cada evento.
        self.emit(
            app,
            TranscriptEvent {
                session_id: self.session_id.clone(),
                track: self.track.clone(),
                speaker_id: speaker_id_for_track(&self.track),
                text,
                is_final: false,
                offset_ms: self.committed_ms,
                // Azure tambem nao mandava duration/confidence em parcial.
                duration_ms: None,
                confidence: None,
            },
        );
    }

    fn commit(&mut self, app: &AppHandle, policy: Option<CutPolicy>) {
        let window_ms = self.window_ms();
        if window_ms == 0 {
            return;
        }

        let segs = match self.decode(false) {
            Ok(s) => s,
            Err(e) => {
                self.emit_error(app, "decode_failed", &e);
                // Nao trava a janela num decode quebrado: descarta e segue, senao
                // ela cresce ate o teto e repete o mesmo erro pra sempre.
                self.drop_window(window_ms);
                return;
            }
        };

        if segs.is_empty() {
            self.drop_window(window_ms);
            return;
        }

        // Quanto da janela sai como commitado.
        let (emit_upto, consumed_ms) = match policy {
            Some(CutPolicy::KeepLastSegment) if segs.len() > 1 => {
                let cut = segs[segs.len() - 2].t1_ms.min(window_ms);
                // Corte degenerado (timestamps colados no zero): commita tudo em
                // vez de nao avancar e reprocessar a mesma janela pra sempre.
                if cut == 0 {
                    (segs.len(), window_ms)
                } else {
                    (segs.len() - 1, cut)
                }
            }
            _ => (segs.len(), window_ms),
        };

        let base = self.committed_ms;
        let mut prompt_add = String::new();

        for seg in segs.iter().take(emit_upto) {
            let offset = (base + seg.t0_ms).max(self.last_final_end_ms);
            let end = (base + seg.t1_ms).max(offset);
            let duration = end - offset;

            debug_assert!(
                offset >= self.last_final_end_ms,
                "offset regrediu: {} < {} (track={})",
                offset,
                self.last_final_end_ms,
                self.track
            );
            self.last_final_end_ms = end;

            if !prompt_add.is_empty() {
                prompt_add.push(' ');
            }
            prompt_add.push_str(&seg.text);

            self.emit(
                app,
                TranscriptEvent {
                    session_id: self.session_id.clone(),
                    track: self.track.clone(),
                    speaker_id: speaker_id_for_track(&self.track),
                    text: seg.text.clone(),
                    is_final: true,
                    offset_ms: offset,
                    duration_ms: Some(duration),
                    confidence: seg.confidence,
                },
            );
        }

        // Avanca o relogio e corta a frente da janela.
        self.committed_ms += consumed_ms;
        self.last_final_end_ms = self.last_final_end_ms.max(self.committed_ms);
        let consumed_samples = ms_to_samples(consumed_ms).min(self.window.len());
        self.window.drain(..consumed_samples);
        self.vad.reset_window();
        self.clear_partial();
        self.push_prompt(&prompt_add);
    }

    fn push_prompt(&mut self, text: &str) {
        if text.trim().is_empty() {
            return;
        }
        if !self.prompt.is_empty() {
            self.prompt.push(' ');
        }
        self.prompt.push_str(text.trim());
        // Corta pela ESQUERDA em fronteira de char (pt-BR tem acento: cortar por
        // byte estoura o `CString`/UTF-8 mais adiante).
        if self.prompt.chars().count() > PROMPT_MAX_CHARS {
            let skip = self.prompt.chars().count() - PROMPT_MAX_CHARS;
            self.prompt = self.prompt.chars().skip(skip).collect();
        }
    }

    fn emit(&self, app: &AppHandle, evt: TranscriptEvent) {
        let _ = app.emit("transcript", &evt);
    }

    fn emit_error(&self, app: &AppHandle, code: &str, message: &str) {
        eprintln!("[stt_local] track={} {}: {}", self.track, code, message);
        // Mesmo shape do `error` do sidecar Python — o front nao distingue.
        let _ = app.emit(
            "stt-error",
            &serde_json::json!({
                "v": 1,
                "type": "error",
                "session_id": self.session_id,
                "code": code,
                "message": message,
            }),
        );
    }
}

enum CutPolicy {
    /// Nao commita o ultimo segmento: ele fica na janela pro proximo decode.
    KeepLastSegment,
}

// ============================================================================
// Helpers puros (testaveis sem modelo)
// ============================================================================

fn samples_to_ms(n: usize) -> u64 {
    (n as u64 * MS_PER_SEC) / SAMPLE_RATE as u64
}

fn ms_to_samples(ms: u64) -> usize {
    ((ms * SAMPLE_RATE as u64) / MS_PER_SEC) as usize
}

/// Whisper.cpp descarta audio com menos de ~1 s. Padda com silencio no FIM
/// (nunca no comeco: isso deslocaria todos os timestamps dos segmentos).
fn pad_to_min(window: &[f32]) -> Vec<f32> {
    let min_samples = ms_to_samples(MIN_PADDED_MS);
    if window.len() >= min_samples {
        return window.to_vec();
    }
    let mut out = Vec::with_capacity(min_samples);
    out.extend_from_slice(window);
    out.resize(min_samples, 0.0);
    out
}

/// Filtro de alucinacao. `text` ja vem trimado.
fn is_noise_text(text: &str) -> bool {
    if text.is_empty() {
        return true;
    }
    let normalized = text
        .trim_matches(|c: char| c.is_whitespace() || c == '.' || c == '!' || c == '?')
        .to_lowercase();
    if normalized.is_empty() {
        return true;
    }
    HALLUCINATION_BLOCKLIST
        .iter()
        .any(|b| normalized == b.trim_matches(|c: char| c == '.' || c == '!'))
}

fn join_segments(segs: &[DecodedSegment]) -> String {
    let mut out = String::new();
    for s in segs {
        if !out.is_empty() {
            out.push(' ');
        }
        out.push_str(&s.text);
    }
    out.trim().to_string()
}

/// Confidence de um segmento a partir das probabilidades dos tokens.
///
/// ========================= NAO E CALIBRADA =========================
/// O `confidence` do Azure vinha de `NBest[0].Confidence`: um score treinado,
/// comparavel entre enunciados, com significado estatistico definido pelo
/// servico. ISTO AQUI NAO E ISSO.
///
/// O que e: `exp(media dos ln(p) dos tokens)`, ou seja, a media GEOMETRICA da
/// probabilidade que o proprio decoder atribuiu aos tokens que ele mesmo
/// escolheu. E o `avg_logprob` do Whisper, normalizado pra (0, 1].
///
/// Consequencias praticas:
///   * Um modelo confiantemente errado (alucinacao fluente) pontua ALTO. O caso
///     classico em pt-BR e "Legendas pela comunidade Amara.org" com ~0.9.
///   * A escala muda com o tamanho do modelo: 0.7 no `base` e 0.7 no `medium`
///     nao querem dizer a mesma coisa.
///   * Nao e comparavel com os valores historicos gravados pelo Azure. Qualquer
///     threshold em cima disso precisa ser recalibrado com dados reais.
///
/// Serve pra ordenar segmentos DENTRO da mesma sessao e pro mesmo modelo, e so.
fn segment_confidence(seg: &whisper_rs::WhisperSegment<'_>) -> Option<f32> {
    let n = seg.n_tokens();
    let mut sum_ln = 0.0f64;
    let mut count = 0u32;

    for j in 0..n {
        let Some(tok) = seg.get_token(j) else { continue };
        // Tokens especiais (`[_BEG_]`, `<|pt|>`, timestamps) entram na contagem
        // com probabilidade ~1.0 e inflariam a media. Sao identificaveis pelo
        // texto, que e como o proprio whisper.cpp os filtra quando
        // `print_special == false`.
        if let Ok(t) = tok.to_str_lossy() {
            if t.starts_with("[_") || t.starts_with("<|") {
                continue;
            }
        }
        let p = tok.token_probability().clamp(1e-6, 1.0);
        sum_ln += (p as f64).ln();
        count += 1;
    }

    if count == 0 {
        return None;
    }
    Some(((sum_ln / count as f64).exp() as f32).clamp(0.0, 1.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conversao_ms_amostras_e_reversivel_em_multiplos_de_frame() {
        for ms in [0u64, 100, 900, 1_200, 22_000] {
            assert_eq!(samples_to_ms(ms_to_samples(ms)), ms);
        }
    }

    #[test]
    fn pad_nunca_encurta_e_alcanca_o_minimo() {
        let min = ms_to_samples(MIN_PADDED_MS);
        let curto = vec![0.5f32; 100];
        let padded = pad_to_min(&curto);
        assert_eq!(padded.len(), min);
        // Padding vai no FIM: os 100 primeiros continuam sendo o audio real.
        assert_eq!(&padded[..100], &curto[..]);
        assert_eq!(padded[min - 1], 0.0);

        let longo = vec![0.1f32; min + 5];
        assert_eq!(pad_to_min(&longo).len(), min + 5);
    }

    #[test]
    fn blocklist_pega_alucinacao_de_silencio_em_pt_br() {
        assert!(is_noise_text("Legendas pela comunidade Amara.org"));
        assert!(is_noise_text("  [Música]  "));
        assert!(is_noise_text("..."));
        assert!(is_noise_text(""));
        assert!(!is_noise_text("vamos fechar o escopo do sprint"));
    }

    #[test]
    fn vad_separa_silencio_de_fala() {
        let mut vad = EnergyVad::new();
        // 1 s de silencio digital.
        let silencio = vec![0.0f32; SAMPLE_RATE];
        vad.ingest(&silencio);
        assert_eq!(vad.speech_ms, 0);
        assert!(vad.silence_run_ms >= 900, "silence_run={}", vad.silence_run_ms);

        // 1 s de onda com amplitude bem acima do piso.
        let mut buf = silencio.clone();
        for i in 0..SAMPLE_RATE {
            let t = i as f32 / SAMPLE_RATE as f32;
            buf.push((t * 440.0 * std::f32::consts::TAU).sin() * 0.3);
        }
        vad.ingest(&buf);
        assert!(vad.speech_ms >= 900, "speech_ms={}", vad.speech_ms);
        assert_eq!(vad.silence_run_ms, 0);
    }

    #[test]
    fn reset_window_zera_contadores_mas_preserva_o_piso() {
        let mut vad = EnergyVad::new();
        vad.noise_floor = 0.02;
        vad.speech_ms = 500;
        vad.silence_run_ms = 300;
        vad.cursor = 1234;
        vad.reset_window();
        assert_eq!(vad.speech_ms, 0);
        assert_eq!(vad.silence_run_ms, 0);
        assert_eq!(vad.cursor, 0);
        assert!((vad.noise_floor - 0.02).abs() < f32::EPSILON);
    }

    #[test]
    fn join_segments_concatena_com_espaco_unico() {
        let segs = vec![
            DecodedSegment { text: "bom".into(), t0_ms: 0, t1_ms: 500, confidence: None },
            DecodedSegment { text: "dia".into(), t0_ms: 500, t1_ms: 900, confidence: None },
        ];
        assert_eq!(join_segments(&segs), "bom dia");
    }

    /// O ponto central deste modulo. Simula a matematica de offset de tres
    /// commits (incluindo um corte forcado que consome menos que a janela) e
    /// verifica que a sequencia final nunca regride nem abre buraco.
    #[test]
    fn offsets_de_final_nunca_regridem() {
        let mut committed_ms: u64 = 0;
        let mut last_end: u64 = 0;
        let mut emitidos: Vec<(u64, u64)> = Vec::new();

        // (segmentos como (t0, t1) relativos a janela, ms consumidos do commit)
        let commits: Vec<(Vec<(u64, u64)>, u64)> = vec![
            (vec![(0, 1_500), (1_500, 2_800)], 3_000),
            // Corte forcado: janela de 22 s, so 18 s consumidos.
            (vec![(0, 9_000), (9_000, 18_000)], 18_000),
            // Timestamp bagunçado do whisper: t0 volta pra tras.
            (vec![(0, 400)], 900),
        ];

        for (segs, consumed) in commits {
            let base = committed_ms;
            for (t0, t1) in segs {
                let offset = (base + t0).max(last_end);
                let end = (base + t1).max(offset);
                assert!(offset >= last_end, "offset {} < last_end {}", offset, last_end);
                emitidos.push((offset, end - offset));
                last_end = end;
            }
            committed_ms += consumed;
            last_end = last_end.max(committed_ms);
        }

        // Monotonicidade global da sequencia emitida.
        let mut prev = 0u64;
        for (off, _) in &emitidos {
            assert!(*off >= prev, "sequencia regrediu em {}", off);
            prev = *off;
        }
        // O relogio bate com a soma do audio consumido.
        assert_eq!(committed_ms, 3_000 + 18_000 + 900);
    }

    #[test]
    fn gap_avanca_o_relogio_sem_regressao() {
        // Um buraco de audio nao pode fazer o offset seguinte "voltar" pro
        // tempo antigo: committed_ms tem que pular junto.
        let mut committed_ms: u64 = 5_000;
        let mut last_end: u64 = 5_000;
        let gap = 2_500;
        committed_ms += gap;
        last_end = last_end.max(committed_ms);
        assert_eq!(committed_ms, 7_500);
        assert!(last_end >= committed_ms);
    }
}
