use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot};
use base64::Engine;

use crate::speech_token::fetch_speech_token;

/// Idle timeout no stdout reader. Se o sidecar não emitir NADA (ready/partial/final/stopped)
/// por 10 minutos, consideramos zumbi (Python travou em GIL, Azure WS pendurou) e abortamos
/// — antes esse loop esperava indefinidamente.
const SIDECAR_IDLE_TIMEOUT: tokio::time::Duration = tokio::time::Duration::from_secs(600);

/// Capacidade do canal de escrita pro stdin do sidecar. Cobre rajadas de áudio
/// (até ~20 chunks de 100ms) sem bloquear o produtor.
const STDIN_QUEUE_CAPACITY: usize = 32;

#[derive(Debug, serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
pub struct TranscriptEvent {
    pub session_id: String,
    pub track: String,
    pub speaker_id: Option<String>,
    pub text: String,
    pub is_final: bool,
    pub offset_ms: u64,
    pub duration_ms: Option<u64>,
    pub confidence: Option<f32>,
}

pub struct SidecarHandle {
    pub session_id: String,
    pub audio_tx: mpsc::Sender<Vec<i16>>,
    stop_tx: Option<oneshot::Sender<()>>,
    pub track_label: String,
}

impl Drop for SidecarHandle {
    fn drop(&mut self) {
        // Defesa contra leak de subprocess: se o `Vec<SidecarHandle>` for limpo sem
        // passar por `stop()` (panic, logout, app close abrupto), o `stop_tx.take()`
        // garante o sinal de cancelamento. Sem isso, o sidecar Python continua rodando
        // ate o app fechar — quota Azure/CPU desperdicada.
        if let Some(tx) = self.stop_tx.take() {
            let _ = tx.send(());
        }
        #[cfg(debug_assertions)]
        eprintln!("[SidecarHandle] DROPPED session_id={}", self.session_id);
    }
}

fn sidecar_binary_name() -> Option<String> {
    let arch = std::env::consts::ARCH; // x86_64, aarch64, etc.
    let os = std::env::consts::OS;     // windows, macos, linux
    let ext = if cfg!(target_os = "windows") { ".exe" } else { "" };
    
    // Tenta nomes conhecidos (msvc, gnu, darwin, musl)
    let candidates: Vec<String> = match os {
        "windows" => vec![
            format!("nora-stt-sidecar-{}-pc-windows-msvc{}", arch, ext),
            format!("nora-stt-sidecar-{}-pc-windows-gnu{}", arch, ext),
        ],
        "macos" => vec![
            format!("nora-stt-sidecar-{}-apple-darwin{}", arch, ext),
        ],
        _ => vec![
            format!("nora-stt-sidecar-{}-unknown-linux-gnu{}", arch, ext),
            format!("nora-stt-sidecar-{}-unknown-linux-musl{}", arch, ext),
        ],
    };
    
    // 1. Check NORA_SIDECAR_PATH env var (highest priority)
    if let Ok(path) = std::env::var("NORA_SIDECAR_PATH") {
        let p = PathBuf::from(path);
        if p.exists() {
            return Some(p.file_name()?.to_string_lossy().to_string());
        }
    }
    
    // 2. Relative to executable (packaged app)
    if let Ok(exe_path) = std::env::current_exe() {
        if let Some(exe_dir) = exe_path.parent() {
            let binaries_dir = exe_dir.join("binaries");
            if let Ok(entries) = std::fs::read_dir(&binaries_dir) {
                for entry in entries.flatten() {
                    let fname = entry.file_name().to_string_lossy().to_string();
                    if fname.starts_with("nora-stt-sidecar-") && fname.ends_with(ext) {
                        return Some(fname);
                    }
                }
            }
        }
    }
    
    // 3. Relative to Rust source tree (dev mode)
    if let Ok(manifest_dir) = std::env::var("CARGO_MANIFEST_DIR") {
        let binaries_dir = PathBuf::from(&manifest_dir).join("binaries");
        if let Ok(entries) = std::fs::read_dir(&binaries_dir) {
            for entry in entries.flatten() {
                let fname = entry.file_name().to_string_lossy().to_string();
                if fname.starts_with("nora-stt-sidecar-") && fname.ends_with(ext) {
                    return Some(fname);
                }
            }
        }
    }
    
    // Fallback: primeiro nome da lista de candidatos (mesmo que não exista ainda)
    candidates.into_iter().next()
}

fn resolve_sidecar_binary() -> Option<PathBuf> {
    let name = sidecar_binary_name()?;

    // 1. Check NORA_SIDECAR_PATH env var (highest priority)
    if let Ok(path) = std::env::var("NORA_SIDECAR_PATH") {
        let p = PathBuf::from(path);
        if p.exists() {
            return Some(p);
        }
    }

    // 2. Relative to executable (packaged app):
    if let Ok(exe_path) = std::env::current_exe() {
        if let Some(exe_dir) = exe_path.parent() {
            let candidates = [
                exe_dir.join(format!("binaries/{}", &name)),
                exe_dir.join(format!("../binaries/{}", &name)),
                exe_dir.join(format!("../../binaries/{}", &name)),
            ];
            for candidate in candidates {
                if candidate.exists() {
                    return Some(candidate);
                }
            }
        }
    }

    // 3. Relative to the Rust source tree (dev mode):
    if let Ok(manifest_dir) = std::env::var("CARGO_MANIFEST_DIR") {
        let dev_binary = PathBuf::from(&manifest_dir)
            .join(format!("binaries/{}", &name));
        if dev_binary.exists() {
            return Some(dev_binary);
        }
    }

    None
}

#[allow(dead_code)]
impl SidecarHandle {
    pub async fn start(
        app: AppHandle,
        region: String,
        auth_token: String,
        language: String,
        track_label: String,
        backend_url: String,
        access_token: String,
    ) -> Result<Self, String> {
        let session_id = uuid::Uuid::new_v4().to_string();
        let (audio_tx, audio_rx) = mpsc::channel::<Vec<i16>>(100);
        let (stop_tx, stop_rx) = oneshot::channel::<()>();
        let (ready_tx, ready_rx) = oneshot::channel::<()>();

        let app_clone = app.clone();
        let session_id_clone = session_id.clone();
        let track_label_clone = track_label.clone();

        let join = tokio::spawn(async move {
            if let Err(e) = run_sidecar(
                app_clone,
                session_id_clone,
                track_label_clone,
                region,
                auth_token,
                language,
                audio_rx,
                stop_rx,
                ready_tx,
                backend_url,
                access_token,
            )
            .await
            {
                #[cfg(debug_assertions)]
                eprintln!("[stt_sidecar] error: {}", e);
            }
        });

        // Wait for ready with timeout
        match tokio::time::timeout(tokio::time::Duration::from_secs(5), ready_rx).await {
            Ok(Ok(())) => {}
            Ok(Err(_)) => {
                join.abort();
                return Err("Sidecar ready channel closed".into());
            }
            Err(_) => {
                join.abort();
                return Err("Sidecar startup timeout (5s)".into());
            }
        }

        Ok(Self {
            session_id,
            audio_tx,
            stop_tx: Some(stop_tx),
            track_label,
        })
    }

    pub fn feed(&self, samples: Vec<i16>) -> Result<(), String> {
        self.audio_tx
            .try_send(samples)
            .map_err(|e| format!("Failed to feed audio: {}", e))
    }

    pub fn stop(mut self) {
        if let Some(stop_tx) = self.stop_tx.take() {
            let _ = stop_tx.send(());
        }
    }
}

/// Spawn a background task that refreshes the auth token every 5 minutes.
/// Envia `{"type":"refresh_token",...}` pra fila única do stdin writer.
/// Antes isso usava `Arc<Mutex<ChildStdin>>` — qualquer write bloqueado (pipe cheio)
/// segurava o lock indefinidamente e travava todas as outras escritas, incluindo
/// o próprio refresh. Agora todas as escritas passam por um único produtor-consumidor.
async fn spawn_refresh_loop(
    session_id: String,
    stdin_tx: mpsc::Sender<String>,
    backend_url: String,
    access_token: String,
    region: String,
    mut cancel_rx: oneshot::Receiver<()>,
    initial_ttl_secs: Option<u64>,
) {
    // Calcula intervalo de refresh baseado no TTL do token.
    // Azure tokens duram ~10 min; usamos min(TTL - 60s_buffer, 300s_max).
    // Se não soubermos o TTL, fallback para 5 minutos.
    let compute_interval = |ttl: Option<u64>| -> tokio::time::Duration {
        let secs = ttl.map(|t| t.min(300)).unwrap_or(300);
        tokio::time::Duration::from_secs(secs.max(30)) // mínimo 30s para não spammar
    };
    let mut refresh_interval = compute_interval(initial_ttl_secs);

    #[cfg(debug_assertions)]
    eprintln!("[stt_sidecar] refresh loop started for session {}", session_id);

    loop {
        tokio::select! {
            _ = &mut cancel_rx => {
                #[cfg(debug_assertions)]
                eprintln!("[stt_sidecar] refresh loop cancelled for session {}", session_id);
                break;
            }
            _ = tokio::time::sleep(refresh_interval) => {
                #[cfg(debug_assertions)]
                eprintln!("[stt_sidecar] refreshing token for session {}", session_id);

                // Retry with exponential backoff: 1s, 2s, 4s, 8s, 16s, then every 30s
                let mut retry_delay = tokio::time::Duration::from_secs(1);
                const MAX_RETRY_DELAY: tokio::time::Duration = tokio::time::Duration::from_secs(30);

                loop {
                    match fetch_speech_token(&backend_url, &access_token, Some(&region)).await {
                        Ok(token_response) => {
                            let new_ttl = token_response.ttl_seconds();
                            refresh_interval = compute_interval(new_ttl);
                            #[cfg(debug_assertions)]
                            eprintln!("[stt_sidecar] next refresh in {:?} (TTL: {:?})", refresh_interval, new_ttl);

                            let refresh_msg = serde_json::json!({
                                "v": 1,
                                "type": "refresh_token",
                                "session_id": session_id,
                                "auth_token": token_response.token,
                            });

                            let line = format!("{}\n", refresh_msg);
                            if stdin_tx.send(line).await.is_err() {
                                #[cfg(debug_assertions)]
                                eprintln!("[stt_sidecar] stdin writer closed; refresh loop exiting");
                                return;
                            }
                            #[cfg(debug_assertions)]
                            eprintln!("[stt_sidecar] refresh_token enqueued for session {}", session_id);
                            break;
                        }
                        Err(e) => {
                            eprintln!("[stt_sidecar] failed to refresh token (retry in {:?}): {}", retry_delay, e);
                            tokio::select! {
                                _ = &mut cancel_rx => {
                                    #[cfg(debug_assertions)]
                                    eprintln!("[stt_sidecar] refresh loop cancelled during retry");
                                    return;
                                }
                                _ = tokio::time::sleep(retry_delay) => {}
                            }
                            retry_delay = std::cmp::min(retry_delay * 2, MAX_RETRY_DELAY);
                        }
                    }
                }
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn run_sidecar(
    app: AppHandle,
    session_id: String,
    track_label: String,
    region: String,
    auth_token: String,
    language: String,
    mut audio_rx: mpsc::Receiver<Vec<i16>>,
    mut stop_rx: oneshot::Receiver<()>,
    ready_tx: oneshot::Sender<()>,
    backend_url: String,
    access_token: String,
) -> Result<(), String> {
    let binary_path = resolve_sidecar_binary()
        .ok_or("Sidecar binary not found. Set NORA_SIDECAR_PATH or ensure binaries/ directory exists")?;

    #[cfg(debug_assertions)]
    eprintln!("[stt_sidecar] using binary: {:?}", binary_path);

    let mut child = Command::new(&binary_path)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .map_err(|e| format!("failed to spawn sidecar: {}", e))?;

    let mut child_stdin = child
        .stdin
        .take()
        .ok_or("failed to get stdin")?;
    let stdout = child
        .stdout
        .take()
        .ok_or("failed to get stdout")?;
    let stderr = child
        .stderr
        .take()
        .ok_or("failed to get stderr")?;

    #[cfg(debug_assertions)]
    eprintln!("[stt_sidecar] spawned sidecar for session {}", session_id);

    // Canal único pra tudo que escreve no stdin do sidecar. Antes usávamos
    // `Arc<Mutex<ChildStdin>>` e cada task (writer, refresh, stop) competia pelo lock;
    // se o pipe enchesse, o `write_all` bloqueava SEGURANDO o Mutex e travava todo
    // o resto. Agora um único stdin_writer consome o canal serialmente, e os produtores
    // (audio writer + refresh loop) só esperam no canal (não no I/O bloqueante).
    let (stdin_tx, mut stdin_rx) = mpsc::channel::<String>(STDIN_QUEUE_CAPACITY);

    // start message: vai no início do canal
    let start_msg = serde_json::json!({
        "v": 1,
        "type": "start",
        "session_id": session_id,
        "azure_region": region,
        "auth_token": auth_token,
        "language": language,
        "sample_rate": 16000,
        "channels": 1,
        "speakers_hint": 2,
    });
    stdin_tx
        .send(format!("{}\n", start_msg))
        .await
        .map_err(|_| "failed to enqueue start message")?;

    // stdin writer task: único dono do ChildStdin
    let stdin_writer = tokio::spawn(async move {
        while let Some(line) = stdin_rx.recv().await {
            if let Err(e) = child_stdin.write_all(line.as_bytes()).await {
                #[cfg(debug_assertions)]
                eprintln!("[stt_sidecar] stdin write error: {}", e);
                break;
            }
            if let Err(e) = child_stdin.flush().await {
                #[cfg(debug_assertions)]
                eprintln!("[stt_sidecar] stdin flush error: {}", e);
                break;
            }
        }
    });

    // Spawn refresh loop
    let (refresh_cancel_tx, refresh_cancel_rx) = oneshot::channel::<()>();
    let refresh_handle = tokio::spawn(spawn_refresh_loop(
        session_id.clone(),
        stdin_tx.clone(),
        backend_url,
        access_token,
        region.clone(),
        refresh_cancel_rx,
        None,
    ));

    // Audio writer: converte chunks PCM em mensagens NDJSON e enfileira.
    // dropped_audio_count: chunks descartados quando a fila enche (rajada extrema).
    let dropped_audio_count = Arc::new(AtomicU64::new(0));
    let writer_session = session_id.clone();
    let writer_stdin_tx = stdin_tx.clone();
    let writer_dropped = Arc::clone(&dropped_audio_count);
    let writer = tokio::spawn(async move {
        let mut seq = 0u64;
        while let Some(samples) = audio_rx.recv().await {
            let pcm_bytes: Vec<u8> = samples
                .iter()
                .flat_map(|s| s.to_le_bytes())
                .collect();
            let b64 = base64::engine::general_purpose::STANDARD.encode(&pcm_bytes);

            let audio_msg = serde_json::json!({
                "v": 1,
                "type": "audio",
                "session_id": writer_session,
                "seq": seq,
                "pcm_b64": b64,
            });
            seq += 1;

            let line = format!("{}\n", audio_msg);
            // Tenta sem bloquear. Em Full, conta drop (rajada extrema não pode
            // comer todo o backlog). Em Closed, sidecar morreu.
            // BUG ANTERIOR: usava `if let Err(Full)=try_send else if send(line).await`.
            // Quando try_send retornava Ok(()), o `else if` entrava e fazia send(line).await,
            // ENVIANDO A MESMA LINHA DE NOVO — o Python via cada `seq` duplicado e
            // emitia SEQUENCE_GAP em loop.
            match writer_stdin_tx.try_send(line) {
                Ok(()) => {}
                Err(mpsc::error::TrySendError::Full(_)) => {
                    let n = writer_dropped.fetch_add(1, Ordering::Relaxed) + 1;
                    if n.is_power_of_two() {
                        eprintln!(
                            "[stt_sidecar] WARN: dropped {} audio chunks (fila cheia) sess={}",
                            n, writer_session
                        );
                    }
                }
                Err(mpsc::error::TrySendError::Closed(_)) => {
                    // Sidecar fechou stdin; sai do loop.
                    break;
                }
            }
        }

        let stop_msg = serde_json::json!({
            "v": 1,
            "type": "stop",
            "session_id": writer_session,
        });
        let _ = writer_stdin_tx.send(format!("{}\n", stop_msg)).await;
    });

    // Stderr reader: trunca linhas a 512 bytes e nunca eprintln! direto sem prefixo
    // — em prod o stderr do sidecar é redirecionado pra logs e pode conter mensagens
    // de erro do Azure SDK com payload sensível.
    let stderr_handle = tokio::spawn(async move {
        let reader = BufReader::new(stderr);
        let mut lines = reader.lines();
        while let Ok(Some(line)) = lines.next_line().await {
            let trimmed = line.trim();
            let truncated: String = if trimmed.len() > 512 {
                format!("{}…[+{}b]", &trimmed[..512], trimmed.len() - 512)
            } else {
                trimmed.to_string()
            };
            #[cfg(debug_assertions)]
            eprintln!("[sidecar stderr] {}", truncated);
            #[cfg(not(debug_assertions))]
            {
                let _ = truncated;
            }
        }
    });

    // Stdout reader
    let mut ready_sent = false;
    let mut ready_tx_opt = Some(ready_tx);

    let stdout_reader = BufReader::new(stdout);
    let mut lines = stdout_reader.lines();

    loop {
        tokio::select! {
            line_result = tokio::time::timeout(SIDECAR_IDLE_TIMEOUT, lines.next_line()) => {
                let line_result = match line_result {
                    Ok(r) => r,
                    Err(_) => {
                        eprintln!(
                            "[stt_sidecar] idle timeout ({}s) sem mensagens do sidecar — assumindo zumbi",
                            SIDECAR_IDLE_TIMEOUT.as_secs()
                        );
                        break;
                    }
                };
                match line_result {
                    Ok(Some(line)) => {
                        let parsed: Result<serde_json::Value, _> = serde_json::from_str(&line);
                        if let Ok(json) = parsed {
                            let msg_type = json.get("type").and_then(|v| v.as_str());

                            match msg_type {
                                Some("ready") if !ready_sent => {
                                    if let Some(tx) = ready_tx_opt.take() {
                                        let _ = tx.send(());
                                    }
                                    ready_sent = true;
                                }
                                Some("partial") | Some("final") => {
                                    let evt = TranscriptEvent {
                                        session_id: json.get("session_id")
                                            .and_then(|v| v.as_str())
                                            .unwrap_or(&session_id)
                                            .to_string(),
                                        track: track_label.clone(),
                                        speaker_id: json.get("speaker_id")
                                            .and_then(|v| v.as_str())
                                            .map(|s| s.to_string()),
                                        text: json.get("text")
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("")
                                            .to_string(),
                                        is_final: msg_type == Some("final"),
                                        offset_ms: json.get("offset_ms")
                                            .and_then(|v| v.as_u64())
                                            .unwrap_or(0),
                                        duration_ms: json.get("duration_ms")
                                            .and_then(|v| v.as_u64()),
                                        confidence: json.get("confidence")
                                            .and_then(|v| v.as_f64())
                                            .map(|v| v as f32),
                                    };
                                    let _ = app.emit("transcript", &evt);
                                }
                                Some("error") => {
                                    let _ = app.emit("stt-error", &json);
                                }
                                Some("stopped") => {
                                    break;
                                }
                                _ => {}
                            }
                        } else {
                            // Linha não-JSON no stdout do sidecar (provavelmente traceback Python).
                            // Antes era ignorada em silêncio; agora logamos para diagnóstico.
                            #[cfg(debug_assertions)]
                            eprintln!("[stt_sidecar] stdout non-JSON line: {}", line.trim());
                        }
                    }
                    Ok(None) => {
                        eprintln!("[stt_sidecar] stdout closed");
                        break;
                    }
                    Err(e) => {
                        eprintln!("[stt_sidecar] read error: {}", e);
                        break;
                    }
                }
            }
            _ = &mut stop_rx => {
                eprintln!("[stt_sidecar] stop signal received");
                break;
            }
        }
    }

    // Cancel refresh loop primeiro pra que ele não tente enfileirar mais nada
    let _ = refresh_cancel_tx.send(());
    refresh_handle.abort();

    // Encerra produtores do canal stdin (audio writer). Quando todos os senders
    // forem dropados, stdin_writer sai do recv() naturalmente. Damos 2s pra
    // o stop message escoar antes de matar o child.
    writer.abort();
    drop(stdin_tx);
    let _ = tokio::time::timeout(
        tokio::time::Duration::from_secs(2),
        stdin_writer,
    )
    .await;

    stderr_handle.abort();
    let _ = child.kill().await;
    let _ = child.wait().await; // reap zombie
    let drops = dropped_audio_count.load(Ordering::Relaxed);
    if drops > 0 {
        #[cfg(debug_assertions)]
        eprintln!(
            "[stt_sidecar] session ended with {} dropped audio chunks",
            drops
        );
    }
    Ok(())
}
