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
/// (abrir o stream, play(), config, resampler) que acontece DEPOIS de start() já ter
/// retornado is_recording:true. A UI escuta "recording-status" e reverte o estado de
/// "gravando" em vez de fingir que grava.
fn emit_recording_failed(app: &AppHandle) {
    let status = RecordingStatus {
        is_recording: false,
        mic_device: String::new(),
        system_audio_device: None,
        sample_rate: 0,
    };
    let _ = app.emit("recording-status", &status);
}

/// Converte uma amostra do formato nativo do device pra f32 normalizado [-1,1].
/// O WASAPI (Windows) pode entregar o mic em F32, I16 ou U16 dependendo do
/// formato escolhido nas configs de som — antes só tratávamos F32, então um mic
/// em "16 bits" abria zero stream (silenciosamente). Agora cobrimos os três.
trait SampleToF32: Copy {
    fn to_f32_sample(self) -> f32;
}
impl SampleToF32 for f32 {
    fn to_f32_sample(self) -> f32 {
        self
    }
}
impl SampleToF32 for i16 {
    fn to_f32_sample(self) -> f32 {
        self as f32 / 32768.0
    }
}
impl SampleToF32 for u16 {
    fn to_f32_sample(self) -> f32 {
        (self as f32 - 32768.0) / 32768.0
    }
}

/// Constrói o input stream do mic pra um formato de amostra T concreto. O
/// callback converte T→f32, faz downmix→mono, resample→16kHz e empurra i16 pro
/// canal. Genérico pra suportarmos F32/I16/U16 com um caminho só.
#[allow(clippy::too_many_arguments)]
fn build_mic_stream<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    stop_flag: Arc<AtomicBool>,
    mic_buf: Arc<Mutex<Vec<f32>>>,
    mut resampler: MonoResampler,
    channels: usize,
    chunk_size: usize,
    mic_tx: tokio::sync::mpsc::Sender<Vec<i16>>,
    app: AppHandle,
) -> Result<cpal::Stream, cpal::BuildStreamError>
where
    T: cpal::SizedSample + SampleToF32 + Send + 'static,
{
    device.build_input_stream(
        config,
        move |data: &[T], _: &cpal::InputCallbackInfo| {
            // stop_flag=true => gravando (processa). stop() seta false => ignora.
            if !stop_flag.load(Ordering::SeqCst) {
                return;
            }
            if let Ok(mut b) = mic_buf.lock() {
                b.extend(data.iter().map(|&s| s.to_f32_sample()));
                while b.len() >= chunk_size {
                    let chunk: Vec<f32> = b.drain(..chunk_size).collect();
                    let mono = downmix_to_mono(&chunk, channels);
                    let resampled = resampler.process(&mono);
                    let i16_samples = f32_to_i16(&resampled);
                    let _ = mic_tx.try_send(i16_samples);
                }
            }
        },
        move |err| {
            // Erros pós-play() do WASAPI (mic ocupado/exclusivo, endpoint
            // invalidado) chegam SÓ aqui — nunca no retorno de play(). Sem isto o
            // mic "abria" e ficava mudo sem nenhum sinal. Loga + reverte a UI.
            crate::applog::log_line(
                &app,
                &format!("mic-thread: STREAM ERROR (callback): {}", err),
            );
            emit_recording_failed(&app);
        },
        None,
    )
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

    /// Escolhe a melhor config de captura do device. Preferimos F32 (caminho
    /// nativo do pipeline), mas aceitamos I16/U16 — antes filtrávamos SÓ F32, o
    /// que fazia mics em 16 bits no Windows não abrirem stream nenhum.
    /// Preferência de taxa: 16kHz nativo (sem resample), depois 48kHz, depois o
    /// máximo suportado.
    fn find_best_config(device: &cpal::Device) -> Result<cpal::SupportedStreamConfig, String> {
        let ranges: Vec<cpal::SupportedStreamConfigRange> = device
            .supported_input_configs()
            .map_err(|e| format!("Config error: {}", e))?
            .collect();

        if ranges.is_empty() {
            return Err("No supported audio input config found".to_string());
        }

        let in_range = |r: &cpal::SupportedStreamConfigRange, target: u32| {
            r.min_sample_rate().0 <= target && r.max_sample_rate().0 >= target
        };
        let is_pcm = |r: &cpal::SupportedStreamConfigRange| {
            matches!(
                r.sample_format(),
                SampleFormat::F32 | SampleFormat::I16 | SampleFormat::U16
            )
        };

        // Pra cada taxa preferida: tenta F32 primeiro, depois qualquer PCM.
        for target in [16000u32, 48000u32] {
            if let Some(r) = ranges
                .iter()
                .find(|r| r.sample_format() == SampleFormat::F32 && in_range(r, target))
            {
                return Ok(r.with_sample_rate(cpal::SampleRate(target)));
            }
            if let Some(r) = ranges.iter().find(|r| is_pcm(r) && in_range(r, target)) {
                return Ok(r.with_sample_rate(cpal::SampleRate(target)));
            }
        }

        // Fallback: primeiro PCM na sua taxa máxima. Se não há NENHUM PCM
        // suportado (F32/I16/U16), falha com erro claro em vez de devolver um
        // formato (I32/F64/…) que o dispatch da thread não trata.
        if let Some(r) = ranges.iter().find(|r| is_pcm(r)) {
            return Ok(r.with_max_sample_rate());
        }
        Err("Nenhum formato PCM suportado (F32/I16/U16) neste device de entrada".to_string())
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
            Some(name) => host
                .input_devices()
                .map_err(|e| format!("Device error: {}", e))?
                .find(|d| d.name().map(|n| &n == name).unwrap_or(false))
                .ok_or_else(|| format!("Device '{}' not found", name))?,
            None => host
                .default_input_device()
                .ok_or("No default input device available".to_string())?,
        };

        let actual_name = device.name().unwrap_or_else(|_| "Unknown".into());

        // Validação cedo: se o device não tem config usável, falha AGORA (a UI
        // mostra o erro) em vez de spawnar uma thread que morre em silêncio.
        let supported = Self::find_best_config(&device)?;
        #[cfg(debug_assertions)]
        eprintln!(
            "[audio] mic device: {} | config sr={} ch={} fmt={:?}",
            actual_name,
            supported.sample_rate().0,
            supported.channels(),
            supported.sample_format()
        );
        crate::applog::log_line(
            &app_handle,
            &format!(
                "audio.start: mic device='{}' sr={} ch={} fmt={:?}",
                actual_name,
                supported.sample_rate().0,
                supported.channels(),
                supported.sample_format()
            ),
        );

        // Spawn mic thread. stop_flag=true => rodando; stop() seta false.
        let stop_flag = Arc::new(AtomicBool::new(true));
        let mic_tx = sinks.mic_tx;

        let join = std::thread::Builder::new()
            .name("nora-mic".into())
            .spawn({
                let stop_flag_thread = stop_flag.clone();
                let device_name = device_name.clone();
                let app = app_handle.clone();
                move || {
                    let host = cpal::default_host();

                    let device = match &device_name {
                        Some(name) => {
                            let mut devices = match host.input_devices() {
                                Ok(d) => d,
                                Err(e) => {
                                    crate::applog::log_line(
                                        &app,
                                        &format!("mic-thread: list devices ERROR: {}", e),
                                    );
                                    emit_recording_failed(&app);
                                    return;
                                }
                            };
                            match devices
                                .find(|d| d.name().map(|n| &n == name).unwrap_or(false))
                            {
                                Some(d) => d,
                                None => {
                                    crate::applog::log_line(
                                        &app,
                                        &format!("mic-thread: device '{}' not found", name),
                                    );
                                    emit_recording_failed(&app);
                                    return;
                                }
                            }
                        }
                        None => match host.default_input_device() {
                            Some(d) => d,
                            None => {
                                crate::applog::log_line(
                                    &app,
                                    "mic-thread: no default input device",
                                );
                                emit_recording_failed(&app);
                                return;
                            }
                        },
                    };

                    let supported = match AudioCapture::find_best_config(&device) {
                        Ok(c) => c,
                        Err(e) => {
                            crate::applog::log_line(
                                &app,
                                &format!("mic-thread: find_config ERROR: {}", e),
                            );
                            emit_recording_failed(&app);
                            return;
                        }
                    };
                    let sample_format = supported.sample_format();
                    let config: cpal::StreamConfig = supported.config();
                    let sr = config.sample_rate.0;
                    let ch = config.channels as usize;

                    let resampler = match MonoResampler::new(sr, 16000) {
                        Ok(r) => r,
                        Err(e) => {
                            crate::applog::log_line(
                                &app,
                                &format!("mic-thread: resampler ERROR (sr={}): {}", sr, e),
                            );
                            emit_recording_failed(&app);
                            return;
                        }
                    };

                    let chunk_size = (sr as usize / 10) * ch; // ~100ms
                    let mic_buf: Arc<Mutex<Vec<f32>>> =
                        Arc::new(Mutex::new(Vec::with_capacity(chunk_size * 2)));

                    // build_mic_stream consome mic_buf/resampler/mic_tx; mover o
                    // mesmo binding em braços de match diferentes é permitido
                    // (só um braço executa).
                    let built = match sample_format {
                        SampleFormat::F32 => build_mic_stream::<f32>(
                            &device,
                            &config,
                            stop_flag_thread.clone(),
                            mic_buf,
                            resampler,
                            ch,
                            chunk_size,
                            mic_tx,
                            app.clone(),
                        ),
                        SampleFormat::I16 => build_mic_stream::<i16>(
                            &device,
                            &config,
                            stop_flag_thread.clone(),
                            mic_buf,
                            resampler,
                            ch,
                            chunk_size,
                            mic_tx,
                            app.clone(),
                        ),
                        SampleFormat::U16 => build_mic_stream::<u16>(
                            &device,
                            &config,
                            stop_flag_thread.clone(),
                            mic_buf,
                            resampler,
                            ch,
                            chunk_size,
                            mic_tx,
                            app.clone(),
                        ),
                        other => {
                            crate::applog::log_line(
                                &app,
                                &format!("mic-thread: unsupported sample format {:?}", other),
                            );
                            emit_recording_failed(&app);
                            return;
                        }
                    };

                    let stream = match built {
                        Ok(s) => s,
                        Err(e) => {
                            crate::applog::log_line(
                                &app,
                                &format!("mic-thread: build_input_stream ERROR: {}", e),
                            );
                            emit_recording_failed(&app);
                            return;
                        }
                    };

                    if let Err(e) = stream.play() {
                        crate::applog::log_line(
                            &app,
                            &format!("mic-thread: play ERROR: {}", e),
                        );
                        emit_recording_failed(&app);
                        return;
                    }

                    crate::applog::log_line(
                        &app,
                        &format!("mic-thread: stream playing (mic aberto) fmt={:?} sr={}", sample_format, sr),
                    );

                    // Mantém a thread viva (e o stream) até o stop flag virar false.
                    while stop_flag_thread.load(Ordering::SeqCst) {
                        std::thread::sleep(std::time::Duration::from_millis(100));
                    }

                    drop(stream);
                    crate::applog::log_line(&app, "mic-thread: stopped");
                }
            })
            .map_err(|e| format!("spawn mic thread: {}", e))?;

        let mic_stream = MicStream { stop_flag, join };

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

                match system_audio::SystemAudioCapture::start(&source, 16000, system_tx, flag) {
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
                        crate::applog::log_line(
                            &app_handle,
                            &format!("audio.start: system audio ERROR: {}", e),
                        );
                    }
                }
            } else {
                #[cfg(debug_assertions)]
                eprintln!("[audio] no system audio source found");
                crate::applog::log_line(&app_handle, "audio.start: no system audio source found");
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
