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
    use windows::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
    use windows::Win32::Media::Audio::{
        eConsole, eRender, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator,
        MMDeviceEnumerator, AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_EVENTCALLBACK, AUDCLNT_STREAMFLAGS_LOOPBACK, WAVEFORMATEX,
        WAVEFORMATEXTENSIBLE, WAVE_FORMAT_PCM,
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
            // WAVEFORMATEXTENSIBLE é packed; acesso a SubFormat precisa ser unaligned
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

#[cfg(target_os = "macos")]
mod platform {
    //! macOS system-audio capture.
    //!
    //! Status (Issue #15):
    //! - Implementado: fallback BlackHole via cpal (PCM f32 → mono → 16 kHz i16).
    //!   Funciona em qualquer versão de macOS desde que o usuário tenha o driver
    //!   virtual BlackHole instalado e o esteja roteando como saída do sistema
    //!   (ou via Multi-Output Device no Audio MIDI Setup).
    //! - TODO: caminho ScreenCaptureKit (macOS 13+) sem driver virtual. Deixado
    //!   como follow-up: requer crate `screencapturekit` + bindings objc2 e
    //!   validação em hardware Apple. A infraestrutura aqui (detecção de versão,
    //!   Info.plist, README) já está pronta para receber o caminho SCK.
    //!
    //! Fluxo atual:
    //!   `find_system_audio_source()` retorna o nome do device "BlackHole" se
    //!   presente; caso contrário `None` (o caller deve mostrar instruções).
    //!   `SystemAudioCapture::start(name, ...)` abre o device com cpal, downmix
    //!   para mono, resample para 16 kHz, converte para i16 e envia ao sink.

    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
    use cpal::SampleFormat;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;

    use crate::audio_resample::{downmix_to_mono, f32_to_i16, MonoResampler};

    /// Nomes conhecidos do driver virtual BlackHole (pode aparecer com sufixo
    /// de canais, ex: "BlackHole 2ch", "BlackHole 16ch").
    const BLACKHOLE_HINTS: &[&str] = &["blackhole", "soundflower"];

    /// Detecta se o sistema é macOS 13+ (Ventura), onde ScreenCaptureKit
    /// passa a suportar captura de áudio do sistema sem driver virtual.
    /// Mantido para uso futuro pelo caminho SCK; hoje apenas registra um log.
    fn macos_supports_sck_audio() -> bool {
        // Lê via sysctlbyname("kern.osrelease") — Darwin kernel.
        // macOS 13 (Ventura) = Darwin 22.x; macOS 14 = Darwin 23.x; etc.
        // ScreenCaptureKit audio: macOS 13+.
        unsafe {
            let name = b"kern.osrelease\0";
            let mut size: libc::size_t = 0;
            if libc::sysctlbyname(
                name.as_ptr() as *const _,
                std::ptr::null_mut(),
                &mut size,
                std::ptr::null_mut(),
                0,
            ) != 0
                || size == 0
            {
                return false;
            }
            let mut buf = vec![0u8; size];
            if libc::sysctlbyname(
                name.as_ptr() as *const _,
                buf.as_mut_ptr() as *mut _,
                &mut size,
                std::ptr::null_mut(),
                0,
            ) != 0
            {
                return false;
            }
            // strip trailing NUL
            if let Some(&0) = buf.last() {
                buf.pop();
            }
            let s = String::from_utf8_lossy(&buf);
            let major: u32 = s
                .split('.')
                .next()
                .and_then(|p| p.parse().ok())
                .unwrap_or(0);
            major >= 22
        }
    }

    /// Retorna o nome do device de input que parece ser o BlackHole (ou similar),
    /// ou `None` se nenhum estiver instalado.
    pub fn find_system_audio_source() -> Option<String> {
        let host = cpal::default_host();
        let devices = host.input_devices().ok()?;
        let candidate = devices
            .filter_map(|d| d.name().ok())
            .find(|name| {
                let lower = name.to_lowercase();
                BLACKHOLE_HINTS.iter().any(|h| lower.contains(h))
            });

        #[cfg(debug_assertions)]
        match (&candidate, macos_supports_sck_audio()) {
            (Some(n), sck) => eprintln!(
                "[macos-audio] using virtual driver: {} (sck_supported={})",
                n, sck
            ),
            (None, true) => eprintln!(
                "[macos-audio] no BlackHole/Soundflower found; ScreenCaptureKit \
                 path not yet implemented (Issue #15)"
            ),
            (None, false) => eprintln!(
                "[macos-audio] no BlackHole/Soundflower found and macOS < 13; \
                 user must install BlackHole"
            ),
        }
        candidate
    }

    pub struct SystemAudioCapture {
        stop_flag: Arc<AtomicBool>,
        thread: Option<std::thread::JoinHandle<()>>,
    }

    impl SystemAudioCapture {
        pub fn start(
            source: &str,
            _sample_rate_hint: u32,
            sink: tokio::sync::mpsc::Sender<Vec<i16>>,
            flag: Arc<AtomicBool>,
        ) -> Result<Self, String> {
            // Localiza o device pelo nome fornecido (case-insensitive contains).
            let host = cpal::default_host();
            let device = host
                .input_devices()
                .map_err(|e| format!("cpal input_devices: {}", e))?
                .find(|d| {
                    d.name()
                        .map(|n| n.to_lowercase().contains(&source.to_lowercase()))
                        .unwrap_or(false)
                })
                .ok_or_else(|| {
                    if macos_supports_sck_audio() {
                        format!(
                            "Device de áudio do sistema '{}' não encontrado. \
                             Instale o BlackHole (https://existential.audio/blackhole/) \
                             e roteie a saída do sistema através dele. \
                             Suporte nativo via ScreenCaptureKit está em desenvolvimento (#15).",
                            source
                        )
                    } else {
                        format!(
                            "Device '{}' não encontrado. Em macOS < 13, a captura \
                             de áudio do sistema requer instalar o BlackHole \
                             (https://existential.audio/blackhole/).",
                            source
                        )
                    }
                })?;

            let device_name = device.name().unwrap_or_else(|_| source.to_string());

            // Escolhe um config F32 com sample rate razoável.
            let supported: Vec<_> = device
                .supported_input_configs()
                .map_err(|e| format!("supported_input_configs: {}", e))?
                .filter(|c| c.sample_format() == SampleFormat::F32)
                .collect();
            if supported.is_empty() {
                return Err(format!(
                    "Device '{}' não expõe configuração F32 suportada",
                    device_name
                ));
            }
            let chosen = supported
                .iter()
                .find(|c| c.min_sample_rate().0 <= 48000 && c.max_sample_rate().0 >= 48000)
                .map(|c| c.with_sample_rate(cpal::SampleRate(48000)))
                .unwrap_or_else(|| supported[0].with_max_sample_rate());

            let config: cpal::StreamConfig = chosen.config();
            let src_sr = config.sample_rate.0;
            let channels = config.channels as usize;

            #[cfg(debug_assertions)]
            eprintln!(
                "[macos-audio] opening '{}' @ {} Hz / {} ch",
                device_name, src_sr, channels
            );

            let stop_flag = flag.clone();
            // cpal::Stream é !Send: precisa viver em thread dedicada.
            let thread = std::thread::Builder::new()
                .name("nora-macos-audio".into())
                .spawn(move || {
                    let mut resampler = match MonoResampler::new(src_sr, 16_000) {
                        Ok(r) => r,
                        Err(e) => {
                            #[cfg(debug_assertions)]
                            eprintln!("[macos-audio] resampler init failed: {}", e);
                            return;
                        }
                    };

                    let sink_for_cb = sink.clone();
                    let err_fn = |e| {
                        #[cfg(debug_assertions)]
                        eprintln!("[macos-audio] cpal stream error: {}", e);
                        let _ = e;
                    };

                    let stream_result = device.build_input_stream(
                        &config,
                        move |data: &[f32], _: &cpal::InputCallbackInfo| {
                            let mono = if channels == 1 {
                                data.to_vec()
                            } else {
                                downmix_to_mono(data, channels)
                            };
                            let resampled = resampler.process(&mono);
                            if !resampled.is_empty() {
                                let pcm = f32_to_i16(&resampled);
                                let _ = sink_for_cb.try_send(pcm);
                            }
                        },
                        err_fn,
                        None,
                    );

                    let stream = match stream_result {
                        Ok(s) => s,
                        Err(e) => {
                            #[cfg(debug_assertions)]
                            eprintln!("[macos-audio] build_input_stream failed: {}", e);
                            return;
                        }
                    };

                    if let Err(e) = stream.play() {
                        #[cfg(debug_assertions)]
                        eprintln!("[macos-audio] stream.play failed: {}", e);
                        return;
                    }

                    while flag.load(Ordering::SeqCst) {
                        std::thread::sleep(std::time::Duration::from_millis(50));
                    }

                    let _ = stream.pause();
                    drop(stream);
                })
                .map_err(|e| format!("spawn macos audio thread: {}", e))?;

            Ok(Self {
                stop_flag,
                thread: Some(thread),
            })
        }

        pub fn stop(&mut self) {
            self.stop_flag.store(false, Ordering::SeqCst);
            if let Some(t) = self.thread.take() {
                let _ = t.join();
            }
        }
    }

    impl Drop for SystemAudioCapture {
        fn drop(&mut self) {
            self.stop();
        }
    }
}

/// Verifica se o BlackHole (ou Soundflower) está instalado no macOS.
/// Retorna true se encontrado, false caso contrário.
#[cfg(target_os = "macos")]
pub fn is_blackhole_installed() -> bool {
    platform::find_system_audio_source().is_some()
}

pub use platform::{find_system_audio_source, SystemAudioCapture};
