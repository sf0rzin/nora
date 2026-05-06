use azure_speech::recognizer::{self, AudioDevice};
use azure_speech::Auth;
use tauri::{AppHandle, Emitter};
use tokio_stream::StreamExt;

#[derive(Debug, serde::Serialize, Clone)]
pub struct TranscriptEvent {
    pub text: String,
    pub is_final: bool,
    pub speaker: Option<String>,
}

pub struct AzureSpeechClient {
    region: String,
    subscription_key: String,
    language: String,
}

impl AzureSpeechClient {
    pub fn new(region: String, subscription_key: String, language: String) -> Self {
        Self {
            region,
            subscription_key,
            language,
        }
    }

    pub async fn recognize_stream(
        &self,
        app_handle: AppHandle,
        mut audio_rx: tokio::sync::mpsc::Receiver<Vec<f32>>,
        sample_rate: u32,
        channels: u16,
    ) -> Result<(), String> {
        eprintln!("[azure-speech] connecting with region={} lang={}", self.region, self.language);

        let auth = Auth::from_subscription(&self.region, &self.subscription_key);

        let config = recognizer::Config::default()
            .set_language(recognizer::Language::from(self.language.as_str()))
            .set_output_format(recognizer::OutputFormat::Detailed);

        let client = recognizer::Client::connect(auth, config)
            .await
            .map_err(|e| format!("Azure connect error: {:?}", e))?;

        eprintln!("[azure-speech] connected to Azure Speech");

        let (wav_tx, wav_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);

        let target_sr = if sample_rate > 48000 { 16000 } else { sample_rate };

        let wav_header = hound::WavSpec {
            sample_rate: target_sr,
            channels: 1,
            bits_per_sample: 16,
            sample_format: hound::SampleFormat::Int,
        }
        .into_header_for_infinite_file();

        wav_tx.send(wav_header).await.map_err(|e| format!("WAV header send error: {}", e))?;

        let sr = sample_rate;
        let ch = channels;
        let encode_task = tokio::spawn(async move {
            let ratio = target_sr as f64 / sr as f64;

            while let Some(chunk) = audio_rx.recv().await {
                let mono: Vec<f32> = if ch > 1 {
                    chunk.chunks(ch as usize)
                        .map(|frame| frame.iter().sum::<f32>() / ch as f32)
                        .collect()
                } else {
                    chunk
                };

                let resampled = if sr != target_sr {
                    resample_mono(&mono, ratio)
                } else {
                    mono
                };

                let pcm_bytes: Vec<u8> = resampled
                    .iter()
                    .flat_map(|s| {
                        let v = (s * 32767.0).clamp(-32768.0, 32767.0) as i16;
                        v.to_le_bytes()
                    })
                    .collect();

                if wav_tx.send(pcm_bytes).await.is_err() {
                    eprintln!("[azure-speech] wav_tx channel closed");
                    break;
                }
            }
        });

        let audio_stream = tokio_stream::wrappers::ReceiverStream::new(wav_rx);

        let mut events = client
            .recognize(audio_stream, recognizer::AudioFormat::Wav, AudioDevice::stream())
            .await
            .map_err(|e| format!("Azure recognize error: {:?}", e))?;

        eprintln!("[azure-speech] recognition started, waiting for events...");

        while let Some(event) = events.next().await {
            match event {
                Ok(recognizer::Event::Recognized(_request_id, result, _offset, _duration, _raw)) => {
                    eprintln!("[azure-speech] RECOGNIZED: {}", result.text);
                    let evt = TranscriptEvent {
                        text: result.text.clone(),
                        is_final: true,
                        speaker: None,
                    };
                    let _ = app_handle.emit("transcript", &evt);
                }
                Ok(recognizer::Event::Recognizing(_request_id, result, _offset, _duration, _raw)) => {
                    eprintln!("[azure-speech] RECOGNIZING: {}", result.text);
                    let evt = TranscriptEvent {
                        text: result.text.clone(),
                        is_final: false,
                        speaker: None,
                    };
                    let _ = app_handle.emit("transcript", &evt);
                }
                Ok(recognizer::Event::SessionStarted(_request_id)) => {
                    eprintln!("[azure-speech] session started");
                }
                Ok(other) => {
                    eprintln!("[azure-speech] other event: {:?}", other);
                }
                Err(e) => {
                    eprintln!("[azure-speech] stream error: {:?}", e);
                    break;
                }
            }
        }

        encode_task.abort();

        eprintln!("[azure-speech] recognition ended");
        Ok(())
    }
}

fn resample_mono(samples: &[f32], ratio: f64) -> Vec<f32> {
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
