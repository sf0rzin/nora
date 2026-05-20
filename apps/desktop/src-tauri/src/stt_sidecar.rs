use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot, Mutex};
use base64::Engine;

use crate::speech_token::fetch_speech_token;

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
/// Sends `{"type":"refresh_token",...}` to the sidecar via stdin.
/// Retries with exponential backoff on transient failures.
async fn spawn_refresh_loop(
    session_id: String,
    stdin: Arc<Mutex<tokio::process::ChildStdin>>,
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
                            // Atualiza intervalo de refresh baseado no TTL real do token
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
                            let mut stdin_guard = stdin.lock().await;
                            if let Err(e) = stdin_guard.write_all(line.as_bytes()).await {
                                eprintln!("[stt_sidecar] failed to write refresh_token: {}", e);
                                break;
                            }
                            if let Err(e) = stdin_guard.flush().await {
                                eprintln!("[stt_sidecar] failed to flush refresh_token: {}", e);
                                break;
                            }
                            #[cfg(debug_assertions)]
                            eprintln!("[stt_sidecar] token refreshed successfully for session {}", session_id);
                            break; // Success, exit retry loop
                        }
                        Err(e) => {
                            eprintln!("[stt_sidecar] failed to refresh token (retry in {:?}): {}", retry_delay, e);
                            tokio::select! {
                                _ = &mut cancel_rx => {
                                    #[cfg(debug_assertions)]
                                    eprintln!("[stt_sidecar] refresh loop cancelled during retry");
                                    break;
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
        .spawn()
        .map_err(|e| format!("failed to spawn sidecar: {}", e))?;

    let stdin = Arc::new(Mutex::new(child
        .stdin
        .take()
        .ok_or("failed to get stdin")?));
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

    // Send start message
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

    {
        let mut stdin_guard = stdin.lock().await;
        let start_line = format!("{}\n", start_msg);
        stdin_guard
            .write_all(start_line.as_bytes())
            .await
            .map_err(|e| format!("failed to write start: {}", e))?;
        stdin_guard.flush().await.map_err(|e| format!("failed to flush: {}", e))?;
    }

    // Spawn refresh loop
    let (refresh_cancel_tx, refresh_cancel_rx) = oneshot::channel::<()>();
    let refresh_handle = tokio::spawn(spawn_refresh_loop(
        session_id.clone(),
        Arc::clone(&stdin),
        backend_url,
        access_token,
        region.clone(),
        refresh_cancel_rx,
        None, // TTL inicial desconhecido; será calculado no primeiro refresh
    ));

    // Writer task
    let writer_session = session_id.clone();
    let writer_stdin = Arc::clone(&stdin);
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
            let mut stdin_guard = writer_stdin.lock().await;
            if let Err(e) = stdin_guard.write_all(line.as_bytes()).await {
                #[cfg(debug_assertions)]
                eprintln!("[stt_sidecar] write error: {}", e);
                break;
            }
            if let Err(e) = stdin_guard.flush().await {
                #[cfg(debug_assertions)]
                eprintln!("[stt_sidecar] flush error: {}", e);
                break;
            }
        }

        // Send stop message
        let stop_msg = serde_json::json!({
            "v": 1,
            "type": "stop",
            "session_id": writer_session,
        });
        let mut stdin_guard = writer_stdin.lock().await;
        let _ = stdin_guard.write_all(format!("{}\n", stop_msg).as_bytes()).await;
        let _ = stdin_guard.flush().await;
    });

    // Stderr reader
    let stderr_handle = tokio::spawn(async move {
        let reader = BufReader::new(stderr);
        let mut lines = reader.lines();
        while let Ok(Some(line)) = lines.next_line().await {
            eprintln!("[sidecar stderr] {}", line.trim());
        }
    });

    // Stdout reader
    let mut ready_sent = false;
    let mut ready_tx_opt = Some(ready_tx);

    let stdout_reader = BufReader::new(stdout);
    let mut lines = stdout_reader.lines();

    loop {
        tokio::select! {
            line_result = lines.next_line() => {
                match line_result {
                    Ok(Some(line)) => {
                        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
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

    // Cancel refresh loop
    let _ = refresh_cancel_tx.send(());
    refresh_handle.abort();
    
    writer.abort();
    stderr_handle.abort();
    let _ = child.kill().await;
    Ok(())
}
