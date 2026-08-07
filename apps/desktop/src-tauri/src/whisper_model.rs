//! Resolucao, cache e download sob demanda do modelo GGML do whisper.cpp.
//!
//! POR QUE NAO EMBUTIR NO BUNDLE
//! -----------------------------
//! `small` tem 465 MiB e `medium` 1.4 GiB. Somar isso ao .msi/.dmg/.AppImage
//! (a) estoura o limite pratico de artefato do GitHub Release, (b) faz TODA
//! atualizacao — inclusive um bugfix de CSS — rebaixar o modelo inteiro pelo
//! updater, e (c) triplica o tempo de build no CI dos tres alvos. O modelo e
//! baixado UMA vez, no primeiro uso, e fica no app data dir do usuario.
//!
//! LAYOUT NO DISCO
//! ---------------
//!   <app_data_dir>/models/ggml-small.bin          modelo verificado
//!   <app_data_dir>/models/ggml-small.bin.part     download em andamento
//!   <app_data_dir>/models/ggml-small.bin.sha256   sentinela de verificacao
//!
//! O `.part` so vira `.bin` DEPOIS do sha256 bater. Um download interrompido
//! nunca e usado como modelo — na pior hipotese e refeito do zero (nao ha
//! resume por Range: HuggingFace suporta, mas resume sem validar o ETag e como
//! se corrompe arquivo em silencio).

use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::AsyncWriteExt;

/// Evento Tauri de progresso do download. O front ainda nao escuta (nada quebra
/// se ninguem escutar); e o gancho pronto pra barra de progresso na UI.
pub const MODEL_PROGRESS_EVENT: &str = "stt-model-progress";

/// Repositorio oficial dos GGML do whisper.cpp. Trocavel por mirror interno via
/// `NORA_WHISPER_MODEL_BASE_URL` (rede corporativa que bloqueia HF).
const DEFAULT_BASE_URL: &str = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelSize {
    /// 74 MiB. So pra smoke test / CI — qualidade em pt-BR e ruim.
    Tiny,
    /// 141 MiB. Aceitavel em pt-BR, roda folgado em CPU fraca.
    Base,
    /// 465 MiB. DEFAULT: melhor troca qualidade/latencia em pt-BR num CPU comum.
    Small,
    /// 1.4 GiB. Melhor qualidade, exige CPU forte ou GPU pra tempo real.
    Medium,
}

/// Metadados de um modelo. `sha256` e `size_bytes` vieram da API de
/// paths-info do HuggingFace (`lfs.oid` = sha256 do blob), nao de memoria.
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

    /// Parse tolerante do valor de config. Desconhecido -> `Small` com aviso.
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

    /// Bytes que o download vai custar. Usado pelo evento de progresso e util
    /// pra um prompt de confirmacao antes de baixar 1.5 GiB no `medium`.
    #[allow(dead_code)] // sem consumidor ainda: a UI de progresso nao existe.
    pub fn download_bytes(self) -> u64 {
        self.spec().size_bytes
    }
}

/// Tamanho configurado, memoizado. Mesma cadeia de prioridade do backend:
/// env runtime -> env build-time -> `plugins.nora.whisperModel` -> `small`.
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
    /// 0.0 a 100.0. Fica em 0 quando o total e desconhecido.
    pub percent: f32,
    pub message: Option<String>,
}

fn emit(app: &AppHandle, p: ModelProgress) {
    // Best-effort: janela fechada / front nao montado nao pode derrubar o download.
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

/// Serializa o download entre os tracks. `mic` e `system` sobem juntos no
/// `start_recording`; sem isto os dois baixariam 465 MiB em paralelo pro mesmo
/// `.part`. O lock cobre a checagem TAMBEM, nao so o download (check-then-act).
fn download_lock() -> &'static tokio::sync::Mutex<()> {
    static LOCK: std::sync::OnceLock<tokio::sync::Mutex<()>> = std::sync::OnceLock::new();
    LOCK.get_or_init(|| tokio::sync::Mutex::new(()))
}

/// Garante que o modelo esta em disco e verificado; devolve o caminho.
///
/// Idempotente e seguro pra chamar de varios tracks ao mesmo tempo. Na segunda
/// chamada em diante o custo e um `metadata()` + leitura da sentinela.
pub async fn ensure_model(app: &AppHandle, size: ModelSize) -> Result<PathBuf, String> {
    // Escape hatch: modelo proprio (quantizado q5_1, fine-tune em pt-BR, mirror
    // offline). Pula download E checksum de proposito — quem aponta pra um
    // arquivo local assume a responsabilidade por ele.
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

    // Arquivo existe mas nao passou na checagem barata: apaga em vez de tentar
    // consertar. Um GGML truncado faz o whisper.cpp abortar dentro do C++, o que
    // derruba o processo inteiro em vez de virar Result::Err.
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

/// Checagem barata do cache: arquivo com o tamanho exato + sentinela com o hash
/// esperado. Re-hashear 465 MiB a cada `start_recording` custaria ~0.5 s de
/// latencia pra confirmar algo que ja foi confirmado; a sentinela e escrita
/// SOMENTE apos um sha256 completo bater.
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

    // Cliente proprio: o `http_proxy::http_client()` tem timeout TOTAL de 30 s,
    // que mata um download de 465 MiB. Aqui so limitamos o connect; o corpo pode
    // demorar o quanto a rede do usuario precisar.
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
    // Throttle do evento: um emit por chunk de 8 KiB inundaria a IPC do webview
    // com ~60k mensagens. Emite a cada ~1 MiB.
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
    // fsync antes do rename: sem isto um crash/queda de energia logo apos o
    // rename deixa um .bin com a sentinela valida e conteudo incompleto.
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
        // Nao deveria acontecer se o sha bateu; e barato e pega mirror que
        // devolve o arquivo certo com padding.
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

        // Sem sentinela: inutilizavel mesmo com tamanho certo.
        assert!(!is_usable(&model, &sentinel, "abc", 4));
        std::fs::write(&sentinel, "ABC").unwrap();
        assert!(is_usable(&model, &sentinel, "abc", 4));
        // Tamanho divergente invalida mesmo com sentinela.
        assert!(!is_usable(&model, &sentinel, "abc", 5));

        let _ = std::fs::remove_dir_all(&dir);
    }
}
