// Teste isolado da captura de microfone (replica a lógica de audio_capture.rs)
// pra debugar RÁPIDO sem rebuildar o app inteiro. Num binário standalone, um
// panic do cpal/rubato APARECE no console — diferente do app, onde a thread
// morre calada.
//
//   cargo run --example mic_capture_test            # device padrão
//   cargo run --example mic_capture_test Brio       # primeiro device que casa "Brio"
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::SampleFormat;
use nora_desktop_lib::audio_resample::{downmix_to_mono, f32_to_i16, MonoResampler};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

fn find_best_config(device: &cpal::Device) -> cpal::SupportedStreamConfig {
    let ranges: Vec<_> = device.supported_input_configs().unwrap().collect();
    let in_range = |r: &cpal::SupportedStreamConfigRange, t: u32| {
        r.min_sample_rate().0 <= t && r.max_sample_rate().0 >= t
    };
    let is_pcm = |r: &cpal::SupportedStreamConfigRange| {
        matches!(r.sample_format(), SampleFormat::F32 | SampleFormat::I16 | SampleFormat::U16)
    };
    for t in [16000u32, 48000u32] {
        if let Some(r) = ranges.iter().find(|r| r.sample_format() == SampleFormat::F32 && in_range(r, t)) {
            return r.with_sample_rate(cpal::SampleRate(t));
        }
        if let Some(r) = ranges.iter().find(|r| is_pcm(r) && in_range(r, t)) {
            return r.with_sample_rate(cpal::SampleRate(t));
        }
    }
    ranges.into_iter().find(|r| is_pcm(r)).unwrap().with_max_sample_rate()
}

fn main() {
    let host = cpal::default_host();

    println!("=== input devices ===");
    for d in host.input_devices().unwrap() {
        println!("  - {}", d.name().unwrap_or_default());
    }

    let want = std::env::args().nth(1);
    let device = match &want {
        Some(name) => host
            .input_devices()
            .unwrap()
            .find(|d| d.name().map(|n| n.contains(name.as_str())).unwrap_or(false))
            .unwrap_or_else(|| panic!("device contendo '{}' nao encontrado", name)),
        None => host.default_input_device().expect("sem default input device"),
    };
    println!("\n>> Usando device: {}", device.name().unwrap());

    println!("=== supported input configs ===");
    for r in device.supported_input_configs().unwrap() {
        println!(
            "  fmt={:?} ch={} sr={}..{}",
            r.sample_format(),
            r.channels(),
            r.min_sample_rate().0,
            r.max_sample_rate().0
        );
    }

    let supported = find_best_config(&device);
    let sample_format = supported.sample_format();
    let config: cpal::StreamConfig = supported.config();
    let sr = config.sample_rate.0;
    let ch = config.channels as usize;
    println!("\n>> Escolhido: fmt={:?} sr={} ch={}", sample_format, sr, ch);

    println!(">> Criando MonoResampler({} -> 16000)...", sr);
    let resampler = Arc::new(Mutex::new(MonoResampler::new(sr, 16000).expect("MonoResampler::new falhou")));
    println!("   OK");

    let chunk_size = (sr as usize / 10) * ch;
    let mic_buf: Arc<Mutex<Vec<f32>>> = Arc::new(Mutex::new(Vec::with_capacity(chunk_size * 2)));
    let raw_count = Arc::new(AtomicUsize::new(0));
    let out_count = Arc::new(AtomicUsize::new(0));
    let peak = Arc::new(Mutex::new(0.0f32));

    println!(">> build_input_stream...");
    let rc = raw_count.clone();
    let oc = out_count.clone();
    let pk = peak.clone();
    let buf = mic_buf.clone();
    let rs = resampler.clone();
    let err_fn = |e| eprintln!("!! STREAM ERROR: {}", e);

    let stream = match sample_format {
        SampleFormat::F32 => device.build_input_stream(
            &config,
            move |data: &[f32], _: &_| {
                rc.fetch_add(data.len(), Ordering::Relaxed);
                let mut p = pk.lock().unwrap();
                for &s in data {
                    if s.abs() > *p {
                        *p = s.abs();
                    }
                }
                drop(p);
                let mut b = buf.lock().unwrap();
                b.extend_from_slice(data);
                while b.len() >= chunk_size {
                    let chunk: Vec<f32> = b.drain(..chunk_size).collect();
                    let mono = downmix_to_mono(&chunk, ch);
                    let resampled = rs.lock().unwrap().process(&mono);
                    let i16s = f32_to_i16(&resampled);
                    oc.fetch_add(i16s.len(), Ordering::Relaxed);
                }
            },
            err_fn,
            None,
        ),
        other => panic!("formato nao tratado no teste: {:?}", other),
    }
    .expect("build_input_stream falhou");
    println!("   build OK");

    stream.play().expect("stream.play() falhou");
    println!(">> stream.play() OK — o MIC DEVE estar ativo agora (checa o indicador do Windows).");
    println!(">> Capturando 6s (pode falar)...");
    std::thread::sleep(std::time::Duration::from_secs(6));

    let raw = raw_count.load(Ordering::Relaxed);
    let out = out_count.load(Ordering::Relaxed);
    let pkv = *peak.lock().unwrap();
    println!("\n=== RESULTADO ===");
    println!("samples crus recebidos: {}", raw);
    println!("samples 16k mono saída: {}", out);
    println!("pico (0..1):            {:.4}", pkv);
    if raw > 0 {
        println!(">> SUCESSO: o mic abriu e produziu áudio. (pico>0 = captou som)");
    } else {
        println!(">> FALHA: nenhum sample — o callback nunca disparou.");
    }
}
