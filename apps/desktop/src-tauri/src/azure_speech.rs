use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::sync::Arc;
use tauri::{AppHandle, Emitter};
use tokio_tungstenite::tungstenite::{client::IntoClientRequest, Message};

#[derive(Debug, serde::Serialize, Clone)]
pub struct TranscriptEvent {
    pub text: String,
    pub is_final: bool,
    pub offset_ms: u64,
    pub duration_ms: u64,
    pub speaker: Option<String>,
}

#[derive(Debug, Deserialize)]
struct SpeechRecognitionResult {
    #[serde(rename = "RecognitionStatus")]
    status: String,
    #[serde(rename = "NBest")]
    n_best: Option<Vec<NBestItem>>,
}

#[derive(Debug, Deserialize)]
struct NBestItem {
    #[serde(rename = "Confidence")]
    confidence: f64,
    #[serde(rename = "Display")]
    display: String,
    #[serde(rename = "Words")]
    words: Option<Vec<WordItem>>,
}

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
struct WordItem {
    #[serde(rename = "Word")]
    word: String,
    #[serde(rename = "Speaker")]
    speaker: Option<i32>,
}

pub struct AzureSpeechClient {
    subscription_key: String,
    endpoint: String,
    language: String,
}

impl AzureSpeechClient {
    pub fn new(subscription_key: String, endpoint: String, language: String) -> Self {
        let endpoint = endpoint.trim_end_matches('/').to_string();
        Self {
            subscription_key,
            endpoint,
            language,
        }
    }

    fn build_ws_url(&self) -> Result<String, String> {
        let connection_id = uuid::Uuid::new_v4().to_string();

        let ws_url = self.endpoint.replace("https://", "wss://");

        Ok(format!(
            "{}/speech/recognition/conversation/cognitiveservices/v1?language={}&format=detailed&X-ConnectionId={}",
            ws_url, self.language, connection_id
        ))
    }

    fn build_config_message(&self) -> String {
        serde_json::json!({
            "config": {
                "context": {
                    "system": { "version": "4.0.0" },
                    "os": { "platform": "Desktop", "name": "NORA Desktop" }
                }
            }
        })
        .to_string()
    }

    pub async fn recognize_stream(
        &self,
        app_handle: AppHandle,
        mut audio_rx: tokio::sync::mpsc::Receiver<Vec<f32>>,
        sample_rate: u32,
    ) -> Result<(), String> {
        let ws_url = self.build_ws_url()?;
        eprintln!("[azure-speech] connecting to: {}", ws_url);

        let mut request = IntoClientRequest::into_client_request(&ws_url)
            .map_err(|e| format!("WS request error: {}", e))?;

        let headers = request.headers_mut();
        headers.insert(
            "Ocp-Apim-Subscription-Key",
            self.subscription_key.parse().map_err(|e: http::header::InvalidHeaderValue| format!("Invalid key: {}", e))?,
        );

        let (mut ws_stream, _) = tokio_tungstenite::connect_async(request)
            .await
            .map_err(|e| format!("WS connect error: {}", e))?;

        eprintln!("[azure-speech] connected, sending config");

        let config_msg = self.build_config_message();
        ws_stream
            .send(Message::Text(config_msg))
            .await
            .map_err(|e| format!("WS send config error: {}", e))?;

        let emit_handle = app_handle.clone();

        let (ws_sink, ws_stream_rx) = ws_stream.split();
        let ws_tx = Arc::new(tokio::sync::Mutex::new(ws_sink));

        let sender = ws_tx.clone();
        let resample_ratio = 16000.0 / sample_rate as f64;
        let send_task = tokio::spawn(async move {
            while let Some(chunk) = audio_rx.recv().await {
                let resampled = if (resample_ratio - 1.0).abs() > 0.01 {
                    Self::resample(&chunk, resample_ratio)
                } else {
                    chunk
                };

                let pcm_bytes: Vec<u8> = resampled
                    .iter()
                    .flat_map(|s| {
                        let v = (s * 32767.0).clamp(-32768.0, 32767.0) as i16;
                        v.to_le_bytes()
                    })
                    .collect();

                let mut tx = sender.lock().await;
                if tx.send(Message::Binary(pcm_bytes)).await.is_err() {
                    break;
                }
            }

            let mut tx = sender.lock().await;
            let _ = tx.send(Message::Binary(vec![])).await;
        });

        let receive_task = tokio::spawn(async move {
            let mut stream = Box::pin(ws_stream_rx);
            while let Some(msg) = stream.next().await {
                match msg {
                    Ok(Message::Text(text)) => {
                        if let Ok(result) = serde_json::from_str::<SpeechRecognitionResult>(&text) {
                            let is_final = result.status == "Success"
                                || result.status == "EndOfDictation";

                            if let Some(n_best) = result.n_best {
                                if let Some(best) = n_best.first() {
                                    let speaker = best.words.as_ref().and_then(|words| {
                                        words
                                            .first()
                                            .and_then(|w| w.speaker.map(|s| format!("Speaker {}", s)))
                                    });

                                    let event = TranscriptEvent {
                                        text: best.display.clone(),
                                        is_final,
                                        offset_ms: 0,
                                        duration_ms: 0,
                                        speaker,
                                    };

                                    let _ = emit_handle.emit("transcript", &event);
                                }
                            }
                        }
                    }
                    Ok(Message::Close(_)) => break,
                    Err(e) => {
                        eprintln!("[azure-speech] WS receive error: {}", e);
                        break;
                    }
                    _ => {}
                }
            }
        });

        let _ = send_task.await;
        let _ = receive_task.await;

        Ok(())
    }

    fn resample(samples: &[f32], ratio: f64) -> Vec<f32> {
        let new_len = (samples.len() as f64 * ratio) as usize;
        let mut result = Vec::with_capacity(new_len);
        for i in 0..new_len {
            let src_idx = i as f64 / ratio;
            let idx = src_idx as usize;
            if idx + 1 < samples.len() {
                let frac = src_idx - idx as f64;
                result.push(samples[idx] * (1.0 - frac) as f32 + samples[idx + 1] * frac as f32);
            } else if idx < samples.len() {
                result.push(samples[idx]);
            }
        }
        result
    }
}
