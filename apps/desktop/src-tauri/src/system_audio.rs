//! System-audio capture. WINDOWS ONLY.
//!
//! The client supports Windows and nothing else. There used to be a Linux module here
//! (`pactl`/`parecord` over PulseAudio) and a macOS one (the BlackHole virtual driver via
//! cpal, plus a half-written ScreenCaptureKit detection that was never going to be
//! finished). Neither had ever been run by anyone, so they were promises rather than
//! support, and they were deleted rather than kept.
//!
//! The `compile_error!` below is deliberate: without it, building for another target fails
//! with `cannot find module 'platform'`, which reads like a broken checkout instead of a
//! decision.

#[cfg(not(target_os = "windows"))]
compile_error!(
    "nora-desktop is Windows-only: system audio capture is implemented with WASAPI \
     loopback and has no counterpart on this target."
);

#[cfg(target_os = "windows")]
mod platform {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use windows::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
    use windows::Win32::Media::Audio::{
        eConsole, eRender, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator,
        MMDeviceEnumerator, AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_EVENTCALLBACK, AUDCLNT_STREAMFLAGS_LOOPBACK, WAVEFORMATEX,
        WAVEFORMATEXTENSIBLE,
    };
    use windows::Win32::Media::KernelStreaming::WAVE_FORMAT_EXTENSIBLE;
    use windows::Win32::Media::Multimedia::{
        KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, WAVE_FORMAT_IEEE_FLOAT,
    };
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize,
        CLSCTX_ALL, COINIT_MULTITHREADED,
    };
    use windows::Win32::System::Threading::{CreateEventW, WaitForSingleObject};

    pub fn find_system_audio_source() -> Option<String> {
        Some("wasapi_loopback".to_string())
    }

    pub struct SystemAudioCapture {
        stop_flag: Arc<AtomicBool>,
        thread: Option<std::thread::JoinHandle<()>>,
    }

    impl SystemAudioCapture {
        /// `_sample_rate_hint` is ignored: the loopback resamples to
        /// [`crate::stt::TARGET_SAMPLE_RATE`] like every other capture path, and taking the
        /// caller's word for the rate would be one more place for the two to drift apart. The
        /// parameter stays so the call site reads the same as the mic's.
        pub fn start(
            _source: &str,
            _sample_rate_hint: u32,
            sink: tokio::sync::mpsc::Sender<Vec<i16>>,
            flag: Arc<AtomicBool>,
        ) -> Result<Self, String> {
            let stop_flag = flag.clone();
            let thread = std::thread::Builder::new()
                .name("nora-wasapi-loopback".into())
                .spawn(move || unsafe {
                    if let Err(e) = run_loop(sink, flag) {
                        #[cfg(debug_assertions)]
                        eprintln!("[wasapi] loop error: {}", e);
                    }
                })
                .map_err(|e| format!("spawn wasapi thread: {}", e))?;

            Ok(Self { stop_flag, thread: Some(thread) })
        }

        pub fn stop(&mut self) {
            self.stop_flag.store(false, Ordering::SeqCst);
            if let Some(t) = self.thread.take() {
                let _ = t.join();
            }
        }
    }

    impl Drop for SystemAudioCapture {
        fn drop(&mut self) { self.stop(); }
    }

    unsafe fn run_loop(
        sink: tokio::sync::mpsc::Sender<Vec<i16>>,
        flag: Arc<AtomicBool>,
    ) -> windows::core::Result<()> {
        CoInitializeEx(None, COINIT_MULTITHREADED).ok()?;

        struct ComGuard;
        impl Drop for ComGuard { fn drop(&mut self) { unsafe { CoUninitialize(); } } }
        let _guard = ComGuard;

        let enumerator: IMMDeviceEnumerator =
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let device = enumerator.GetDefaultAudioEndpoint(eRender, eConsole)?;
        let audio_client: IAudioClient = device.Activate(CLSCTX_ALL, None)?;

        let mix_format_ptr = audio_client.GetMixFormat()?;
        let mix_format = &*mix_format_ptr;

        let event = CreateEventW(None, false, false, None)?;

        audio_client.Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
            10_000_000,
            0,
            mix_format_ptr,
            None,
        )?;
        audio_client.SetEventHandle(event)?;

        let capture_client: IAudioCaptureClient = audio_client.GetService()?;
        audio_client.Start()?;

        let src_sr = mix_format.nSamplesPerSec;
        let src_ch = mix_format.nChannels as usize;
        let is_float = is_ieee_float(mix_format);

        let mut resampler =
            crate::audio_resample::MonoResampler::new(src_sr, crate::stt::TARGET_SAMPLE_RATE)
                .unwrap();

        while flag.load(Ordering::SeqCst) {
            let wait = WaitForSingleObject(event, 100);
            if wait != WAIT_OBJECT_0 { continue; }

            loop {
                let frames_avail = capture_client.GetNextPacketSize()?;
                if frames_avail == 0 { break; }

                let mut data: *mut u8 = std::ptr::null_mut();
                let mut frames: u32 = 0;
                let mut flags: u32 = 0;
                capture_client.GetBuffer(&mut data, &mut frames, &mut flags, None, None)?;

                if frames > 0 {
                    let f32_mono = if (flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32) != 0 {
                        vec![0.0f32; frames as usize]
                    } else {
                        decode_to_f32_mono(data, frames as usize, src_ch, is_float)
                    };

                    let resampled = resampler.process(&f32_mono);
                    let i16_samples = crate::audio_resample::f32_to_i16(&resampled);
                    let _ = sink.try_send(i16_samples);
                }

                capture_client.ReleaseBuffer(frames)?;
            }
        }

        audio_client.Stop()?;
        CloseHandle(event)?;
        CoTaskMemFree(Some(mix_format_ptr as *const _ as *mut _));
        Ok(())
    }

    unsafe fn is_ieee_float(fmt: &WAVEFORMATEX) -> bool {
        if fmt.wFormatTag as u32 == WAVE_FORMAT_IEEE_FLOAT { return true; }
        if fmt.wFormatTag as u32 == WAVE_FORMAT_EXTENSIBLE && fmt.cbSize >= 22 {
            let ext = &*(fmt as *const _ as *const WAVEFORMATEXTENSIBLE);
            // WAVEFORMATEXTENSIBLE is packed; access to SubFormat has to be unaligned
            let subformat = std::ptr::addr_of!(ext.SubFormat).read_unaligned();
            return subformat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
        }
        false
    }

    unsafe fn decode_to_f32_mono(
        data: *mut u8,
        frames: usize,
        channels: usize,
        is_float: bool,
    ) -> Vec<f32> {
        if is_float {
            let slice = std::slice::from_raw_parts(data as *const f32, frames * channels);
            if channels == 1 {
                slice.to_vec()
            } else {
                crate::audio_resample::downmix_to_mono(slice, channels)
            }
        } else {
            let slice = std::slice::from_raw_parts(data as *const i16, frames * channels);
            let f32_samples: Vec<f32> = slice.iter().map(|s| *s as f32 / 32768.0).collect();
            if channels == 1 {
                f32_samples
            } else {
                crate::audio_resample::downmix_to_mono(&f32_samples, channels)
            }
        }
    }
}

#[cfg(target_os = "windows")]
pub use platform::{find_system_audio_source, SystemAudioCapture};
