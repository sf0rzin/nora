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
            _sample_rate_hint: u32,
            _sink: tokio::sync::mpsc::Sender<Vec<i16>>,
            _flag: Arc<AtomicBool>,
        ) -> Result<Self, String> {
            // TODO: Implementar WASAPI loopback funcional (Issue #14)
            Err("Windows system audio capture not yet implemented".to_string())
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
