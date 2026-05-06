use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::SampleFormat;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter};

static RECORDING: AtomicBool = AtomicBool::new(false);

#[derive(Debug, serde::Serialize, Clone)]
pub struct AudioChunkPayload {
    pub samples: Vec<f32>,
    pub sample_rate: u32,
    pub channels: u16,
}

#[derive(Debug, serde::Serialize, Clone)]
pub struct RecordingStatus {
    pub is_recording: bool,
    pub device_name: String,
    pub sample_rate: u32,
    pub channels: u16,
}

#[derive(Default)]
pub struct AudioCapture {
    recording: Arc<AtomicBool>,
    audio_sender: Arc<Mutex<Option<tokio::sync::mpsc::Sender<Vec<f32>>>>>,
}

unsafe impl Send for AudioCapture {}
unsafe impl Sync for AudioCapture {}

impl AudioCapture {
    pub fn new() -> Self {
        Self {
            recording: Arc::new(AtomicBool::new(false)),
            audio_sender: Arc::new(Mutex::new(None)),
        }
    }

    pub fn list_devices() -> Result<Vec<String>, String> {
        let host = cpal::default_host();
        let devices = host
            .input_devices()
            .map_err(|e| format!("Failed to list devices: {}", e))?;
        Ok(devices.filter_map(|d| d.name().ok()).collect())
    }

    pub fn start(
        &self,
        app_handle: AppHandle,
        device_name: Option<String>,
        sender: Option<tokio::sync::mpsc::Sender<Vec<f32>>>,
    ) -> Result<RecordingStatus, String> {
        if self.recording.load(Ordering::SeqCst) {
            return Err("Already recording".into());
        }

        if let Some(s) = sender {
            *self.audio_sender.lock().unwrap() = Some(s);
        }

        let host = cpal::default_host();
        let device = match device_name {
            Some(name) => host
                .input_devices()
                .map_err(|e| format!("Device error: {}", e))?
                .find(|d| d.name().map(|n| n == name).unwrap_or(false))
                .ok_or_else(|| format!("Device '{}' not found", name))?,
            None => host
                .default_input_device()
                .ok_or("No default input device available")?,
        };

        let actual_name = device.name().unwrap_or_else(|_| "Unknown".into());

        let supported = device
            .supported_input_configs()
            .map_err(|e| format!("Config error: {}", e))?
            .find(|c| c.sample_format() == SampleFormat::F32)
            .or_else(|| device.supported_input_configs().ok()?.next())
            .ok_or("No supported audio config found")?;

        let config = supported.with_max_sample_rate().config();
        let sample_rate = config.sample_rate.0;
        let channels = config.channels;

        self.recording.store(true, Ordering::SeqCst);
        let flag = self.recording.clone();
        let emit_handle = app_handle.clone();

        let chunk_size = (sample_rate as usize / 10) * channels as usize;
        let buffer: Arc<Mutex<Vec<f32>>> =
            Arc::new(Mutex::new(Vec::with_capacity(chunk_size * 2)));
        let buf = buffer.clone();
        let sender = self.audio_sender.clone();

        let _stream = device
            .build_input_stream(
                &config,
                move |data: &[f32], _: &cpal::InputCallbackInfo| {
                    if !flag.load(Ordering::SeqCst) {
                        return;
                    }
                    if let Ok(mut b) = buf.lock() {
                        b.extend_from_slice(data);
                        if b.len() >= chunk_size {
                            let chunk: Vec<f32> = b.drain(..chunk_size).collect();

                            if let Ok(guard) = sender.lock() {
                                if let Some(tx) = guard.as_ref() {
                                    let _ = tx.try_send(chunk.clone());
                                }
                            }

                            let payload = AudioChunkPayload {
                                samples: chunk,
                                sample_rate,
                                channels,
                            };
                            let _ = emit_handle.emit("audio-chunk", &payload);
                        }
                    }
                },
                |err| eprintln!("Audio stream error: {}", err),
                None,
            )
            .map_err(|e| format!("Stream build error: {}", e))?;

        _stream.play().map_err(|e| format!("Stream play error: {}", e))?;

        let status = RecordingStatus {
            is_recording: true,
            device_name: actual_name,
            sample_rate,
            channels,
        };

        let _ = app_handle.emit("recording-status", &status);

        std::mem::forget(_stream);

        Ok(status)
    }

    pub fn stop(&self, app_handle: AppHandle) -> Result<(), String> {
        self.recording.store(false, Ordering::SeqCst);

        if let Ok(mut guard) = self.audio_sender.lock() {
            *guard = None;
        }

        let status = RecordingStatus {
            is_recording: false,
            device_name: String::new(),
            sample_rate: 0,
            channels: 0,
        };

        let _ = app_handle.emit("recording-status", &status);

        Ok(())
    }
}
