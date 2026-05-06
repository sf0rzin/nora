use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::SampleFormat;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter};

use crate::system_audio;

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

pub struct AudioCapture {
    recording: Arc<AtomicBool>,
    audio_sender: Arc<Mutex<Option<tokio::sync::mpsc::Sender<Vec<f32>>>>>,
    system_audio_buffer: Arc<Mutex<Vec<f32>>>,
    system_audio_capture: Arc<Mutex<Option<system_audio::SystemAudioCapture>>>,
}

unsafe impl Send for AudioCapture {}
unsafe impl Sync for AudioCapture {}

impl AudioCapture {
    pub fn new() -> Self {
        Self {
            recording: Arc::new(AtomicBool::new(false)),
            audio_sender: Arc::new(Mutex::new(None)),
            system_audio_buffer: Arc::new(Mutex::new(Vec::new())),
            system_audio_capture: Arc::new(Mutex::new(None)),
        }
    }

    pub fn list_devices() -> Result<Vec<String>, String> {
        let host = cpal::default_host();
        let devices = host
            .input_devices()
            .map_err(|e| format!("Failed to list devices: {}", e))?;
        Ok(devices.filter_map(|d| d.name().ok()).collect())
    }

    fn find_best_config(device: &cpal::Device) -> Result<cpal::StreamConfig, String> {
        let supported_configs: Vec<_> = device
            .supported_input_configs()
            .map_err(|e| format!("Config error: {}", e))?
            .filter(|c| c.sample_format() == SampleFormat::F32)
            .collect();

        if supported_configs.is_empty() {
            return Err("No supported F32 audio config found".to_string());
        }

        if let Some(c) = supported_configs
            .iter()
            .find(|c| c.min_sample_rate().0 <= 16000 && c.max_sample_rate().0 >= 16000)
        {
            return Ok(c.clone().with_sample_rate(cpal::SampleRate(16000)).config());
        }
        if let Some(c) = supported_configs
            .iter()
            .find(|c| c.min_sample_rate().0 <= 48000 && c.max_sample_rate().0 >= 48000)
        {
            return Ok(c.clone().with_sample_rate(cpal::SampleRate(48000)).config());
        }
        Ok(supported_configs[0].clone().with_max_sample_rate().config())
    }

    pub fn start(
        &self,
        app_handle: AppHandle,
        device_name: Option<String>,
        capture_system_audio: bool,
        _system_audio_device_name: Option<String>,
        sender: Option<tokio::sync::mpsc::Sender<Vec<f32>>>,
    ) -> Result<RecordingStatus, String> {
        eprintln!("[audio] start() called, capture_system_audio={}", capture_system_audio);

        if self.recording.load(Ordering::SeqCst) {
            return Err("Already recording".into());
        }

        if let Some(s) = sender {
            *self.audio_sender.lock().unwrap() = Some(s);
        }

        let host = cpal::default_host();

        let device = match &device_name {
            Some(name) => {
                host.input_devices()
                    .map_err(|e| format!("Device error: {}", e))?
                    .find(|d| d.name().map(|n| &n == name).unwrap_or(false))
                    .ok_or_else(|| format!("Device '{}' not found", name))?
            }
            None => {
                host.default_input_device()
                    .ok_or("No default input device available".to_string())?
            }
        };

        let actual_name = device.name().unwrap_or_else(|_| "Unknown".into());
        eprintln!("[audio] mic device: {}", actual_name);

        let config = Self::find_best_config(&device)?;
        let sample_rate = config.sample_rate.0;
        let channels = config.channels;
        eprintln!("[audio] mic config: sr={}, ch={}", sample_rate, channels);

        self.recording.store(true, Ordering::SeqCst);
        let flag = self.recording.clone();
        let emit_handle = app_handle.clone();

        let chunk_size = (sample_rate as usize / 10) * channels as usize;
        let mic_buf: Arc<Mutex<Vec<f32>>> =
            Arc::new(Mutex::new(Vec::with_capacity(chunk_size * 2)));
        let mic_buf_clone = mic_buf.clone();
        let sender = self.audio_sender.clone();
        let sys_buf = self.system_audio_buffer.clone();

        let _mic_stream = device
            .build_input_stream(
                &config,
                move |data: &[f32], _: &cpal::InputCallbackInfo| {
                    if !flag.load(Ordering::SeqCst) {
                        return;
                    }
                    if let Ok(mut b) = mic_buf_clone.lock() {
                        b.extend_from_slice(data);
                        if b.len() >= chunk_size {
                            let mut chunk: Vec<f32> = b.drain(..chunk_size).collect();

                            if let Ok(mut sys) = sys_buf.lock() {
                                let mix_len = chunk.len().min(sys.len());
                                for i in 0..mix_len {
                                    chunk[i] = chunk[i] + sys[i];
                                }
                                if mix_len > 0 {
                                    sys.drain(..mix_len);
                                }
                            }

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
                |err| eprintln!("[audio] mic stream error: {}", err),
                None,
            )
            .map_err(|e| format!("Mic stream build error: {}", e))?;

        _mic_stream.play().map_err(|e| format!("Mic stream play error: {}", e))?;
        eprintln!("[audio] mic stream playing!");

        let mut system_audio_display_name = String::new();

        if capture_system_audio {
            if let Some(source) = system_audio::find_system_audio_source() {
                let sys_buf_clone = self.system_audio_buffer.clone();
                let flag_clone = self.recording.clone();

                if let Some(capture) = system_audio::SystemAudioCapture::start(&source, sys_buf_clone, flag_clone) {
                    eprintln!("[audio] system audio capture started");
                    system_audio_display_name = format!("System Audio ({})", source);
                    *self.system_audio_capture.lock().unwrap() = Some(capture);
                } else {
                    eprintln!("[audio] failed to start system audio capture");
                }
            } else {
                eprintln!("[audio] no system audio source found");
            }
        }

        std::mem::forget(_mic_stream);

        let display_name = if system_audio_display_name.is_empty() {
            actual_name
        } else {
            format!("{} + {}", actual_name, system_audio_display_name)
        };

        let status = RecordingStatus {
            is_recording: true,
            device_name: display_name,
            sample_rate,
            channels,
        };

        let _ = app_handle.emit("recording-status", &status);

        Ok(status)
    }

    pub fn stop(&self, app_handle: AppHandle) -> Result<(), String> {
        self.recording.store(false, Ordering::SeqCst);

        if let Ok(mut guard) = self.system_audio_capture.lock() {
            if let Some(ref mut capture) = *guard {
                capture.stop();
            }
            *guard = None;
        }

        if let Ok(mut guard) = self.audio_sender.lock() {
            *guard = None;
        }

        if let Ok(mut buf) = self.system_audio_buffer.lock() {
            buf.clear();
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
