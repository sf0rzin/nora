use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::SampleFormat;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter};

use crate::audio_resample::{downmix_to_mono, f32_to_i16, MonoResampler};
use crate::system_audio;

#[derive(Debug, serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RecordingStatus {
    pub is_recording: bool,
    pub mic_device: String,
    pub system_audio_device: Option<String>,
    pub sample_rate: u32,
}

/// Avisa a UI que a gravação NÃO chegou a iniciar de fato — falha dentro da thread de áudio
/// (abrir o stream, play()) que acontece DEPOIS de start() já ter retornado is_recording:true.
/// A UI escuta "recording-status" e reverte o estado de "gravando" em vez de fingir que grava.
fn emit_recording_failed(app: &AppHandle) {
    let status = RecordingStatus {
        is_recording: false,
        mic_device: String::new(),
        system_audio_device: None,
        sample_rate: 0,
    };
    let _ = app.emit("recording-status", &status);
}

pub struct CaptureSinks {
    /// Recebe i16 16kHz mono do mic.
    pub mic_tx: tokio::sync::mpsc::Sender<Vec<i16>>,
    /// Recebe i16 16kHz mono do system audio (None se desabilitado).
    pub system_tx: Option<tokio::sync::mpsc::Sender<Vec<i16>>>,
}

struct MicStream {
    stop_flag: Arc<AtomicBool>,
    join: std::thread::JoinHandle<()>,
}

pub struct AudioCapture {
    mic: Mutex<Option<MicStream>>,
    system: Mutex<Option<system_audio::SystemAudioCapture>>,
    current_status: Mutex<RecordingStatus>,
}

impl AudioCapture {
    pub fn new() -> Self {
        Self {
            mic: Mutex::new(None),
            system: Mutex::new(None),
            current_status: Mutex::new(RecordingStatus {
                is_recording: false,
                mic_device: String::new(),
                system_audio_device: None,
                sample_rate: 0,
            }),
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

        // Prefer 16kHz native to avoid resampling entirely
        if let Some(c) = supported_configs
            .iter()
            .find(|c| c.min_sample_rate().0 <= 16000 && c.max_sample_rate().0 >= 16000)
        {
            return Ok((*c).with_sample_rate(cpal::SampleRate(16000)).config());
        }
        // Fallback to 48kHz (common on desktops, good quality)
        if let Some(c) = supported_configs
            .iter()
            .find(|c| c.min_sample_rate().0 <= 48000 && c.max_sample_rate().0 >= 48000)
        {
            return Ok((*c).with_sample_rate(cpal::SampleRate(48000)).config());
        }
        Ok(supported_configs[0].with_max_sample_rate().config())
    }

    pub fn start(
        &self,
        app_handle: AppHandle,
        device_name: Option<String>,
        capture_system_audio: bool,
        system_audio_device_name: Option<String>,
        sinks: CaptureSinks,
    ) -> Result<RecordingStatus, String> {
        #[cfg(debug_assertions)]
        eprintln!("[audio] start() called, capture_system_audio={}", capture_system_audio);

        // Check if already recording
        if let Ok(guard) = self.mic.lock() {
            if guard.is_some() {
                return Err("Already recording".into());
            }
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
        #[cfg(debug_assertions)]
        eprintln!("[audio] mic device: {}", actual_name);

        let config = Self::find_best_config(&device)?;
        let sample_rate = config.sample_rate.0;
        let channels = config.channels;
        #[cfg(debug_assertions)]
        eprintln!("[audio] mic config: sr={}, ch={}", sample_rate, channels);

        // Spawn mic thread
        let stop_flag = Arc::new(AtomicBool::new(true));
        let mic_tx = sinks.mic_tx;

        let join = std::thread::Builder::new()
            .name("nora-mic".into())
            .spawn({
                let stop_flag_thread = stop_flag.clone();
                let device_name = device_name.clone();
                let app_for_thread = app_handle.clone();
                move || {
                    let host = cpal::default_host();

                    let device = match &device_name {
                        Some(name) => {
                            let mut devices = match host.input_devices() {
                                Ok(d) => d,
                                Err(e) => {
                                    eprintln!("[audio] failed to list input devices: {}", e);
                                    return;
                                }
                            };
                            match devices.find(|d| d.name().map(|n| &n == name).unwrap_or(false)) {
                                Some(d) => d,
                                None => {
                                    eprintln!("[audio] input device '{}' not found", name);
                                    return;
                                }
                            }
                        }
                        None => match host.default_input_device() {
                            Some(d) => d,
                            None => {
                                eprintln!("[audio] no default input device available");
                                return;
                            }
                        },
                    };

                    let config = match AudioCapture::find_best_config(&device) {
                        Ok(c) => c,
                        Err(e) => {
                            eprintln!("[audio] failed to find best config: {}", e);
                            return;
                        }
                    };
                    let sr = config.sample_rate.0;
                    let ch = config.channels;

                    let mut resampler = match MonoResampler::new(sr, 16000) {
                        Ok(r) => r,
                        Err(e) => {
                            eprintln!("[audio] failed to create resampler: {}", e);
                            return;
                        }
                    };

                    let chunk_size = (sr as usize / 10) * ch as usize;
                    let mic_buf: Arc<Mutex<Vec<f32>>> =
                        Arc::new(Mutex::new(Vec::with_capacity(chunk_size * 2)));
                    let mic_buf_clone = mic_buf.clone();

                    let stop_flag_stream = stop_flag_thread.clone();
                    let stream = match device.build_input_stream(
                        &config,
                        move |data: &[f32], _: &cpal::InputCallbackInfo| {
                            if !stop_flag_stream.load(Ordering::SeqCst) {
                                return;
                            }
                            if let Ok(mut b) = mic_buf_clone.lock() {
                                b.extend_from_slice(data);
                                if b.len() >= chunk_size {
                                    let chunk: Vec<f32> = b.drain(..chunk_size).collect();
                                    let mono = downmix_to_mono(&chunk, ch as usize);
                                    let resampled = resampler.process(&mono);
                                    let i16_samples = f32_to_i16(&resampled);
                                    let _ = mic_tx.try_send(i16_samples);
                                }
                            }
                        },
                        |err| {
                            #[cfg(debug_assertions)]
                            eprintln!("[audio] mic stream error: {}", err)
                        },
                        None,
                    ) {
                        Ok(s) => s,
                        Err(e) => {
                            eprintln!("[audio] failed to build input stream: {}", e);
                            emit_recording_failed(&app_for_thread);
                            return;
                        }
                    };

                    if let Err(e) = stream.play() {
                        eprintln!("[audio] failed to start stream: {}", e);
                        emit_recording_failed(&app_for_thread);
                        return;
                    }

                    // Keep thread alive until stop flag is set
                    while stop_flag_thread.load(Ordering::SeqCst) {
                        std::thread::sleep(std::time::Duration::from_millis(100));
                    }

                    // Stream is dropped here, stopping ALSA device
                    drop(stream);
                }
            })
            .map_err(|e| format!("spawn mic thread: {}", e))?;

        let mic_stream = MicStream {
            stop_flag,
            join,
        };

        if let Ok(mut guard) = self.mic.lock() {
            *guard = Some(mic_stream);
        }

        // Start system audio if requested
        let mut system_audio_display_name = None;

        if capture_system_audio {
            let source = system_audio_device_name
                .as_deref()
                .map(|name| {
                    #[cfg(debug_assertions)]
                    eprintln!("[audio] using explicit system audio device: {}", name);
                    name.to_string()
                })
                .or_else(system_audio::find_system_audio_source);

            // Exige fonte E sink: o unwrap() panicava se o contrato caller/callee
            // divergisse (capture_system_audio sem system_tx). Auditoria #19.
            if let (Some(source), Some(system_tx)) = (source, sinks.system_tx) {
                let flag = Arc::new(AtomicBool::new(true));

                match system_audio::SystemAudioCapture::start(
                    &source,
                    16000,
                    system_tx,
                    flag,
                ) {
                    Ok(capture) => {
                        #[cfg(debug_assertions)]
                        eprintln!("[audio] system audio capture started");
                        system_audio_display_name = Some(format!("System Audio ({})", source));
                        if let Ok(mut guard) = self.system.lock() {
                            *guard = Some(capture);
                        }
                    }
                    Err(e) => {
                        #[cfg(debug_assertions)]
                        eprintln!("[audio] failed to start system audio capture: {}", e);
                    }
                }
            } else {
                #[cfg(debug_assertions)]
                eprintln!("[audio] no system audio source found");
            }
        }

        let status = RecordingStatus {
            is_recording: true,
            mic_device: actual_name,
            system_audio_device: system_audio_display_name,
            sample_rate: 16000,
        };

        // Store current status
        if let Ok(mut guard) = self.current_status.lock() {
            *guard = status.clone();
        }

        let _ = app_handle.emit("recording-status", &status);

        Ok(status)
    }

    pub fn get_status(&self) -> RecordingStatus {
        if let Ok(guard) = self.current_status.lock() {
            guard.clone()
        } else {
            RecordingStatus {
                is_recording: false,
                mic_device: String::new(),
                system_audio_device: None,
                sample_rate: 0,
            }
        }
    }

    pub fn stop(&self, app_handle: AppHandle) -> Result<(), String> {
        // Stop mic thread
        if let Ok(mut guard) = self.mic.lock() {
            if let Some(mic) = guard.take() {
                mic.stop_flag.store(false, Ordering::SeqCst);
                let _ = mic.join.join();
            }
        }

        // Stop system audio
        if let Ok(mut guard) = self.system.lock() {
            if let Some(ref mut capture) = *guard {
                capture.stop();
            }
            *guard = None;
        }

        let status = RecordingStatus {
            is_recording: false,
            mic_device: String::new(),
            system_audio_device: None,
            sample_rate: 0,
        };

        // Clear current status
        if let Ok(mut guard) = self.current_status.lock() {
            *guard = status.clone();
        }

        let _ = app_handle.emit("recording-status", &status);

        Ok(())
    }
}
