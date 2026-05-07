#[cfg(target_os = "linux")]
mod platform {
    use std::io::Read;
    use std::os::unix::io::AsRawFd;
    use std::os::unix::process::CommandExt;
    use std::process::{Command, Stdio};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;

    pub fn find_system_audio_source() -> Option<String> {
        let output = Command::new("pactl")
            .args(["list", "short", "sources"])
            .output()
            .ok()?;

        if !output.status.success() {
            #[cfg(debug_assertions)]
            eprintln!("[system-audio] pactl failed");
            return None;
        }

        let stdout = String::from_utf8_lossy(&output.stdout);

        let default_sink = Command::new("pactl")
            .args(["get-default-sink"])
            .output()
            .ok()
            .and_then(|o| {
                if o.status.success() {
                    let name = String::from_utf8_lossy(&o.stdout).trim().to_string();
                    if !name.is_empty() {
                        return Some(name);
                    }
                }
                None
            });

        if let Some(ref sink) = default_sink {
            let monitor_name = format!("{}.monitor", sink);
            for line in stdout.lines() {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 && parts[1] == monitor_name {
                    #[cfg(debug_assertions)]
                    eprintln!("[system-audio] using default sink monitor: {}", monitor_name);
                    return Some(monitor_name);
                }
            }
        }

        for line in stdout.lines() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 4 {
                let source_name = parts[1];
                let state = parts.get(3).unwrap_or(&"");
                if source_name.to_lowercase().contains("monitor") && *state == "RUNNING" {
                    #[cfg(debug_assertions)]
                    eprintln!("[system-audio] found running monitor: {}", source_name);
                    return Some(source_name.to_string());
                }
            }
        }

        for line in stdout.lines() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 && parts[1].to_lowercase().contains("monitor") {
                #[cfg(debug_assertions)]
                eprintln!("[system-audio] found monitor (fallback): {}", parts[1]);
                return Some(parts[1].to_string());
            }
        }

        None
    }

    pub struct SystemAudioCapture {
        child: std::process::Child,
    }

    impl SystemAudioCapture {
        pub fn start(
            source: &str,
            _sample_rate_hint: u32,
            sink: tokio::sync::mpsc::Sender<Vec<i16>>,
            flag: Arc<AtomicBool>,
        ) -> Result<Self, String> {
            let mut cmd = Command::new("parecord");
            cmd.args([
                "--device", source,
                "--format=s16le",
                "--rate=16000",
                "--channels=1",
                "--raw",
            ])
            .stdout(Stdio::piped())
            .stderr(Stdio::null());

            // prctl inside pre_exec (runs in child process before exec)
            unsafe {
                cmd.pre_exec(|| {
                    libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL);
                    Ok(())
                });
            }

            let mut child = cmd.spawn()
                .map_err(|e| format!("spawn parecord: {}", e))?;

            let mut stdout = child.stdout.take()
                .ok_or("no stdout")?;

            // Set stdout to non-blocking
            unsafe {
                let fd = stdout.as_raw_fd();
                let flags = libc::fcntl(fd, libc::F_GETFL);
                if flags >= 0 {
                    libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
                }
            }

            std::thread::spawn(move || {
                let mut read_buf = [0u8; 6400]; // 3200 samples i16 = 200ms a 16kHz mono
                while flag.load(Ordering::SeqCst) {
                    match stdout.read(&mut read_buf) {
                        Ok(0) => break,
                        Ok(n) => {
                            if n % 2 != 0 {
                                continue;
                            }
                            let samples: Vec<i16> = read_buf[..n]
                                .chunks_exact(2)
                                .map(|c| i16::from_le_bytes([c[0], c[1]]))
                                .collect();
                            let _ = sink.try_send(samples);
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(std::time::Duration::from_millis(10));
                        }
                        Err(_) => break,
                    }
                }
            });

            Ok(Self { child })
        }

        pub fn stop(&mut self) {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }

    impl Drop for SystemAudioCapture {
        fn drop(&mut self) {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }
}

#[cfg(target_os = "windows")]
mod platform {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use windows::core::{Interface, GUID};
    use windows::Win32::Foundation::{CloseHandle, HANDLE, WAIT_OBJECT_0};
    use windows::Win32::Media::Audio::{
        eConsole, eRender, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator,
        MMDeviceEnumerator, AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_EVENTCALLBACK, AUDCLNT_STREAMFLAGS_LOOPBACK, WAVEFORMATEX,
        WAVEFORMATEXTENSIBLE, WAVE_FORMAT_EXTENSIBLE, WAVE_FORMAT_IEEE_FLOAT,
        WAVE_FORMAT_PCM,
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

        let mut resampler = crate::audio_resample::MonoResampler::new(src_sr, 16000).unwrap();

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
            return ext.SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
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

#[cfg(target_os = "macos")]
mod platform {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};

    pub fn find_system_audio_source() -> Option<String> {
        #[cfg(debug_assertions)]
        eprintln!("[system-audio] macOS system audio capture requires a virtual audio driver (e.g., BlackHole)");
        None
    }

    pub struct SystemAudioCapture;

    impl SystemAudioCapture {
        pub fn start(
            _source: &str,
            _sample_rate_hint: u32,
            _sink: tokio::sync::mpsc::Sender<Vec<i16>>,
            _flag: Arc<AtomicBool>,
        ) -> Result<Self, String> {
            Err("macOS system audio capture not yet implemented".to_string())
        }

        pub fn stop(&mut self) {}
    }
}

pub use platform::{find_system_audio_source, SystemAudioCapture};
