use crate::audio_capture::{AudioCapture, CaptureSinks, RecordingStatus};
use crate::stt::{SttBackend, SttBackendKind};
use crate::SidecarState;
use serde::Deserialize;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Manager, State};

pub type CaptureState = Arc<Mutex<AudioCapture>>;

/// Sobe UM backend de STT pra um track, ja resolvido por config.
///
/// O contrato de eventos pro front e identico nos dois caminhos (evento
/// `transcript` com `TranscriptEvent`); a unica diferenca visivel aqui e que o
/// caminho local nao precisa de token nenhum.
async fn start_stt_backend(
    app_handle: &AppHandle,
    backend: SttBackendKind,
    language: &str,
    track_label: &str,
    azure: Option<&crate::stt::AzureStartParams>,
) -> Result<Box<dyn SttBackend>, String> {
    match backend {
        #[cfg(feature = "stt-local")]
        SttBackendKind::Local => {
            let h = crate::stt_local::LocalSttHandle::start(
                app_handle.clone(),
                language.to_string(),
                track_label.to_string(),
            )
            .await?;
            Ok(Box::new(h))
        }
        #[cfg(not(feature = "stt-local"))]
        SttBackendKind::Local => Err("binario compilado sem a feature stt-local".to_string()),

        #[cfg(feature = "stt-azure")]
        SttBackendKind::Azure => {
            let az = azure.ok_or("backend azure sem credenciais de speech token")?;
            let h = crate::stt_sidecar::SidecarHandle::start(
                app_handle.clone(),
                az.region.clone(),
                az.auth_token.clone(),
                language.to_string(),
                track_label.to_string(),
                az.backend_url.clone(),
                az.access_token.clone(),
            )
            .await?;
            Ok(Box::new(h))
        }
        #[cfg(not(feature = "stt-azure"))]
        SttBackendKind::Azure => {
            let _ = azure;
            Err("binario compilado sem a feature stt-azure".to_string())
        }
    }
}

/// Busca o token do Azure Speech e monta os parametros de start do sidecar.
///
/// So existe quando a feature `stt-azure` esta compilada: no build local-puro
/// (`--no-default-features --features stt-local`) o modulo `crate::speech_token`
/// nem entra no binario, entao referenciar `crate::speech_token::*` de um caminho
/// nao-gated quebraria a compilacao. E tambem o motivo pelo qual o app nao tem
/// como falhar no boot/start tentando falar com `/speech/token`.
#[cfg(feature = "stt-azure")]
async fn fetch_azure_params(
    app_handle: &AppHandle,
    backend_url: &str,
    access_token: &str,
) -> Result<crate::stt::AzureStartParams, String> {
    match crate::speech_token::fetch_speech_token(
        backend_url,
        access_token,
        None, // Use default region from backend
    )
    .await
    {
        Ok(t) => {
            log_line(app_handle, "start_recording: speech token ok");
            Ok(crate::stt::AzureStartParams {
                region: t.region,
                auth_token: t.token,
                backend_url: backend_url.to_string(),
                access_token: access_token.to_string(),
            })
        }
        Err(e) => {
            log_line(
                app_handle,
                &format!("start_recording: speech token ERROR: {}", e),
            );
            Err(format!("Failed to fetch speech token: {}", e))
        }
    }
}

/// Logger de arquivo best-effort pro fluxo de gravacao.
///
/// Em release o binario roda com `windows_subsystem = "windows"`, sem console —
/// entao `eprintln!` some e o usuario fica sem nenhum sinal quando "clica
/// iniciar e nada acontece". Esta funcao append uma linha em
/// `<app_log_dir>/desktop.log` com um timestamp simples.
///
/// REGRA: NUNCA pode fazer o caller falhar. Qualquer erro de IO/resolucao de
/// path e silenciosamente ignorado (a gravacao e mais importante que o log).
fn log_line(app_handle: &AppHandle, msg: &str) {
    use std::io::Write;

    // Timestamp relativo simples: segundos desde o UNIX epoch. Nao precisamos
    // de wall-clock formatado — so de ordem + delta entre linhas pra debug.
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0);

    // Resolve o dir de log; se falhar, desiste em silencio.
    let Ok(dir) = app_handle.path().app_log_dir() else {
        return;
    };
    // Cria o dir best-effort; ignora erro (open abaixo simplesmente falhara).
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("desktop.log");

    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
    {
        // Ignora erro de escrita — best-effort.
        let _ = writeln!(file, "[{:.3}] {}", ts, msg);
    }
}

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

#[tauri::command]
pub async fn start_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
    sidecar_state: State<'_, SidecarState>,
    request: StartRecordingRequest,
) -> Result<RecordingStatus, String> {
    #[cfg(debug_assertions)]
    {
        eprintln!("[commands] start_recording called");
        eprintln!("[commands] device_name: {:?}", request.device_name);
        eprintln!("[commands] language: {:?}", request.language);
        eprintln!("[commands] capture_system_audio: {:?}", request.capture_system_audio);
        eprintln!("[commands] system_audio_device: {:?}", request.system_audio_device);
    }

    log_line(
        &app_handle,
        &format!(
            "start_recording: begin (device={:?}, lang={:?}, system_audio={:?})",
            request.device_name, request.language, request.capture_system_audio
        ),
    );

    let access_token = match crate::auth_bridge::web_session_jwt(&app_handle) {
        Ok(t) => {
            log_line(&app_handle, "start_recording: auth ok");
            t
        }
        Err(e) => {
            log_line(&app_handle, &format!("start_recording: auth ERROR: {}", e));
            return Err(e);
        }
    };
    let backend_url = crate::api_base_url();

    let backend = crate::stt::configured_backend();
    log_line(
        &app_handle,
        &format!("start_recording: backend stt = {}", backend.as_str()),
    );

    // AZURE-ONLY. No backend local nao ha token a buscar: nenhuma chamada a
    // `/speech/token`, nenhum round-trip de rede, e a gravacao nao depende de a
    // API estar de pe. Este `if` e o que impede o app de morrer no start quando
    // o backend esta fora do ar.
    #[cfg(feature = "stt-azure")]
    let azure_params = if backend == SttBackendKind::Azure {
        Some(fetch_azure_params(&app_handle, &backend_url, &access_token).await?)
    } else {
        None
    };

    // Build local-puro: nao ha sequer o tipo de credencial a preencher.
    #[cfg(not(feature = "stt-azure"))]
    let azure_params: Option<crate::stt::AzureStartParams> = {
        // `backend_url`/`access_token` seguem sendo resolvidos acima de proposito:
        // a checagem de sessao continua valendo (o upload da reuniao vai precisar
        // dela), so o round-trip do token e que desaparece.
        let _ = (&backend_url, &access_token);
        None
    };

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

    // Um backend por track. O track `mic` e o usuario local; o track `system` e
    // o audio de loopback (participantes remotos). A atribuicao de falante e POR
    // TRACK — ver crate::stt::SYSTEM_SPEAKER_ID.
    let mic_sidecar = match start_stt_backend(
        &app_handle,
        backend,
        &language,
        "mic",
        azure_params.as_ref(),
    )
    .await
    {
        Ok(s) => {
            log_line(&app_handle, "start_recording: stt mic ok");
            s
        }
        Err(e) => {
            log_line(&app_handle, &format!("start_recording: stt mic ERROR: {}", e));
            return Err(format!("Failed to start mic sidecar: {}", e));
        }
    };

    let system_sidecar = if capture_system {
        match start_stt_backend(
            &app_handle,
            backend,
            &language,
            "system",
            azure_params.as_ref(),
        )
        .await
        {
            Ok(s) => {
                log_line(&app_handle, "start_recording: stt system ok");
                Some(s)
            }
            Err(e) => {
                log_line(
                    &app_handle,
                    &format!("start_recording: stt system ERROR: {}", e),
                );
                return Err(format!("Failed to start system sidecar: {}", e));
            }
        }
    } else {
        None
    };

    // Spawn bridge tasks to feed audio from channels to sidecars
    let mic_bridge = {
        let sidecar = mic_sidecar.audio_tx();
        tokio::spawn(async move {
            while let Some(samples) = mic_rx.recv().await {
                match sidecar.try_send(samples) {
                    Ok(()) => {}
                    // Backpressure: sidecar não consome rápido o bastante. Dropa a
                    // amostra (tempo real > completude) — esperado sob carga.
                    Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {}
                    // Sidecar morreu: encerra a bridge em vez de girar à toa.
                    Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => break,
                }
            }
        })
    };

    let system_bridge = if let Some(ref sidecar) = system_sidecar {
        let tx = sidecar.audio_tx();
        Some(tokio::spawn(async move {
            while let Some(samples) = system_rx.recv().await {
                match tx.try_send(samples) {
                    Ok(()) => {}
                    Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {}
                    Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => break,
                }
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
                log_line(
                    &app_handle,
                    &format!("start_recording: capture start ERROR: {}", e),
                );
                e
            })?;

        status
    };

    log_line(&app_handle, "start_recording: capture start ok");

    #[cfg(debug_assertions)]
    eprintln!(
        "[commands] capture started ok - mic: {}, system: {:?}, sr: {}",
        status.mic_device, status.system_audio_device, status.sample_rate
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
    log_line(
        &app_handle,
        &format!(
            "start_recording: returning ok (mic={}, system={:?}, sr={})",
            status.mic_device, status.system_audio_device, status.sample_rate
        ),
    );
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
        eprintln!("[commands] stopping stt session_id={}", sidecar.session_id());
        // Os dois backends fazem flush antes de encerrar, entao um ultimo evento
        // `transcript` pode chegar depois deste ponto (comportamento identico ao
        // `stop_continuous_recognition` do SDK Azure).
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
    app_handle: AppHandle,
    request: UploadMeetingRequest,
) -> Result<UploadMeetingResponse, String> {
    let access_token = crate::auth_bridge::web_session_jwt(&app_handle)?;

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

    let file_bytes = request.file_content.into_bytes();

    let form = reqwest::multipart::Form::new()
        .part("metadata", reqwest::multipart::Part::bytes(metadata_bytes)
            .mime_str("application/json")
            .map_err(|e| e.to_string())?)
        .part("file", reqwest::multipart::Part::bytes(file_bytes)
            .file_name(request.file_name)
            .mime_str("text/plain")
            .map_err(|e| e.to_string())?);

    let response = crate::http_proxy::http_client()
        .post(format!("{}/meetings", backend_url))
        .header("Authorization", format!("Bearer {}", access_token))
        .multipart(form)
        .timeout(std::time::Duration::from_secs(120))
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

    let meeting_id = json["id"]
        .as_str()
        .ok_or_else(|| format!("Upload: resposta do backend sem 'id': {}", body_text))?
        .to_string();
    Ok(UploadMeetingResponse {
        meeting_id,
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
            "missingDriver": if has_blackhole { serde_json::Value::Null } else { serde_json::json!("blackhole") },
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
