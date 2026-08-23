use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::SampleFormat;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter};

use crate::audio_resample::{downmix_to_mono, f32_to_i16, MonoResampler};
use crate::stt::TARGET_SAMPLE_RATE;
use crate::system_audio;

#[derive(Debug, serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RecordingStatus {
    pub is_recording: bool,
    pub mic_device: String,
    pub system_audio_device: Option<String>,
    pub sample_rate: u32,
}

/// What the status reports for the system track.
///
/// It names the endpoint the WASAPI loopback actually captures — the Windows default render
/// device — instead of echoing a choice back at the user. The old label interpolated whatever
/// device name the UI had sent while the capture ignored it entirely, so the status confirmed a
/// selection that never took effect (audit #14).
const SYSTEM_AUDIO_LABEL: &str = "System Audio (Windows default output)";

/// Warns the UI that the recording did NOT actually start — a failure inside the audio thread
/// (opening the stream, play()) that happens AFTER start() has already returned is_recording:true.
/// The UI listens to "recording-status" and reverts the "recording" state instead of pretending it records.
fn emit_recording_failed(app: &AppHandle) {
    let status = RecordingStatus {
        is_recording: false,
        mic_device: String::new(),
        system_audio_device: None,
        sample_rate: 0,
    };
    let _ = app.emit("recording-status", &status);
}

/// Says WHICH track stopped producing audio and why, in a sentence the front end can put in
/// front of the user while the meeting is still running.
///
/// `emit_recording_failed` above only flips the status back to "not recording", which is the
/// right answer when the mic dies and the wrong one when the loopback does — the mic track
/// carries on and the recording is merely degraded. Neither case had any signal at all before:
/// the cpal error callback was an empty closure in release and the WASAPI thread died on a `?`,
/// so a headset unplugged at minute 12 produced a UI that said "recording" for the next hour
/// and a transcript that stopped (audit #11, #12).
pub(crate) fn emit_capture_error(app: &AppHandle, track: &str, message: &str) {
    eprintln!("[audio] capture error on track '{}': {}", track, message);
    let _ = app.emit(
        "capture-error",
        &serde_json::json!({ "track": track, "message": message }),
    );
}

pub struct CaptureSinks {
    /// Receives mono i16 at [`TARGET_SAMPLE_RATE`] from the mic.
    pub mic_tx: tokio::sync::mpsc::Sender<Vec<i16>>,
    /// Receives mono i16 at [`TARGET_SAMPLE_RATE`] from the system audio (None if disabled).
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

        // Prefer the pipeline's target rate natively, which skips resampling entirely
        // (`MonoResampler` bypasses when src == dst).
        if let Some(c) = supported_configs.iter().find(|c| {
            c.min_sample_rate().0 <= TARGET_SAMPLE_RATE
                && c.max_sample_rate().0 >= TARGET_SAMPLE_RATE
        }) {
            return Ok((*c)
                .with_sample_rate(cpal::SampleRate(TARGET_SAMPLE_RATE))
                .config());
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
        sinks: CaptureSinks,
    ) -> Result<RecordingStatus, String> {
        #[cfg(debug_assertions)]
        eprintln!(
            "[audio] start() called, capture_system_audio={}",
            capture_system_audio
        );

        // Check if already recording
        if let Ok(mut guard) = self.mic.lock() {
            if let Some(mic) = guard.as_ref() {
                if mic.join.is_finished() {
                    // The thread ended on its own — the device went away and the error paths
                    // below emitted "recording failed". The slot is stale, not busy, and
                    // reclaiming it is what lets the user act on that warning: without this,
                    // pressing record again answered "Already recording" and the only way out
                    // was to stop a recording the UI had already given up on.
                    let _ = guard.take();
                } else {
                    return Err("Already recording".into());
                }
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

                    // Every `return` below leaves the caller holding an Ok(status) that already
                    // says is_recording:true, so each one has to say so. Four of them repeat a
                    // check `start()` made synchronously and are therefore near-impossible, but
                    // "near-impossible" here means the device was pulled between the check and
                    // the spawn — which is the same accident as audit #11, only narrower.
                    let device = match &device_name {
                        Some(name) => {
                            let mut devices = match host.input_devices() {
                                Ok(d) => d,
                                Err(e) => {
                                    eprintln!("[audio] failed to list input devices: {}", e);
                                    emit_capture_error(
                                        &app_for_thread,
                                        "mic",
                                        &format!("could not list the input devices: {}", e),
                                    );
                                    emit_recording_failed(&app_for_thread);
                                    return;
                                }
                            };
                            match devices.find(|d| d.name().map(|n| &n == name).unwrap_or(false)) {
                                Some(d) => d,
                                None => {
                                    eprintln!("[audio] input device '{}' not found", name);
                                    emit_capture_error(
                                        &app_for_thread,
                                        "mic",
                                        &format!("the input device '{}' is gone", name),
                                    );
                                    emit_recording_failed(&app_for_thread);
                                    return;
                                }
                            }
                        }
                        None => match host.default_input_device() {
                            Some(d) => d,
                            None => {
                                eprintln!("[audio] no default input device available");
                                emit_capture_error(
                                    &app_for_thread,
                                    "mic",
                                    "there is no default input device any more",
                                );
                                emit_recording_failed(&app_for_thread);
                                return;
                            }
                        },
                    };

                    let config = match AudioCapture::find_best_config(&device) {
                        Ok(c) => c,
                        Err(e) => {
                            eprintln!("[audio] failed to find best config: {}", e);
                            emit_capture_error(
                                &app_for_thread,
                                "mic",
                                &format!("no usable audio format on this microphone: {}", e),
                            );
                            emit_recording_failed(&app_for_thread);
                            return;
                        }
                    };
                    let sr = config.sample_rate.0;
                    let ch = config.channels;

                    // The one failure in this thread with NO synchronous counterpart: nothing
                    // upstream builds a resampler, so this return was silent by construction.
                    let mut resampler = match MonoResampler::new(sr, TARGET_SAMPLE_RATE) {
                        Ok(r) => r,
                        Err(e) => {
                            eprintln!("[audio] failed to create resampler: {}", e);
                            emit_capture_error(
                                &app_for_thread,
                                "mic",
                                &format!(
                                    "could not resample {} Hz to {} Hz: {}",
                                    sr, TARGET_SAMPLE_RATE, e
                                ),
                            );
                            emit_recording_failed(&app_for_thread);
                            return;
                        }
                    };

                    let chunk_size = (sr as usize / 10) * ch as usize;
                    let mic_buf: Arc<Mutex<Vec<f32>>> =
                        Arc::new(Mutex::new(Vec::with_capacity(chunk_size * 2)));
                    let mic_buf_clone = mic_buf.clone();

                    let stop_flag_stream = stop_flag_thread.clone();
                    let stop_flag_err = stop_flag_thread.clone();
                    let app_for_err = app_for_thread.clone();
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
                        move |err| {
                            // Where a headset being unplugged, a USB dock being removed or the
                            // default device changing lands. cpal calls this once and then
                            // simply stops delivering buffers; in release this closure had an
                            // empty body, so the recording went on producing silence with the
                            // track clock advancing over it (audit #11).
                            emit_capture_error(&app_for_err, "mic", &err.to_string());
                            emit_recording_failed(&app_for_err);
                            // Ends the keep-alive loop below so the stream is dropped instead
                            // of being held open against a device that is gone.
                            stop_flag_err.store(false, Ordering::SeqCst);
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

        let mic_stream = MicStream { stop_flag, join };

        if let Ok(mut guard) = self.mic.lock() {
            *guard = Some(mic_stream);
        }

        // Start system audio if requested
        let mut system_audio_display_name = None;

        if capture_system_audio {
            // Requires a sink: the unwrap() panicked if the caller/callee contract diverged
            // (capture_system_audio without system_tx). Audit #19.
            //
            // There is no device to pick. The loopback attaches to the default render endpoint
            // and nothing else, which is why the `system_audio_device` this function used to
            // take is gone rather than plumbed one layer deeper (audit #14).
            match sinks.system_tx {
                Some(system_tx) => {
                    let flag = Arc::new(AtomicBool::new(true));

                    match system_audio::SystemAudioCapture::start(
                        app_handle.clone(),
                        TARGET_SAMPLE_RATE,
                        system_tx,
                        flag,
                    ) {
                        Ok(capture) => {
                            #[cfg(debug_assertions)]
                            eprintln!("[audio] system audio capture started");
                            system_audio_display_name = Some(SYSTEM_AUDIO_LABEL.to_string());
                            if let Ok(mut guard) = self.system.lock() {
                                *guard = Some(capture);
                            }
                        }
                        Err(e) => {
                            // The user asked for the remote participants and is about to get a
                            // recording of their own voice only — which is the half of the
                            // meeting they already know. Degrading in silence made that a
                            // discovery for upload time (audit #12).
                            emit_capture_error(
                                &app_handle,
                                "system",
                                &format!("system audio capture did not start: {}", e),
                            );
                        }
                    }
                }
                None => {
                    emit_capture_error(
                        &app_handle,
                        "system",
                        "system audio was requested without a destination for it",
                    );
                }
            }
        }

        let status = RecordingStatus {
            is_recording: true,
            mic_device: actual_name,
            system_audio_device: system_audio_display_name,
            sample_rate: TARGET_SAMPLE_RATE,
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

    /// True when the recording is running without the system track the caller asked for.
    ///
    /// The status carries the degradation as an absent device name, and `commands.rs` needs to
    /// read it to shut down the transcription session that track will never feed (audit #12).
    pub fn is_system_audio_degraded(status: &RecordingStatus, requested: bool) -> bool {
        requested && status.system_audio_device.is_none()
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

#[cfg(test)]
mod tests {
    use super::*;

    fn status(system: Option<&str>) -> RecordingStatus {
        RecordingStatus {
            is_recording: true,
            mic_device: "Headset".to_string(),
            system_audio_device: system.map(String::from),
            sample_rate: TARGET_SAMPLE_RATE,
        }
    }

    /// These four names are a runtime contract with `recording-types.ts` and the two hooks that
    /// read the `recording-status` payload. Rename a field here and the front end reads
    /// `undefined`, which it treats as "not recording" — silently, like everything else this
    /// module was audited for.
    #[test]
    fn the_status_payload_keeps_the_names_the_front_end_reads() {
        let json = serde_json::to_value(status(None)).unwrap();
        assert_eq!(json["isRecording"], true);
        assert_eq!(json["micDevice"], "Headset");
        assert!(json["systemAudioDevice"].is_null());
        assert_eq!(json["sampleRate"], TARGET_SAMPLE_RATE);
    }

    /// The degradation the user has to be told about: they asked for the remote participants
    /// and the loopback did not come up, so the recording holds their own voice only.
    #[test]
    fn a_missing_system_device_is_a_degradation_only_when_it_was_asked_for() {
        assert!(AudioCapture::is_system_audio_degraded(&status(None), true));
        assert!(!AudioCapture::is_system_audio_degraded(
            &status(None),
            false
        ));
        assert!(!AudioCapture::is_system_audio_degraded(
            &status(Some(SYSTEM_AUDIO_LABEL)),
            true
        ));
    }

    /// The label describes the endpoint that is really captured. It used to interpolate the
    /// device the user had picked in the UI while the loopback recorded the default one, so the
    /// status confirmed a choice that had no effect (audit #14).
    #[test]
    fn the_system_label_does_not_echo_a_user_choice() {
        assert!(!SYSTEM_AUDIO_LABEL.contains('{'));
        assert!(SYSTEM_AUDIO_LABEL.contains("default output"));
    }
}
