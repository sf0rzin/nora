#[cfg(target_os = "linux")]
mod platform {
    use std::io::Read;
    use std::os::unix::io::AsRawFd;
    use std::process::{Command, Stdio};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};

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
            sample_rate: u32,
            sys_buf: Arc<Mutex<Vec<f32>>>,
            flag: Arc<AtomicBool>,
        ) -> Option<Self> {
            let mut child = Command::new("parecord")
                .args([
                    "--device", source,
                    "--format", "float32le",
                    "--rate", &sample_rate.to_string(),
                    "--channels", "1",
                    "--raw",
                ])
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()
                .ok()?;

            // Kill child automatically if parent dies (no orphans on crash)
            unsafe {
                libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL);
            }

            let mut stdout = child.stdout.take()?;

            // Set stdout to non-blocking so the read thread can exit promptly
            unsafe {
                let fd = stdout.as_raw_fd();
                let flags = libc::fcntl(fd, libc::F_GETFL);
                if flags >= 0 {
                    libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
                }
            }

            std::thread::spawn(move || {
                let mut read_buf = [0u8; 6400];
                while flag.load(Ordering::SeqCst) {
                    match stdout.read(&mut read_buf) {
                        Ok(0) => break,
                        Ok(n) => {
                            if n % 4 != 0 {
                                continue;
                            }
                            let samples: Vec<f32> = read_buf[..n]
                                .chunks_exact(4)
                                .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                                .collect();
                            if let Ok(mut buf) = sys_buf.lock() {
                                buf.extend_from_slice(&samples);
                                let max_samples = (sample_rate as usize / 10) * 20;
                                if buf.len() > max_samples {
                                    let excess = buf.len() - max_samples;
                                    buf.drain(..excess);
                                }
                            }
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(std::time::Duration::from_millis(10));
                        }
                        Err(_) => break,
                    }
                }
            });

            Some(Self { child })
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
    use std::sync::{Arc, Mutex};

    pub fn find_system_audio_source() -> Option<String> {
        Some("wasapi_loopback".to_string())
    }

    pub struct SystemAudioCapture {
        thread_handle: Option<std::thread::JoinHandle<()>>,
    }

    impl SystemAudioCapture {
        pub fn start(
            _source: &str,
            sample_rate: u32,
            sys_buf: Arc<Mutex<Vec<f32>>>,
            flag: Arc<AtomicBool>,
        ) -> Option<Self> {
            use windows::Win32::Media::Audio::*;
            use windows::Win32::System::Com::*;
            use windows::core::GUID;

            unsafe {
                let _ = CoInitializeEx(None, COINIT_MULTITHREADED).ok()?;

                let enumerator: IMMDeviceEnumerator =
                    windows::core::Factory::create_instance(&IMMDeviceEnumerator)?
                        .ok()?;

                let device = enumerator.GetDefaultAudioEndpoint(eRender, eConsole).ok()?;

                let mut audio_client: IAudioClient = device.Activate(
                    &IAudioClient::IID as *const _ as *const _,
                    CLSCTX_ALL,
                    None,
                ).ok()?;

                // TODO: query native mix format instead of hardcoding
                let format = WAVEFORMATEX {
                    wFormatTag: 3, // IEEE_FLOAT
                    nChannels: 1,
                    nSamplesPerSec: sample_rate,
                    nAvgBytesPerSec: sample_rate * 4,
                    nBlockAlign: 4,
                    wBitsPerSample: 32,
                    cbSize: 0,
                };

                audio_client.Initialize(
                    AUDCLNT_SHAREMODE_SHARED,
                    AUDCLNT_STREAMFLAGS_LOOPBACK,
                    10000000,
                    0,
                    &format,
                    GUID::zeroed(),
                ).ok()?;

                let capture_client: IAudioCaptureClient = audio_client.GetService(&IAudioCaptureClient::IID as *const _ as *const _).ok()?;
                audio_client.Start().ok()?;

                let flag_clone = flag.clone();
                let sr = sample_rate;
                let handle = std::thread::spawn(move || {
                    loop {
                        if !flag_clone.load(Ordering::SeqCst) {
                            break;
                        }
                        std::thread::sleep(std::time::Duration::from_millis(10));

                        let _frame_count = unsafe {
                            match capture_client.GetNextPacketSize() {
                                Ok(0) => continue,
                                Ok(_) => {},
                                Err(_) => break,
                            }

                            let (data, frames, _flags) = match capture_client.GetBuffer() {
                                Ok(result) => result,
                                Err(_) => continue,
                            };

                            if frames > 0 {
                                let slice = std::slice::from_raw_parts(data as *const f32, frames as usize);
                                if let Ok(mut buf) = sys_buf.lock() {
                                    buf.extend_from_slice(slice);
                                    let max_samples = (sr as usize / 10) * 20;
                                    if buf.len() > max_samples {
                                        let excess = buf.len() - max_samples;
                                        buf.drain(..excess);
                                    }
                                }
                            }

                            let _ = capture_client.ReleaseBuffer(frames);
                            frames
                        };
                    }
                    #[cfg(debug_assertions)]
                    eprintln!("[system-audio] wasapi loopback thread ending");
                });

                Some(Self {
                    thread_handle: Some(handle),
                })
            }
        }

        pub fn stop(&mut self) {
            if let Some(handle) = self.thread_handle.take() {
                let _ = handle.join();
            }
        }
    }
}

#[cfg(target_os = "macos")]
mod platform {
    use std::sync::{Arc, Mutex};
    use std::sync::atomic::{AtomicBool, Ordering};

    pub fn find_system_audio_source() -> Option<String> {
        #[cfg(debug_assertions)]
        eprintln!("[system-audio] macOS system audio capture requires a virtual audio driver (e.g., BlackHole)");
        None
    }

    pub struct SystemAudioCapture;

    impl SystemAudioCapture {
        pub fn start(
            _source: &str,
            _sample_rate: u32,
            _sys_buf: Arc<Mutex<Vec<f32>>>,
            _flag: Arc<AtomicBool>,
        ) -> Option<Self> {
            None
        }

        pub fn stop(&mut self) {}
    }
}

pub use platform::{find_system_audio_source, SystemAudioCapture};
