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
        // Argument order is (input rate, output rate, chunk_size, nbr_channels, fixed), and
        // `sub_chunks` is chosen automatically as `(chunk_size / 256).max(1)` to target roughly
        // 256 frames per sub-chunk. `new_custom` takes it back if needed.
        //
        // Verified unchanged from rubato 4 to 5 by reading `synchro.rs` at 5.0.0 rather than by
        // assuming it: the signature and that expression are identical. The 5.0.0 upgrade broke
        // the build without touching this call — `rubato` moved to `audioadapter` 5 while
        // `Cargo.toml` still pinned `audioadapter-buffers` 4, so the buffers below implemented a
        // different crate's traits of the same name. The manifest comment explains why those two
        // version numbers move together.
        //
        // This is a BEHAVIOUR change at 48 kHz, not just an API one, and it is the reason the
        // two tests below were run rather than merely compiled. `chunk_in` is 960 there, so
        // rubato picks 3 sub-chunks where this code used to pass 2, and the internal FFT block
        // goes from 480 frames to 320. Delay and filter shape move with it. At 44.1 kHz
        // (`chunk_in` 882) the arithmetic lands on the same block either way. Both tests pass
        // at 48 kHz under the new value: output length within 90-100% of input/3, peak above
        // 0.1, and ~2000 zero crossings per second for a 1 kHz tone.
        //
        // Worth keeping, because every parameter here is a usize and nothing rejects a wrong
        // order at compile time: on rubato 2 this call read (.., 1, chunk_in, 2, ..) with
        // comments claiming the 1 was the channel count and the 2 the sub-chunk count. It
        // actually asked for a chunk of one frame, 960 sub-chunks and two channels. At run time
        // the resampler was configured for stereo while the buffers below are mono,
        // process_into_buffer returned WrongNumberOfInputChannels on every call, and `process`
        // broke out of the loop and returned an empty Vec — so any capture device not already
        // at 16 kHz fed silence into transcription, with nothing failing anywhere. The two
        // tests at the bottom of this file exist for exactly that: they assert the output is
        // non-empty and that a tone survives, which is what a swapped argument breaks.
        let inner = Fft::<f32>::new(
            src_sr as usize,
            dst_sr as usize,
            chunk_in, // chunk_size: frames consumed per call (~20ms of input)
            1,        // nbr_channels: mono
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

    /// Number of sign changes in the signal. For a clean sine this is proportional to its
    /// frequency, which is enough to tell "the tone survived resampling" from "the tone is gone"
    /// without pulling an FFT crate into the desktop build.
    fn zero_crossings(samples: &[f32]) -> usize {
        samples
            .windows(2)
            .filter(|w| (w[0] < 0.0) != (w[1] < 0.0))
            .count()
    }

    fn sine(freq_hz: f32, sample_rate: u32, frames: usize) -> Vec<f32> {
        (0..frames)
            .map(|n| {
                (2.0 * std::f32::consts::PI * freq_hz * n as f32 / sample_rate as f32).sin() * 0.5
            })
            .collect()
    }

    /// Regression: the resampler was constructed with chunk_size, sub_chunks and nbr_channels in
    /// the wrong order, which configured it for two channels against mono buffers. Every call
    /// failed and `process` returned an empty Vec, so a 48 kHz microphone produced no audio at
    /// all. Anything that asserts real output would have caught it.
    #[test]
    fn resamples_48k_to_16k_and_returns_audio() {
        let mut resampler = MonoResampler::new(48_000, 16_000).unwrap();
        // One second of input, fed in ~20ms blocks the way the capture callback does.
        let input = sine(1_000.0, 48_000, 48_000);
        let mut output = Vec::new();
        for block in input.chunks(960) {
            output.extend(resampler.process(block));
        }

        assert!(!output.is_empty(), "resampling produced no samples at all");

        // Three input frames per output frame. The tail that has not filled a whole chunk stays
        // buffered, so this is a range rather than an equality.
        let expected = input.len() / 3;
        assert!(
            output.len() > expected * 9 / 10 && output.len() <= expected,
            "expected roughly {} frames, got {}",
            expected,
            output.len()
        );

        // The output has to carry the signal, not zeros.
        let peak = output.iter().fold(0.0f32, |acc, s| acc.max(s.abs()));
        assert!(peak > 0.1, "output is silent (peak {})", peak);
    }

    /// A 1 kHz tone stays a 1 kHz tone: at 16 kHz it should cross zero about 2000 times per
    /// second. A resampler misconfigured on rates or channel count fails this even when it does
    /// return samples.
    #[test]
    fn resampling_preserves_the_tone() {
        let mut resampler = MonoResampler::new(48_000, 16_000).unwrap();
        let input = sine(1_000.0, 48_000, 48_000);
        let mut output = Vec::new();
        for block in input.chunks(960) {
            output.extend(resampler.process(block));
        }

        let seconds = output.len() as f32 / 16_000.0;
        let crossings_per_second = zero_crossings(&output) as f32 / seconds;
        // 2 crossings per cycle at 1 kHz. Generous bounds: this is a smoke test for "the right
        // frequency", not a filter-quality measurement.
        assert!(
            (1_800.0..=2_200.0).contains(&crossings_per_second),
            "expected about 2000 zero crossings per second, got {}",
            crossings_per_second
        );
    }

    /// The ratio the pipeline actually runs at since ADR 0045 moved the target to 24 kHz.
    ///
    /// The two tests above stay at 48k→16k on purpose: what they guard is `Fft::new`'s
    /// all-usize argument list, where a wrong order compiles and feeds silence, and they have
    /// caught that once. This one is not a duplicate of them — 48→24 is a 2:1 ratio where 48→16
    /// is 3:1, so it exercises a different sub-chunk arithmetic inside rubato, and it is the one
    /// a real 48 kHz microphone goes through today.
    #[test]
    fn resamples_48k_to_24k_the_ratio_the_pipeline_now_runs_at() {
        let mut resampler = MonoResampler::new(48_000, 24_000).unwrap();
        let input = sine(1_000.0, 48_000, 48_000);
        let mut output = Vec::new();
        for block in input.chunks(960) {
            output.extend(resampler.process(block));
        }

        assert!(!output.is_empty(), "resampling produced no samples at all");

        // Two input frames per output frame; the unfilled tail stays buffered.
        let expected = input.len() / 2;
        assert!(
            output.len() > expected * 9 / 10 && output.len() <= expected,
            "expected roughly {} frames, got {}",
            expected,
            output.len()
        );

        let peak = output.iter().fold(0.0f32, |acc, s| acc.max(s.abs()));
        assert!(peak > 0.1, "output is silent (peak {})", peak);

        // A 1 kHz tone is still 1 kHz: ~2000 zero crossings per second, whatever the rate.
        let seconds = output.len() as f32 / 24_000.0;
        let crossings_per_second = zero_crossings(&output) as f32 / seconds;
        assert!(
            (1_800.0..=2_200.0).contains(&crossings_per_second),
            "expected about 2000 zero crossings per second, got {}",
            crossings_per_second
        );
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
