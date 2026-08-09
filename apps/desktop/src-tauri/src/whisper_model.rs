//! Resolution, caching and on-demand download of the whisper.cpp GGML model.
//!
//! WHY NOT SHIP IT IN THE BUNDLE
//! -----------------------------
//! `small` is 465 MiB and `medium` 1.4 GiB. Adding that to the .msi/.dmg/.AppImage
//! (a) blows past the practical GitHub Release artifact limit, (b) makes EVERY
//! update — even a CSS bugfix — re-download the whole model through the
//! updater, and (c) triples the CI build time across the three targets. The model
//! is downloaded ONCE, on first use, and stays in the user's app data dir.
//!
//! ON-DISK LAYOUT
//! --------------
//!   <app_data_dir>/models/ggml-small.bin          verified model
//!   <app_data_dir>/models/ggml-small.bin.part     download in progress
//!   <app_data_dir>/models/ggml-small.bin.sha256   verification sentinel
//!
//! The `.part` only becomes `.bin` AFTER the sha256 matches. An interrupted
//! download is never used as a model — worst case it is redone from scratch (there
//! is no Range resume: HuggingFace supports it, but resuming without validating the
//! ETag is how you corrupt a file in silence).

use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::AsyncWriteExt;

/// Tauri download-progress event. The front end does not listen yet (nothing
/// breaks if nobody listens); it is the hook ready for a progress bar in the UI.
pub const MODEL_PROGRESS_EVENT: &str = "stt-model-progress";

/// Official whisper.cpp GGML repository. Swappable for an internal mirror via
/// `NORA_WHISPER_MODEL_BASE_URL` (corporate network that blocks HF).
const DEFAULT_BASE_URL: &str = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelSize {
    /// 74 MiB. Only for smoke test / CI — pt-BR quality is bad.
    Tiny,
    /// 141 MiB. Acceptable in pt-BR, runs comfortably on a weak CPU.
    Base,
    /// 465 MiB. DEFAULT: best quality/latency trade-off in pt-BR on a common CPU.
    Small,
    /// 1.4 GiB. Best quality, needs a strong CPU or a GPU for real time.
    Medium,
}

/// Metadata for a model. `sha256` and `size_bytes` came from the HuggingFace
/// paths-info API (`lfs.oid` = sha256 of the blob), not from memory.
struct ModelSpec {
    file_name: &'static str,
    sha256: &'static str,
    size_bytes: u64,
}

impl ModelSize {
    pub fn as_str(self) -> &'static str {
        match self {
            ModelSize::Tiny => "tiny",
            ModelSize::Base => "base",
            ModelSize::Small => "small",
            ModelSize::Medium => "medium",
        }
    }

    /// Tolerant parse of the config value. Unknown -> `Small` with a warning.
    pub fn parse(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "tiny" => ModelSize::Tiny,
            "base" => ModelSize::Base,
            "small" | "" => ModelSize::Small,
            "medium" => ModelSize::Medium,
            other => {
                eprintln!(
                    "[whisper_model] tamanho desconhecido {:?} — usando 'small'",
                    other
                );
                ModelSize::Small
            }
        }
    }

    fn spec(self) -> ModelSpec {
        match self {
            ModelSize::Tiny => ModelSpec {
                file_name: "ggml-tiny.bin",
                sha256: "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
                size_bytes: 77_691_713,
            },
            ModelSize::Base => ModelSpec {
                file_name: "ggml-base.bin",
                sha256: "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
                size_bytes: 147_951_465,
            },
            ModelSize::Small => ModelSpec {
                file_name: "ggml-small.bin",
                sha256: "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
                size_bytes: 487_601_967,
            },
            ModelSize::Medium => ModelSpec {
                file_name: "ggml-medium.bin",
                sha256: "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208",
                size_bytes: 1_533_763_059,
            },
        }
    }

    /// Bytes the download will cost. Used by the progress event and useful
    /// for a confirmation prompt before pulling 1.5 GiB on `medium`.
    #[allow(dead_code)] // no consumer yet: the progress UI does not exist.
    pub fn download_bytes(self) -> u64 {
        self.spec().size_bytes
    }
}

/// Configured size, memoized. Same priority chain as the backend:
/// runtime env -> build-time env -> `plugins.nora.whisperModel` -> `small`.
pub fn configured_size() -> ModelSize {
    static SIZE: std::sync::OnceLock<ModelSize> = std::sync::OnceLock::new();
    *SIZE.get_or_init(|| {
        let raw = std::env::var("NORA_WHISPER_MODEL")
            .ok()
            .filter(|s| !s.is_empty())
            .or_else(|| option_env!("NORA_WHISPER_MODEL").map(str::to_string))
            .or_else(|| crate::nora_config_str("whisperModel"))
            .unwrap_or_default();
        let size = ModelSize::parse(&raw);
        eprintln!("[whisper_model] tamanho configurado: {}", size.as_str());
        size
    })
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelProgress {
    pub model: String,
    /// `checking` | `downloading` | `verifying` | `ready` | `error`
    pub phase: &'static str,
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
    /// 0.0 to 100.0. Stays at 0 when the total is unknown.
    pub percent: f32,
    pub message: Option<String>,
}

fn emit(app: &AppHandle, p: ModelProgress) {
    // Best-effort: a closed window / unmounted front end must not kill the download.
    let _ = app.emit(MODEL_PROGRESS_EVENT, &p);
}

fn progress(size: ModelSize, phase: &'static str, done: u64, total: u64) -> ModelProgress {
    ModelProgress {
        model: size.as_str().to_string(),
        phase,
        downloaded_bytes: done,
        total_bytes: total,
        percent: if total > 0 {
            (done as f64 / total as f64 * 100.0) as f32
        } else {
            0.0
        },
        message: None,
    }
}

pub fn models_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("nao consegui resolver app_data_dir: {e}"))?
        .join("models");
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("nao consegui criar {}: {e}", dir.display()))?;
    Ok(dir)
}

/// Serializes the download across tracks. `mic` and `system` come up together in
/// `start_recording`; without this both would pull 465 MiB in parallel into the
/// same `.part`. The lock covers the check TOO, not just the download (check-then-act).
fn download_lock() -> &'static tokio::sync::Mutex<()> {
    static LOCK: std::sync::OnceLock<tokio::sync::Mutex<()>> = std::sync::OnceLock::new();
    LOCK.get_or_init(|| tokio::sync::Mutex::new(()))
}

/// Ensures the model is on disk and verified; returns the path.
///
/// Idempotent and safe to call from several tracks at once. From the second
/// call on the cost is a `metadata()` + reading the sentinel.
pub async fn ensure_model(app: &AppHandle, size: ModelSize) -> Result<PathBuf, String> {
    // Escape hatch: your own model (q5_1 quantized, pt-BR fine-tune, offline
    // mirror). Skips download AND checksum on purpose — whoever points at a
    // local file takes responsibility for it.
    if let Ok(raw) = std::env::var("NORA_WHISPER_MODEL_PATH") {
        let p = PathBuf::from(raw);
        if p.is_file() {
            eprintln!(
                "[whisper_model] usando modelo local de NORA_WHISPER_MODEL_PATH: {} (checksum NAO verificado)",
                p.display()
            );
            return Ok(p);
        }
        return Err(format!(
            "NORA_WHISPER_MODEL_PATH aponta pra algo que nao e arquivo: {}",
            p.display()
        ));
    }

    let spec = size.spec();
    let dir = models_dir(app)?;
    let final_path = dir.join(spec.file_name);
    let sentinel = dir.join(format!("{}.sha256", spec.file_name));

    let _guard = download_lock().lock().await;

    emit(app, progress(size, "checking", 0, spec.size_bytes));

    if is_usable(&final_path, &sentinel, spec.sha256, spec.size_bytes) {
        emit(
            app,
            progress(size, "ready", spec.size_bytes, spec.size_bytes),
        );
        return Ok(final_path);
    }

    // File exists but did not pass the cheap check: delete instead of trying to
    // repair. A truncated GGML makes whisper.cpp abort inside the C++, which
    // takes down the whole process instead of becoming a Result::Err.
    if final_path.exists() {
        eprintln!(
            "[whisper_model] {} existe mas falhou na verificacao — rebaixando",
            final_path.display()
        );
        let _ = std::fs::remove_file(&final_path);
        let _ = std::fs::remove_file(&sentinel);
    }

    let base = std::env::var("NORA_WHISPER_MODEL_BASE_URL")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| DEFAULT_BASE_URL.to_string());
    let url = format!("{}/{}", base.trim_end_matches('/'), spec.file_name);
    let part_path = dir.join(format!("{}.part", spec.file_name));

    match download_and_verify(app, size, &url, &part_path, &final_path, &sentinel, &spec).await {
        Ok(()) => {
            emit(
                app,
                progress(size, "ready", spec.size_bytes, spec.size_bytes),
            );
            Ok(final_path)
        }
        Err(e) => {
            let _ = tokio::fs::remove_file(&part_path).await;
            let mut p = progress(size, "error", 0, spec.size_bytes);
            p.message = Some(e.clone());
            emit(app, p);
            Err(e)
        }
    }
}

/// Cheap cache check: file with the exact size + sentinel with the expected
/// hash. Re-hashing 465 MiB on every `start_recording` would cost ~0.5 s of
/// latency to confirm something already confirmed; the sentinel is written
/// ONLY after a full sha256 matches.
fn is_usable(model: &Path, sentinel: &Path, expected_sha: &str, expected_len: u64) -> bool {
    let Ok(meta) = std::fs::metadata(model) else {
        return false;
    };
    if !meta.is_file() || meta.len() != expected_len {
        return false;
    }
    match std::fs::read_to_string(sentinel) {
        Ok(recorded) => recorded.trim().eq_ignore_ascii_case(expected_sha),
        Err(_) => false,
    }
}

#[allow(clippy::too_many_arguments)]
async fn download_and_verify(
    app: &AppHandle,
    size: ModelSize,
    url: &str,
    part_path: &Path,
    final_path: &Path,
    sentinel: &Path,
    spec: &ModelSpec,
) -> Result<(), String> {
    eprintln!("[whisper_model] baixando {} -> {}", url, part_path.display());

    // Own client: `http_proxy::http_client()` has a TOTAL timeout of 30 s,
    // which kills a 465 MiB download. Here we only cap the connect; the body can
    // take as long as the user's network needs.
    let client = reqwest::Client::builder()
        .connect_timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| format!("cliente http: {e}"))?;

    let mut resp = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("download do modelo falhou: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!(
            "download do modelo: HTTP {} em {}",
            resp.status(),
            url
        ));
    }

    let total = resp.content_length().unwrap_or(spec.size_bytes);

    let mut file = tokio::fs::File::create(part_path)
        .await
        .map_err(|e| format!("nao consegui criar {}: {e}", part_path.display()))?;

    let mut hasher = Sha256::new();
    let mut downloaded: u64 = 0;
    // Event throttle: one emit per 8 KiB chunk would flood the webview IPC
    // with ~60k messages. Emits every ~1 MiB.
    let mut next_emit_at: u64 = 0;

    while let Some(chunk) = resp
        .chunk()
        .await
        .map_err(|e| format!("erro lendo corpo do download: {e}"))?
    {
        hasher.update(&chunk);
        file.write_all(&chunk)
            .await
            .map_err(|e| format!("erro escrevendo {}: {e}", part_path.display()))?;
        downloaded += chunk.len() as u64;
        if downloaded >= next_emit_at {
            emit(app, progress(size, "downloading", downloaded, total));
            next_emit_at = downloaded + 1_048_576;
        }
    }

    file.flush()
        .await
        .map_err(|e| format!("flush do modelo: {e}"))?;
    // fsync before the rename: without it a crash/power loss right after the
    // rename leaves a .bin with a valid sentinel and incomplete content.
    file.sync_all()
        .await
        .map_err(|e| format!("fsync do modelo: {e}"))?;
    drop(file);

    emit(app, progress(size, "verifying", downloaded, total));

    let actual = hex_lower(&hasher.finalize());
    if !actual.eq_ignore_ascii_case(spec.sha256) {
        return Err(format!(
            "checksum do modelo {} nao confere (esperado {}, obtido {}) — download corrompido ou mirror adulterado",
            spec.file_name, spec.sha256, actual
        ));
    }
    if downloaded != spec.size_bytes {
        // Should not happen if the sha matched; it is cheap and catches a mirror
        // that returns the right file with padding.
        return Err(format!(
            "tamanho do modelo {} nao confere: esperado {} bytes, obtido {}",
            spec.file_name, spec.size_bytes, downloaded
        ));
    }

    tokio::fs::rename(part_path, final_path)
        .await
        .map_err(|e| format!("rename {} -> {}: {e}", part_path.display(), final_path.display()))?;
    tokio::fs::write(sentinel, &actual)
        .await
        .map_err(|e| format!("escrevendo sentinela {}: {e}", sentinel.display()))?;

    eprintln!(
        "[whisper_model] modelo pronto: {} ({} bytes, sha256 ok)",
        final_path.display(),
        downloaded
    );
    Ok(())
}

fn hex_lower(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        let _ = write!(s, "{:02x}", b);
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_tolerante_cai_no_small() {
        assert_eq!(ModelSize::parse("BASE"), ModelSize::Base);
        assert_eq!(ModelSize::parse(" medium "), ModelSize::Medium);
        assert_eq!(ModelSize::parse(""), ModelSize::Small);
        assert_eq!(ModelSize::parse("large-v3"), ModelSize::Small);
    }

    #[test]
    fn specs_tem_sha256_de_64_hex() {
        for s in [
            ModelSize::Tiny,
            ModelSize::Base,
            ModelSize::Small,
            ModelSize::Medium,
        ] {
            let spec = s.spec();
            assert_eq!(spec.sha256.len(), 64, "{}", spec.file_name);
            assert!(
                spec.sha256.chars().all(|c| c.is_ascii_hexdigit()),
                "{}",
                spec.file_name
            );
            assert!(spec.size_bytes > 1_000_000);
        }
    }

    #[test]
    fn hex_lower_formata_com_zero_a_esquerda() {
        assert_eq!(hex_lower(&[0x00, 0x0f, 0xff]), "000fff");
    }

    #[test]
    fn is_usable_exige_sentinela() {
        let dir = std::env::temp_dir().join(format!("nora-wm-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let model = dir.join("m.bin");
        let sentinel = dir.join("m.bin.sha256");
        std::fs::write(&model, b"1234").unwrap();

        // No sentinel: unusable even with the right size.
        assert!(!is_usable(&model, &sentinel, "abc", 4));
        std::fs::write(&sentinel, "ABC").unwrap();
        assert!(is_usable(&model, &sentinel, "abc", 4));
        // A divergent size invalidates even with a sentinel.
        assert!(!is_usable(&model, &sentinel, "abc", 5));

        let _ = std::fs::remove_dir_all(&dir);
    }
}
