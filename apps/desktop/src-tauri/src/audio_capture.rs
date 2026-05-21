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

pub struct CaptureSinks {
    /// Recebe i16 16kHz mono do mic.
    pub mic_tx: tokio::sync::mpsc::Sender<Vec<i16>>,
    /// Recebe i16 16kHz mono do system audio (None se desabilitado).
    pub system_tx: Option<tokio::sync::mpsc::Sender<Vec<i16>>>,
}

struct MicStream {
    running: Arc<AtomicBool>,
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
        eprintln!("[audio] mic device len={}", actual_name.len());

        let config = Self::find_best_config(&device)?;
        let sample_rate = config.sample_rate.0;
        let channels = config.channels;
        #[cfg(debug_assertions)]
        eprintln!("[audio] mic config: sr={}, ch={}", sample_rate, channels);

        // Spawn mic thread. `running` (não confundir com nome anterior `stop_flag`):
        // true = thread está rodando; setar false sinaliza shutdown.
        // O canal `armed_tx` reporta sucesso/erro do setup DENTRO da thread; o caller
        // só retorna `Ok(status)` depois de receber `Ok(())`, evitando o caso anterior
        // em que start_recording retornava Ok mas a thread morria silenciosamente.
        let running = Arc::new(AtomicBool::new(true));
        let mic_tx = sinks.mic_tx;
        let (armed_tx, armed_rx) = std::sync::mpsc::sync_channel::<Result<(), String>>(1);

        let join = std::thread::Builder::new()
            .name("nora-mic".into())
            .spawn({
                let running_thread = running.clone();
                let device_name = device_name.clone();
                move || {
                    let report = |r: Result<(), String>| {
                        let _ = armed_tx.send(r);
                    };

                    let host = cpal::default_host();

                    let device = match &device_name {
                        Some(name) => {
                            let mut devices = match host.input_devices() {
                                Ok(d) => d,
                                Err(e) => {
                                    report(Err(format!("list input devices: {}", e)));
                                    return;
                                }
                            };
                            match devices.find(|d| d.name().map(|n| &n == name).unwrap_or(false)) {
                                Some(d) => d,
                                None => {
                                    report(Err(format!("input device '{}' not found", name)));
                                    return;
                                }
                            }
                        }
                        None => match host.default_input_device() {
                            Some(d) => d,
                            None => {
                                report(Err("no default input device available".to_string()));
                                return;
                            }
                        },
                    };

                    let config = match AudioCapture::find_best_config(&device) {
                        Ok(c) => c,
                        Err(e) => {
                            report(Err(format!("find best config: {}", e)));
                            return;
                        }
                    };
                    let sr = config.sample_rate.0;
                    let ch = config.channels;

                    let mut resampler = match MonoResampler::new(sr, 16000) {
                        Ok(r) => r,
                        Err(e) => {
                            report(Err(format!("create resampler: {}", e)));
                            return;
                        }
                    };

                    // chunk_size = ~100ms de áudio (sr/10 frames × channels). Vamos drenar
                    // o buffer assim que ele atingir esse tamanho.
                    let chunk_size = (sr as usize / 10) * ch as usize;
                    let mic_buf: Arc<Mutex<Vec<f32>>> =
                        Arc::new(Mutex::new(Vec::with_capacity(chunk_size * 2)));
                    let mic_buf_clone = mic_buf.clone();

                    let running_stream = running_thread.clone();
                    let stream = match device.build_input_stream(
                        &config,
                        move |data: &[f32], _: &cpal::InputCallbackInfo| {
                            if !running_stream.load(Ordering::SeqCst) {
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
                            report(Err(format!("build input stream: {}", e)));
                            return;
                        }
                    };

                    if let Err(e) = stream.play() {
                        report(Err(format!("start stream: {}", e)));
                        return;
                    }

                    // A partir daqui o pipeline está armed; reporta sucesso ao caller.
                    report(Ok(()));

                    while running_thread.load(Ordering::SeqCst) {
                        std::thread::sleep(std::time::Duration::from_millis(100));
                    }

                    drop(stream);
                }
            })
            .map_err(|e| format!("spawn mic thread: {}", e))?;

        // Aguarda o setup da thread (até 5s). Se falhar, sinaliza shutdown e propaga
        // o erro — assim `start_recording` não retorna Ok com uma thread morta.
        match armed_rx.recv_timeout(std::time::Duration::from_secs(5)) {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                running.store(false, Ordering::SeqCst);
                let _ = join.join();
                return Err(format!("mic thread failed to arm: {}", e));
            }
            Err(_) => {
                running.store(false, Ordering::SeqCst);
                let _ = join.join();
                return Err("mic thread arm timeout (5s)".to_string());
            }
        }

        let mic_stream = MicStream {
            running,
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
                    eprintln!(
                        "[audio] using explicit system audio device (len={})",
                        name.len()
                    );
                    name.to_string()
                })
                .or_else(system_audio::find_system_audio_source);

            // Se o caller pediu system audio mas esqueceu de prover o sink, falha cedo
            // em vez de panicar dentro do unwrap.
            let system_tx = match sinks.system_tx {
                Some(tx) => tx,
                None => {
                    return Err(
                        "capture_system_audio=true mas sinks.system_tx ausente".to_string(),
                    );
                }
            };

            if let Some(source) = source {
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
                mic.running.store(false, Ordering::SeqCst);
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
