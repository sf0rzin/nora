use crate::audio_capture::{AudioCapture, CaptureSinks, RecordingStatus};
use crate::secrets::SecretStore;
use crate::speech_token::fetch_speech_token;
use crate::stt_sidecar::SidecarHandle;
use crate::SidecarState;
use serde::Deserialize;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, State};

pub type CaptureState = Arc<Mutex<AudioCapture>>;

#[tauri::command]
pub fn list_audio_devices() -> Result<Vec<String>, String> {
    AudioCapture::list_devices()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartRecordingRequest {
    pub device_name: Option<String>,
    pub language: Option<String>,
    pub capture_system_audio: Option<bool>,
    pub system_audio_device: Option<String>,
}

/// Redaciona nomes de dispositivos de áudio antes de logar: alguns sistemas
/// codificam o nome do usuário (ex: "USB Headset of John Doe"), que é PII.
/// Mantemos o prefixo até o primeiro " of ", " de " ou os 8 primeiros chars.
fn redact_device_name(name: &Option<String>) -> String {
    match name {
        None => "<default>".to_string(),
        Some(s) => {
            let lower = s.to_ascii_lowercase();
            for sep in [" of ", " de ", "(", "-"] {
                if let Some(idx) = lower.find(sep) {
                    return format!("{}***", &s[..idx]);
                }
            }
            let cutoff = s.char_indices().nth(8).map(|(i, _)| i).unwrap_or(s.len());
            format!("{}***", &s[..cutoff])
        }
    }
}

#[tauri::command]
pub async fn start_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
    sidecar_state: State<'_, SidecarState>,
    secrets: State<'_, SecretStore>,
    request: StartRecordingRequest,
) -> Result<RecordingStatus, String> {
    #[cfg(debug_assertions)]
    {
        eprintln!("[commands] start_recording called");
        eprintln!("[commands] device_name: {}", redact_device_name(&request.device_name));
        eprintln!("[commands] language: {:?}", request.language);
        eprintln!("[commands] capture_system_audio: {:?}", request.capture_system_audio);
        eprintln!(
            "[commands] system_audio_device: {}",
            redact_device_name(&request.system_audio_device)
        );
    }

    let access_token = secrets
        .get("access-token")
        .map_err(|e| format!("Failed to get access token: {}", e))?
        .ok_or("Not authenticated. Please login first.")?;
    let backend_url = crate::api_base_url();
    
    let speech_token = fetch_speech_token(
        &backend_url,
        &access_token,
        None, // Use default region from backend
    )
    .await
    .map_err(|e| format!("Failed to fetch speech token: {}", e))?;
    
    let language = request.language.unwrap_or_else(|| "pt-BR".to_string());
    let capture_system = request.capture_system_audio.unwrap_or(false);

    // Create channels for mic and system audio
    let (mic_tx, mut mic_rx) = tokio::sync::mpsc::channel::<Vec<i16>>(100);
    let (system_tx, mut system_rx) = tokio::sync::mpsc::channel::<Vec<i16>>(100);

    let sinks = CaptureSinks {
        mic_tx,
        system_tx: if capture_system {
            Some(system_tx)
        } else {
            None
        },
    };

    // Start sidecars with auth token
    let mic_sidecar = SidecarHandle::start(
        app_handle.clone(),
        speech_token.region.clone(),
        speech_token.token.clone(),
        language.clone(),
        "mic".to_string(),
        backend_url.clone(),
        access_token.clone(),
    )
    .await
    .map_err(|e| format!("Failed to start mic sidecar: {}", e))?;

    let system_sidecar = if capture_system {
        Some(
            SidecarHandle::start(
                app_handle.clone(),
                speech_token.region,
                speech_token.token,
                language,
                "system".to_string(),
                backend_url,
                access_token,
            )
            .await
            .map_err(|e| format!("Failed to start system sidecar: {}", e))?,
        )
    } else {
        None
    };

    // Spawn bridge tasks to feed audio from channels to sidecars
    let mic_bridge = {
        let sidecar = mic_sidecar.audio_tx.clone();
        tokio::spawn(async move {
            while let Some(samples) = mic_rx.recv().await {
                let _ = sidecar.try_send(samples);
            }
        })
    };

    let system_bridge = if let Some(ref sidecar) = system_sidecar {
        let tx = sidecar.audio_tx.clone();
        Some(tokio::spawn(async move {
            while let Some(samples) = system_rx.recv().await {
                let _ = tx.try_send(samples);
            }
        }))
    } else {
        None
    };

    let status = {
        let capture = state.lock().map_err(|e| {
            #[cfg(debug_assertions)]
            eprintln!("[commands] failed to lock capture state: {}", e);
            e.to_string()
        })?;

        #[cfg(debug_assertions)]
        eprintln!("[commands] calling capture.start()...");

        let status = capture
            .start(
                app_handle.clone(),
                request.device_name.clone(),
                capture_system,
                request.system_audio_device.clone(),
                sinks,
            )
            .map_err(|e| {
                #[cfg(debug_assertions)]
                eprintln!("[commands] capture.start FAILED: {}", e);
                e
            })?;

        status
    };

    #[cfg(debug_assertions)]
    eprintln!(
        "[commands] capture started ok - mic: {}, system: {}, sr: {}",
        redact_device_name(&Some(status.mic_device.clone())),
        redact_device_name(&status.system_audio_device),
        status.sample_rate
    );

    // Store sidecars in app state so they stay alive
    {
        let mut sidecars = sidecar_state.lock().map_err(|e| e.to_string())?;
        sidecars.push(mic_sidecar);
        if let Some(s) = system_sidecar {
            sidecars.push(s);
        }
    }

    // Bridge tasks ja foram spawnadas acima (mic_bridge / system_bridge sao
    // JoinHandle de tokio::spawn). NAO chamamos `tokio::spawn(mic_bridge)` de
    // novo — isso criaria uma segunda task que apenas faz await do JoinHandle
    // e termina assim que o bridge termina, sem beneficio funcional.
    // Mantemos os handles em escopo soltando-os via `let _ =` pra indicar
    // intenco de fire-and-forget.
    let _ = mic_bridge;
    if let Some(bridge) = system_bridge {
        let _ = bridge;
    }

    #[cfg(debug_assertions)]
    eprintln!("[commands] start_recording returning ok");
    Ok(status)
}

#[tauri::command]
pub fn stop_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
    sidecar_state: State<'_, SidecarState>,
) -> Result<(), String> {
    #[cfg(debug_assertions)]
    eprintln!("[commands] stop_recording called");

    // Stop audio capture first
    let capture = state.lock().map_err(|e| e.to_string())?;
    capture.stop(app_handle)?;

    // Stop and clear all sidecars to prevent zombie processes
    let mut sidecars = sidecar_state.lock().map_err(|e| e.to_string())?;
    for sidecar in sidecars.drain(..) {
        #[cfg(debug_assertions)]
        eprintln!("[commands] stopping sidecar session_id={}", sidecar.session_id);
        sidecar.stop();
    }

    Ok(())
}

#[tauri::command]
pub fn get_recording_status(
    state: State<'_, CaptureState>,
) -> Result<RecordingStatus, String> {
    let capture = state.lock().map_err(|e| e.to_string())?;
    Ok(capture.get_status())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadMeetingRequest {
    pub title: String,
    pub started_at: String,
    pub ended_at: Option<String>,
    pub language: Option<String>,
    pub transcript_format: String,
    pub tags: Option<Vec<String>>,
    pub participants: Option<Vec<UploadParticipant>>,
    pub file_content: String,
    pub file_name: String,
    /// Idempotency key emitida pelo cliente (UUID v4). Propagada como
    /// `Idempotency-Key` HTTP header — backend pode ignorar (best-effort
    /// dedup) sem efeitos colaterais. Ver `apps/desktop/src/lib/meetings.ts`.
    pub client_id: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadParticipant {
    pub display_name: String,
    pub email: Option<String>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadMeetingResponse {
    pub meeting_id: String,
    pub processing_status: String,
}

#[tauri::command]
pub async fn upload_meeting(
    secrets: State<'_, SecretStore>,
    request: UploadMeetingRequest,
) -> Result<UploadMeetingResponse, String> {
    let access_token = secrets
        .get("access-token")
        .map_err(|e| format!("Failed to get access token: {}", e))?
        .ok_or("Not authenticated. Please login first.")?;

    let backend_url = crate::api_base_url();

    let metadata = serde_json::json!({
        "title": request.title,
        "startedAt": request.started_at,
        "endedAt": request.ended_at,
        "language": request.language.unwrap_or_else(|| "pt-BR".to_string()),
        "transcriptFormat": request.transcript_format,
        "tags": request.tags.unwrap_or_default(),
        "participants": request.participants.unwrap_or_default().iter().map(|p| {
            serde_json::json!({
                "displayName": p.display_name,
                "email": p.email,
            })
        }).collect::<Vec<_>>(),
    });

    let metadata_bytes = serde_json::to_vec(&metadata)
        .map_err(|e| format!("Failed to serialize metadata: {}", e))?;

    // Limite de 25 MiB no transcript: protege a memória do desktop e do backend
    // (50 MB de texto cabem em 6h de reunião razoável a 4kbps).
    const MAX_TRANSCRIPT_BYTES: usize = 25 * 1024 * 1024;
    if request.file_content.len() > MAX_TRANSCRIPT_BYTES {
        return Err(format!(
            "Transcript acima do limite ({} bytes; max {})",
            request.file_content.len(),
            MAX_TRANSCRIPT_BYTES
        ));
    }
    let file_bytes = request.file_content.into_bytes();

    // file_name é controlado pelo frontend; sanitize para evitar CRLF
    // header injection no `file_name=` do Content-Disposition.
    let safe_file_name: String = request
        .file_name
        .chars()
        .filter(|c| !matches!(c, '\r' | '\n' | '\0' | '"'))
        .collect();

    let form = reqwest::multipart::Form::new()
        .part("metadata", reqwest::multipart::Part::bytes(metadata_bytes)
            .mime_str("application/json")
            .map_err(|e| e.to_string())?)
        .part("file", reqwest::multipart::Part::bytes(file_bytes)
            .file_name(safe_file_name)
            .mime_str("text/plain")
            .map_err(|e| e.to_string())?);

    let client = crate::http_proxy::http_client();
    let mut req = client
        .post(format!("{}/meetings", backend_url))
        .header("Authorization", format!("Bearer {}", access_token));
    if let Some(ref cid) = request.client_id {
        // Sanitize: idempotency keys são UUIDs; rejeita controle/CRLF.
        let safe = cid
            .chars()
            .filter(|c| c.is_ascii_graphic())
            .collect::<String>();
        if !safe.is_empty() {
            req = req.header("Idempotency-Key", safe);
        }
    }
    let response = req
        .multipart(form)
        .send()
        .await
        .map_err(|e| format!("Upload request failed: {}", e))?;

    let status = response.status();
    let body_text = response.text().await
        .map_err(|e| format!("Failed to read response body: {}", e))?;

    if !status.is_success() {
        return Err(format!("Upload failed ({}): {}", status, body_text));
    }

    let json: serde_json::Value = serde_json::from_str(&body_text)
        .map_err(|e| format!("Failed to parse response: {} — body: {}", e, body_text))?;

    Ok(UploadMeetingResponse {
        meeting_id: json["id"].as_str().unwrap_or("").to_string(),
        processing_status: json["processingStatus"].as_str().unwrap_or("PENDING").to_string(),
    })
}

#[tauri::command]
pub fn check_system_audio_prerequisites() -> Result<serde_json::Value, String> {
    #[cfg(target_os = "macos")]
    {
        let has_blackhole = crate::system_audio::is_blackhole_installed();
        let supports_sck = false; // TODO: Issue #15 — ScreenCaptureKit
        
        Ok(serde_json::json!({
            "platform": "macos",
            "available": has_blackhole,
            "missingDriver": if has_blackhole { null } else { "blackhole" },
            "supportsScreenCaptureKit": supports_sck,
            "message": if has_blackhole {
                "Driver virtual detectado"
            } else {
                "BlackHole não instalado. Instale para capturar áudio do sistema."
            }
        }))
    }
    
    #[cfg(target_os = "linux")]
    {
        Ok(serde_json::json!({
            "platform": "linux",
            "available": true,
            "missingDriver": null,
            "supportsScreenCaptureKit": false,
            "message": "Linux usa PulseAudio nativamente"
        }))
    }
    
    #[cfg(target_os = "windows")]
    {
        Ok(serde_json::json!({
            "platform": "windows",
            "available": true,
            "missingDriver": null,
            "supportsScreenCaptureKit": false,
            "message": "Windows usa WASAPI loopback nativamente"
        }))
    }
}
