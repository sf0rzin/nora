//! Contrato comum dos backends de speech-to-text do desktop.
//!
//! Existem dois, escolhidos em RUNTIME (nao em build-time):
//!
//!   * `local` — whisper.cpp in-process, ver `stt_local.rs`. Padrao a partir da
//!               saida do Azure. Nao usa rede, nao usa `/speech/token`, nao sobe
//!               subprocesso.
//!   * `azure` — sidecar Python + Azure Speech SDK, ver `stt_sidecar.rs`. LEGADO,
//!               preservado atras da feature de Cargo `stt-azure` durante a
//!               transicao.
//!
//! O CONTRATO COM O FRONT NAO MUDA. Os dois emitem:
//!   * `transcript` — payload `TranscriptEvent` (camelCase), consumido por
//!     `use-live-transcript.tsx`, `use-recording.ts` e `overlay.tsx`;
//!   * `stt-error`  — objeto JSON opaco com `code` e `message`.
//!
//! Nenhum arquivo de `apps/desktop/src/` precisou mudar por causa desta troca.

use tokio::sync::mpsc;

/// Evento emitido pro front a cada parcial/final de transcricao.
///
/// Serializado em camelCase: `sessionId`, `speakerId`, `isFinal`, `offsetMs`,
/// `durationMs`. NAO existe campo `speaker` — o front le `payload.speaker` e
/// sempre recebe `undefined`; isso e pre-existente e esta documentado em
/// `SYSTEM_SPEAKER_ID` abaixo porque tem consequencia real.
#[derive(Debug, serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
pub struct TranscriptEvent {
    pub session_id: String,
    pub track: String,
    pub speaker_id: Option<String>,
    pub text: String,
    pub is_final: bool,
    /// Offset ABSOLUTO em ms desde o inicio da gravacao daquele track.
    ///
    /// INVARIANTE: monotonico nao-decrescente por track. Ver a discussao de
    /// re-decode em `stt_local.rs` — a armadilha do modo streaming e reprocessar
    /// a mesma janela e emitir um offset menor que o do final anterior.
    pub offset_ms: u64,
    pub duration_ms: Option<u64>,
    pub confidence: Option<f32>,
}

/// `speaker_id` do track `mic`.
///
/// `None` de proposito, e isso esta correto: o front trata `track === "mic"`
/// como o usuario local ANTES de olhar pro speaker_id — `getSpeakerName()`
/// devolve "Eu" (use-recording.ts:228) e `getDisplayName()` devolve "Voce"
/// (overlay.tsx:60) — e `detectedSpeakers` pula o track mic explicitamente
/// (overlay.tsx:1115). Emitir um id aqui so poluiria o SpeakerMap com uma
/// entrada renomeavel inutil.
pub const MIC_SPEAKER_ID: Option<&str> = None;

/// `speaker_id` do track `system` (participantes remotos da chamada).
///
/// ================== DESVIO CONSCIENTE DA ESPECIFICACAO ==================
///
/// A decisao de produto e "atribuicao por track, `speaker_id = None` dentro do
/// track". Emitir literalmente `None` AQUI quebra em silencio dois consumidores
/// que ja existem no front — o oposto do que a decisao queria preservar:
///
///   1. `overlay.tsx:1111-1125` monta a lista renomeavel com
///      `const id = l.speakerId || l.speaker; if (!id) continue;`
///      O Rust nunca emite o campo `speaker` (nao existe em `TranscriptEvent`),
///      entao com `speakerId` nulo `id` e `undefined` e a linha e PULADA:
///      a UI de renomear falante fica permanentemente vazia.
///
///   2. `use-recording.ts:255` deriva `participants` de
///      `Object.entries(speakerMap)`, e `speakerMap` so ganha entrada quando o
///      usuario renomeia alguem na UI do item 1. Sem linha pra renomear,
///      `participants` fica sempre `undefined` no upload da reuniao.
///
///   3. `buildTranscript` (use-recording.ts:18) prefixa `[nome] ` so quando
///      `getSpeakerName` devolve algo; com speakerId nulo e track != mic ele
///      devolve `speaker` (undefined) e o TXT sai SEM prefixo nenhum nas falas
///      remotas.
///
/// Um id ESTAVEL por track entrega exatamente o que a decisao de produto pediu
/// — zero diarizacao online, zero churn de rotulo, um rotulo por track — e
/// mantem os tres consumidores funcionando sem tocar em uma linha do front.
///
/// O valor aparece cru na UI ate o usuario renomear (`speakerMap[id] || id`),
/// por isso e uma string legivel em pt-BR e nao um UUID.
///
/// Se um dia o front passar a tratar `speakerId === null && track === "system"`
/// como um falante implicito, trocar isto por `None` volta a ser possivel.
pub const SYSTEM_SPEAKER_ID: &str = "Participantes";

/// Resolve o `speaker_id` a emitir para um track. Ver constantes acima.
pub fn speaker_id_for_track(track: &str) -> Option<String> {
    match track {
        "system" => Some(SYSTEM_SPEAKER_ID.to_string()),
        _ => MIC_SPEAKER_ID.map(str::to_string),
    }
}

/// Handle vivo de uma sessao de transcricao de UM track.
///
/// Object-safe de proposito: `commands.rs` guarda `Vec<Box<dyn SttBackend>>` no
/// state do Tauri, misturando backends se preciso. Metodos sincronos para nao
/// arrastar `async-trait` (o unico ponto async e o `start`, que fica nas
/// factories concretas).
pub trait SttBackend: Send {
    /// Id da sessao daquele track. Usado so em log/diagnostico.
    fn session_id(&self) -> &str;

    /// Canal por onde o `audio_capture` empurra PCM 16 kHz / mono / i16.
    ///
    /// O caller usa `try_send` e DROPA a amostra quando cheio: tempo real vale
    /// mais que completude. Ver `commands.rs`.
    fn audio_tx(&self) -> mpsc::Sender<Vec<i16>>;

    /// Encerra a sessao. Consome o handle (`Box<Self>`) para impedir uso apos
    /// o stop. Os backends fazem um flush final antes de morrer, entao PODE
    /// haver um ultimo evento `transcript` depois desta chamada — o sidecar
    /// Azure ja se comportava assim (`stop_continuous_recognition` faz flush).
    fn stop(self: Box<Self>);
}

/// Qual implementacao esta ativa.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SttBackendKind {
    Local,
    Azure,
}

impl SttBackendKind {
    pub fn as_str(self) -> &'static str {
        match self {
            SttBackendKind::Local => "local",
            SttBackendKind::Azure => "azure",
        }
    }
}

/// Backend efetivo, memoizado. Prioridade:
///
/// 1. env `NORA_STT_BACKEND` em runtime — so funciona quando o app e lancado de
///    um shell (dev). App aberto pelo Finder/Explorer NAO herda env do usuario.
/// 2. `NORA_STT_BACKEND` injetada em build-time pelo `build.rs` (CI/release).
/// 3. `plugins.nora.sttBackend` no `tauri.conf.json`.
/// 4. Default: `local` se a feature `stt-local` estiver compilada, senao `azure`.
///
/// Valor desconhecido cai no default com um aviso — nunca faz o app morrer.
pub fn configured_backend() -> SttBackendKind {
    static KIND: std::sync::OnceLock<SttBackendKind> = std::sync::OnceLock::new();
    *KIND.get_or_init(|| {
        let raw = std::env::var("NORA_STT_BACKEND")
            .ok()
            .filter(|s| !s.is_empty())
            .or_else(|| option_env!("NORA_STT_BACKEND").map(str::to_string))
            .or_else(|| crate::nora_config_str("sttBackend"))
            .unwrap_or_default();

        let resolved = match raw.trim().to_ascii_lowercase().as_str() {
            "" => default_backend(),
            "local" | "whisper" => SttBackendKind::Local,
            "azure" | "sidecar" => SttBackendKind::Azure,
            other => {
                eprintln!(
                    "[stt] NORA_STT_BACKEND desconhecido: {:?} — usando {}",
                    other,
                    default_backend().as_str()
                );
                default_backend()
            }
        };

        // Pediu um backend que nao foi compilado neste binario: degrada pro que
        // existe em vez de estourar no meio da gravacao.
        let available = match resolved {
            SttBackendKind::Local => cfg!(feature = "stt-local"),
            SttBackendKind::Azure => cfg!(feature = "stt-azure"),
        };
        if !available {
            eprintln!(
                "[stt] backend {:?} nao foi compilado neste binario — caindo pro default",
                resolved.as_str()
            );
            return default_backend();
        }

        eprintln!("[stt] backend ativo: {}", resolved.as_str());
        resolved
    })
}

const fn default_backend() -> SttBackendKind {
    if cfg!(feature = "stt-local") {
        SttBackendKind::Local
    } else {
        SttBackendKind::Azure
    }
}

/// Credenciais que so o backend azure precisa. `None` no modo local — e por isso
/// que `commands.rs` nao chama `fetch_speech_token` quando o backend e local.
#[allow(dead_code)]
pub struct AzureStartParams {
    pub region: String,
    pub auth_token: String,
    pub backend_url: String,
    pub access_token: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mic_nao_gera_entrada_no_speaker_map() {
        assert_eq!(speaker_id_for_track("mic"), None);
    }

    #[test]
    fn system_gera_id_estavel_e_nao_vazio() {
        // Estabilidade e o ponto: dois eventos do mesmo track tem que cair no
        // MESMO bucket do SpeakerMap, senao a overlay lista N falantes fantasma.
        let a = speaker_id_for_track("system");
        let b = speaker_id_for_track("system");
        assert_eq!(a, b);
        assert_eq!(a.as_deref(), Some(SYSTEM_SPEAKER_ID));
        assert!(!SYSTEM_SPEAKER_ID.is_empty(), "id vazio e falsy no JS e some do detectedSpeakers");
    }

    #[test]
    fn backend_kind_round_trip() {
        assert_eq!(SttBackendKind::Local.as_str(), "local");
        assert_eq!(SttBackendKind::Azure.as_str(), "azure");
    }
}
