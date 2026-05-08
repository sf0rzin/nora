use tauri::{AppHandle, Emitter};
use tokio::sync::{mpsc, oneshot};
use tauri_plugin_shell::ShellExt;
use base64::Engine;

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
        eprintln!("[SidecarHandle] DROPPED session_id={}", self.session_id);
    }
}

#[allow(dead_code)]
impl SidecarHandle {
    pub async fn start(
        app: AppHandle,
        region: String,
        auth_token: String,
        language: String,
        track_label: String,
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
) -> Result<(), String> {
    let sidecar = app
        .shell()
        .sidecar("nora-stt-sidecar")
        .map_err(|e| format!("sidecar lookup: {}", e))?;

    let (mut rx, mut child) = sidecar
        .spawn()
        .map_err(|e| format!("sidecar spawn: {}", e))?;

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

    let start_line = format!("{}\n", start_msg);
    child
        .write(start_line.as_bytes())
        .map_err(|e| format!("failed to write start: {}", e))?;

    // Writer task
    let writer_session = session_id.clone();
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
            if let Err(e) = child.write(line.as_bytes()) {
                #[cfg(debug_assertions)]
                eprintln!("[stt_sidecar] write error: {}", e);
                break;
            }
        }

        // Send stop message
        let stop_msg = serde_json::json!({
            "v": 1,
            "type": "stop",
            "session_id": writer_session,
        });
        let _ = child.write(format!("{}\n", stop_msg).as_bytes());
    });

    // Reader task
    let mut buf = Vec::new();
    let mut ready_sent = false;
    let mut ready_tx_opt = Some(ready_tx);

    loop {
        tokio::select! {
            event = rx.recv() => {
                match event {
                    Some(tauri_plugin_shell::process::CommandEvent::Stdout(chunk)) => {
                        buf.extend_from_slice(&chunk);

                        // Process complete lines
                        while let Some(pos) = buf.iter().position(|&b| b == b'\n') {
                            let line = buf.drain(..=pos).collect::<Vec<u8>>();
                            let line_str = String::from_utf8_lossy(&line);

                            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line_str) {
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
                    }
                    Some(tauri_plugin_shell::process::CommandEvent::Stderr(chunk)) => {
                        let msg = String::from_utf8_lossy(&chunk);
                        eprintln!("[sidecar stderr] {}", msg.trim());
                    }
                    Some(tauri_plugin_shell::process::CommandEvent::Terminated(payload)) => {
                        eprintln!("[stt_sidecar] terminated: {:?}", payload);
                        let _ = app.emit("stt-error", serde_json::json!({
                            "session_id": session_id,
                            "code": "SIDECAR_DIED",
                            "message": "Sidecar process terminated unexpectedly",
                        }));
                        break;
                    }
                    Some(_) => {}
                    None => break,
                }
            }
            _ = &mut stop_rx => {
                eprintln!("[stt_sidecar] stop signal received");
                break;
            }
        }
    }

    writer.abort();
    Ok(())
}
