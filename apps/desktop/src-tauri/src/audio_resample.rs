/// Resampler mono streaming por interpolação linear.
///
/// Substituiu o rubato FFT (que dava panic "divide by zero" em synchro.rs ao
/// construir 48000→16000 — matava a thread de áudio antes de abrir o mic). Pra
/// STT a interp linear é mais que suficiente (fala vive bem abaixo do Nyquist de
/// 16kHz) e é IMPOSSÍVEL panicar: nenhuma divisão por entrada do usuário.
///
/// Mantém estado entre chunks (`buf` + posição fracionária `pos`) pra continuidade
/// no fluxo de áudio em tempo real.
pub struct MonoResampler {
    src_sr: u32,
    dst_sr: u32,
    buf: Vec<f32>,
    pos: f64,
}

impl MonoResampler {
    pub fn new(src_sr: u32, dst_sr: u32) -> Result<Self, String> {
        if src_sr == 0 || dst_sr == 0 {
            return Err(format!("sample rate inválido: src={} dst={}", src_sr, dst_sr));
        }
        Ok(Self {
            src_sr,
            dst_sr,
            buf: Vec::new(),
            pos: 0.0,
        })
    }

    /// Recebe mono f32 em src_sr, retorna mono f32 em dst_sr.
    pub fn process(&mut self, input: &[f32]) -> Vec<f32> {
        if self.src_sr == self.dst_sr {
            return input.to_vec(); // bypass quando sr igual
        }

        self.buf.extend_from_slice(input);
        let ratio = self.src_sr as f64 / self.dst_sr as f64;

        let n = self.buf.len();
        let mut out = Vec::new();
        let mut pos = self.pos;

        // Precisa de buf[i] e buf[i+1] pra interpolar → para quando i+1 sai do buffer.
        while (pos as usize) + 1 < n {
            let i = pos as usize;
            let frac = pos - i as f64;
            let s = self.buf[i] as f64 * (1.0 - frac) + self.buf[i + 1] as f64 * frac;
            out.push(s as f32);
            pos += ratio;
        }

        // Descarta as amostras já consumidas, carregando a fração restante.
        let drop = (pos as usize).min(n);
        if drop > 0 {
            self.buf.drain(0..drop);
            pos -= drop as f64;
        }
        self.pos = pos;

        out
    }
}

/// Helper: F32 multi-canal interleaved → mono (média).
pub fn downmix_to_mono(interleaved: &[f32], channels: usize) -> Vec<f32> {
    if channels <= 1 {
        return interleaved.to_vec();
    }
    interleaved
        .chunks_exact(channels)
        .map(|frame| frame.iter().sum::<f32>() / channels as f32)
        .collect()
}

/// Helper: f32 [-1.0, 1.0] → i16 PCM com clamp.
pub fn f32_to_i16(samples: &[f32]) -> Vec<i16> {
    samples
        .iter()
        .map(|s| (s.clamp(-1.0, 1.0) * 32767.0) as i16)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bypass_same_rate() {
        let mut resampler = MonoResampler::new(16000, 16000).unwrap();
        let input = vec![0.5f32; 160];
        let output = resampler.process(&input);
        assert_eq!(input, output);
    }

    #[test]
    fn test_downmix_mono() {
        let interleaved = vec![0.5f32, 0.7f32, 0.3f32, 0.9f32];
        let mono = downmix_to_mono(&interleaved, 2);
        assert_eq!(mono, vec![0.6f32, 0.6f32]);
    }

    #[test]
    fn test_f32_to_i16() {
        let samples = vec![-1.0f32, 0.0f32, 1.0f32, 2.0f32];
        let i16_samples = f32_to_i16(&samples);
        assert_eq!(i16_samples, vec![-32767i16, 0i16, 32767i16, 32767i16]);
    }
}
