use rubato::{Fft, FixedSync, Resampler};
use audioadapter_buffers::owned::InterleavedOwned;

pub struct MonoResampler {
    inner: Option<Fft<f32>>,
    #[allow(dead_code)]
    src_sr: u32,
    #[allow(dead_code)]
    dst_sr: u32,
    leftover: Vec<f32>,
}

impl MonoResampler {
    pub fn new(src_sr: u32, dst_sr: u32) -> Result<Self, String> {
        if src_sr == dst_sr {
            return Ok(Self {
                inner: None,
                src_sr,
                dst_sr,
                leftover: Vec::new(),
            });
        }
        let chunk_in = (src_sr as usize / 50).max(64); // ~20ms
        let inner = Fft::<f32>::new(
            src_sr as usize,
            dst_sr as usize,
            1, // 1 channel (mono)
            chunk_in,
            2, // subchunks
            FixedSync::Input,
        )
        .map_err(|e| format!("rubato init: {}", e))?;
        Ok(Self {
            inner: Some(inner),
            src_sr,
            dst_sr,
            leftover: Vec::new(),
        })
    }

    /// Receives mono f32 at src_sr, returns mono f32 at dst_sr.
    pub fn process(&mut self, input: &[f32]) -> Vec<f32> {
        let Some(resampler) = self.inner.as_mut() else {
            return input.to_vec(); // bypass when sr is equal
        };
        self.leftover.extend_from_slice(input);
        let chunk = resampler.input_frames_next();
        let mut out = Vec::new();
        while self.leftover.len() >= chunk {
            let block: Vec<f32> = self.leftover.drain(..chunk).collect();
            let input_buf = match InterleavedOwned::new_from(block, 1, chunk) {
                Ok(buf) => buf,
                Err(e) => {
                    eprintln!("[audio_resample] InterleavedOwned::new_from failed: {}", e);
                    break;
                }
            };
            let mut output_buf = InterleavedOwned::new(0.0f32, 1, resampler.output_frames_next());
            match resampler.process_into_buffer(
                &input_buf,
                &mut output_buf,
                None,
            ) {
                Ok((_input_frames, output_frames)) => {
                    // Extract data from output buffer
                    let data = output_buf.take_data();
                    out.extend_from_slice(&data[..output_frames]);
                }
                Err(e) => {
                    eprintln!("[audio_resample] Resample error: {}", e);
                    break;
                }
            }
        }
        out
    }
}

/// Helper: multi-channel interleaved F32 → mono (average).
pub fn downmix_to_mono(interleaved: &[f32], channels: usize) -> Vec<f32> {
    if channels <= 1 {
        return interleaved.to_vec();
    }
    interleaved
        .chunks_exact(channels)
        .map(|frame| frame.iter().sum::<f32>() / channels as f32)
        .collect()
}

/// Helper: f32 [-1.0, 1.0] → i16 PCM with clamp.
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
