use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager, State};

use crate::secrets::SecretStore;
use crate::stealth_mode::StealthModeState;

#[cfg(target_os = "windows")]
use crate::stealth_mode::set_stealth_for_window;

pub type LiveHighlightsState = Arc<Mutex<Option<LiveHighlights>>>;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveHighlightItem {
    pub text: String,
    pub confidence: f64,
    pub source_quote: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveTaskItem {
    pub title: String,
    pub assignee: Option<String>,
    pub priority: String,
    pub source_quote: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveHighlights {
    pub decisions: Vec<LiveHighlightItem>,
    pub next_steps: Vec<LiveHighlightItem>,
    pub observations: Vec<LiveHighlightItem>,
    pub tasks: Vec<LiveTaskItem>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalyzeLiveRequest {
    pub transcript_chunk: String,
    pub chunk_seq: u32,
    pub previous_highlights: Option<LiveHighlights>,
    pub language: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalyzeLiveResponse {
    pub chunk_seq: u32,
    pub highlights: LiveHighlights,
    pub processing_millis: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveAnalysisTelemetry {
    pub chunk_seq: u32,
    pub latency_ms: u64,
    pub success: bool,
    pub items_count: usize,
}

#[tauri::command]
pub async fn analyze_live(
    app_handle: AppHandle,
    state: State<'_, LiveHighlightsState>,
    secrets: State<'_, SecretStore>,
    request: AnalyzeLiveRequest,
) -> Result<(), String> {
    let access_token = secrets
        .get("access-token")
        .map_err(|e| format!("Failed to get access token: {}", e))?
        .ok_or("Not authenticated. Please login first.")?;

    let backend_url = crate::api_base_url();

    let mut body = serde_json::json!({
        "transcriptChunk": request.transcript_chunk,
        "language": request.language.unwrap_or_else(|| "pt-BR".to_string()),
    });

    if let Some(prev) = &request.previous_highlights {
        match serde_json::to_value(prev) {
            Ok(value) => {
                body.as_object_mut()
                    .map(|o| o.insert("previousHighlights".to_string(), value));
            }
            Err(e) => {
                eprintln!("[live_analysis] failed to serialize previousHighlights: {}", e);
            }
        }
    }

    let start = std::time::Instant::now();

    // Reusa o cliente global (connection pool + timeouts default).
    // O override de timeout aqui (15s) ainda vence o default de 30s.
    let client = crate::http_proxy::http_client();
    let result = client
        .post(format!("{}/meetings/live-analyze", backend_url))
        .header("Authorization", format!("Bearer {}", access_token))
        .header("Content-Type", "application/json")
        .json(&body)
        .timeout(std::time::Duration::from_secs(15))
        .send()
        .await;

    let latency_ms = start.elapsed().as_millis() as u64;

    match result {
        Ok(response) => {
            let status = response.status();
            if !status.is_success() {
                let body_text = response.text().await.unwrap_or_default();
                eprintln!("[live_analysis] worker returned {}: {}", status, body_text);

                let _ = app_handle.emit(
                    "live-analysis-telemetry",
                    &LiveAnalysisTelemetry {
                        chunk_seq: request.chunk_seq,
                        latency_ms,
                        success: false,
                        items_count: 0,
                    },
                );
                return Err(format!("Worker error ({}): {}", status, body_text));
            }

            let resp_body: serde_json::Value = response
                .json()
                .await
                .map_err(|e| format!("Failed to parse response: {}", e))?;

            let highlights = parse_highlights_from_response(&resp_body);

            let items_count = highlights.decisions.len()
                + highlights.next_steps.len()
                + highlights.observations.len()
                + highlights.tasks.len();

            {
                let mut state = state.lock().map_err(|e| e.to_string())?;
                *state = Some(highlights.clone());
            }

            let _ = app_handle.emit(
                "live-analysis",
                &AnalyzeLiveResponse {
                    chunk_seq: request.chunk_seq,
                    highlights: highlights.clone(),
                    processing_millis: latency_ms,
                },
            );

            let _ = app_handle.emit(
                "live-analysis-telemetry",
                &LiveAnalysisTelemetry {
                    chunk_seq: request.chunk_seq,
                    latency_ms,
                    success: true,
                    items_count,
                },
            );

            Ok(())
        }
        Err(e) => {
            eprintln!("[live_analysis] request failed: {}", e);

            let _ = app_handle.emit(
                "live-analysis-telemetry",
                &LiveAnalysisTelemetry {
                    chunk_seq: request.chunk_seq,
                    latency_ms,
                    success: false,
                    items_count: 0,
                },
            );

            Err(format!("Request failed: {}", e))
        }
    }
}

fn parse_highlights_from_response(value: &serde_json::Value) -> LiveHighlights {
    fn parse_items(val: &serde_json::Value) -> Vec<LiveHighlightItem> {
        val.as_array()
            .map(|arr| {
                arr.iter()
                    .filter_map(|item| {
                        Some(LiveHighlightItem {
                            text: item.get("text")?.as_str()?.to_string(),
                            confidence: item.get("confidence")?.as_f64().unwrap_or(0.0),
                            source_quote: item.get("sourceQuote")?.as_str()?.to_string(),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    fn parse_tasks(val: &serde_json::Value) -> Vec<LiveTaskItem> {
        val.as_array()
            .map(|arr| {
                arr.iter()
                    .filter_map(|item| {
                        Some(LiveTaskItem {
                            title: item.get("title")?.as_str()?.to_string(),
                            assignee: item.get("assignee").and_then(|v| v.as_str()).map(String::from),
                            priority: item.get("priority")?.as_str()?.to_string(),
                            source_quote: item.get("sourceQuote")?.as_str()?.to_string(),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    LiveHighlights {
        decisions: parse_items(value.get("decisions").unwrap_or(&serde_json::Value::Null)),
        next_steps: parse_items(value.get("nextSteps").unwrap_or(&serde_json::Value::Null)),
        observations: parse_items(value.get("observations").unwrap_or(&serde_json::Value::Null)),
        tasks: parse_tasks(value.get("tasks").unwrap_or(&serde_json::Value::Null)),
    }
}

#[tauri::command]
#[allow(unused_variables)]
pub fn toggle_overlay(
    app_handle: AppHandle,
    state: State<'_, StealthModeState>,
    show: bool,
) -> Result<(), String> {
    if let Some(window) = app_handle.get_webview_window("overlay") {
        if show {
            let _ = window.show();
            let _ = window.set_focus();

            // Se stealth mode estiver ativo, aplicar na overlay agora que ela ficou visível
            #[cfg(target_os = "windows")]
            {
                let stealth_enabled = state.lock().map_err(|e| e.to_string())?;
                if *stealth_enabled {
                    #[cfg(debug_assertions)]
                    eprintln!("[toggle_overlay] applying stealth to newly visible overlay");
                    let _ = set_stealth_for_window(&window, true);
                }
            }
        } else {
            let _ = window.hide();
        }
        Ok(())
    } else {
        Err("Overlay window not found".to_string())
    }
}

#[tauri::command]
pub fn get_live_highlights_snapshot(
    state: State<'_, LiveHighlightsState>,
) -> Result<Option<LiveHighlights>, String> {
    let snapshot = state.lock().map_err(|e| e.to_string())?;
    Ok(snapshot.clone())
}

#[tauri::command]
pub fn clear_live_highlights(
    app_handle: AppHandle,
    state: State<'_, LiveHighlightsState>,
) -> Result<(), String> {
    {
        let mut s = state.lock().map_err(|e| e.to_string())?;
        *s = None;
    }
    let _ = app_handle.emit("clear-highlights", ());
    Ok(())
}

#[tauri::command]
pub fn get_overlay_position(app_handle: AppHandle) -> Result<(i32, i32), String> {
    if let Some(window) = app_handle.get_webview_window("overlay") {
        let pos = window.outer_position().map_err(|e| e.to_string())?;
        Ok((pos.x, pos.y))
    } else {
        Err("Overlay window not found".to_string())
    }
}

#[tauri::command]
pub fn set_overlay_position(app_handle: AppHandle, x: i32, y: i32) -> Result<(), String> {
    if let Some(window) = app_handle.get_webview_window("overlay") {
        window
            .set_position(tauri::Position::Physical(tauri::PhysicalPosition { x, y }))
            .map_err(|e| e.to_string())
    } else {
        Err("Overlay window not found".to_string())
    }
}
