use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWriteExt, BufReader, Lines};
use tokio::process::Command;

// This test spawns a real `python3` and talks NDJSON to it, so it is the only coverage the
// sidecar handshake has. It also used to be the flakiest thing in `desktop-rust`, which is a
// `needs` of `ci-gate` — main's only required status check — so a random failure here blocks
// an unrelated pull request. It failed on PR #431 (run 31459327444, "should receive ready
// message") and the SAME commit passed on a re-run of the same job.
//
// THE ROOT CAUSE WAS NOT SLOWNESS, though slowness is what made it visible. The old loop was:
//
//     for _ in 0..50 {
//         if let Ok(Ok(Some(line))) = timeout(Duration::from_millis(200), lines.next_line()).await
//
// `tokio::io::Lines::next_line` is documented as NOT cancellation-safe: dropping the future
// mid-read discards whatever it had already buffered. Every one of those 200 ms timeouts drops
// it. So on a runner slow enough to split a line across the boundary — a Windows runner
// building whisper.cpp in another job, say — the `ready` message could be written by the child,
// partially read, and then thrown away. No number of remaining iterations recovers it, which is
// why "just re-run it" worked and why raising the iteration count would not have fixed it.
//
// The shape below has ONE timeout, around the whole wait, so `next_line` is only ever cancelled
// at the point where the test is already failing. The deadlines are deliberately generous: they
// cost nothing when the child behaves, because every wait returns as soon as its message
// arrives. A test that takes 400 ms normally and tolerates 60 s under load is not slow, it is
// unhurried, and that is the difference between a deadline and a sleep.
//
// Three other things the old version hid, all of which turned a specific failure into the same
// unhelpful assertion:
//   - stderr was dropped on the floor (`let _stderr`), so a child that died on import printed
//     its traceback into nothing.
//   - `Ok(Ok(None))` (EOF — the child exited) was indistinguishable from a timeout, so a dead
//     child was waited on for the full ten seconds and then reported as "should receive ready".
//   - `child.wait()` was unbounded, so a child that ignored `stop` hung until the harness
//     killed the whole run.

/// Generous on purpose — see the note above. Normal completion is ~1.5 s, set by the
/// `time.sleep(0.5)` calls in the fake sidecar, not by this number.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(60);

/// The fake sidecar sleeps 0.5 s between each emission, so two `final`s arrive ~1.5 s after
/// `ready`. This bounds the whole exchange, not each line.
const TRANSCRIPT_TIMEOUT: Duration = Duration::from_secs(60);

/// Reads lines until it has seen `count` messages whose `type` is `want`.
///
/// No internal timeout, deliberately: the caller wraps the entire call in a single
/// `tokio::time::timeout`, so `next_line` is never dropped mid-line while the test can still
/// succeed. Returns `Err` on EOF, which means the child exited — a case worth distinguishing
/// from "nothing arrived yet", because it is terminal and the old code treated the two alike.
async fn wait_for<R: AsyncBufRead + Unpin>(
    lines: &mut Lines<R>,
    want: &str,
    count: usize,
) -> Result<Vec<String>, String> {
    let mut seen = Vec::new();
    loop {
        match lines.next_line().await {
            Ok(Some(line)) => {
                if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
                    if json.get("type").and_then(|v| v.as_str()) == Some(want) {
                        seen.push(line);
                        if seen.len() >= count {
                            return Ok(seen);
                        }
                    }
                }
            }
            Ok(None) => {
                return Err(format!(
                    "the sidecar's stdout reached EOF after {} of {} '{}' message(s) — the child exited",
                    seen.len(),
                    count,
                    want
                ));
            }
            Err(e) => return Err(format!("error reading the sidecar's stdout: {e}")),
        }
    }
}

/// Tests the full flow: spawn fake sidecar -> receive ready -> receive transcripts -> stop.
#[tokio::test]
async fn test_sidecar_fake_lifecycle() {
    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap_or_else(|_| ".".into());
    let fake_sidecar_path =
        std::path::Path::new(&manifest_dir).join("../sidecar/tests/fake_sidecar.py");

    let mut child = Command::new("python3")
        .arg(&fake_sidecar_path)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .expect("failed to spawn fake sidecar");

    let mut stdin = child.stdin.take().unwrap();
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();

    // Drained concurrently for two reasons. It is what makes a failing child say WHY it
    // failed instead of just "should receive ready message" — and a child that fills the
    // stderr pipe while nobody reads it blocks on write, which would be a deadlock this test
    // reports as a timeout.
    let stderr_handle = tokio::spawn(async move {
        let mut buf = String::new();
        let mut lines = BufReader::new(stderr).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            buf.push_str(&line);
            buf.push('\n');
        }
        buf
    });

    let start_msg = serde_json::json!({
        "v": 1,
        "type": "start",
        "session_id": "test-session",
        "azure_region": "eastus",
        "auth_token": "fake-token",
        "language": "pt-BR",
        "sample_rate": 16000,
        "channels": 1,
        "speakers_hint": 2,
    });
    stdin
        .write_all(format!("{}\n", start_msg).as_bytes())
        .await
        .unwrap();
    stdin.flush().await.unwrap();

    let mut lines = BufReader::new(stdout).lines();

    // --- ready -------------------------------------------------------------------------
    match tokio::time::timeout(HANDSHAKE_TIMEOUT, wait_for(&mut lines, "ready", 1)).await {
        Ok(Ok(_)) => {}
        Ok(Err(e)) => {
            let err = stderr_handle.await.unwrap_or_default();
            panic!("should receive ready message: {e}\n--- sidecar stderr ---\n{err}");
        }
        Err(_) => panic!(
            "should receive ready message: nothing arrived within {:?}. The child was still \
             running, so this is a hang rather than a crash.",
            HANDSHAKE_TIMEOUT
        ),
    }

    // --- two final transcripts ---------------------------------------------------------
    // The assertion is unchanged from the original: exactly two `final` messages. What
    // changed is that falling short now says how many arrived and whether the child died.
    let transcripts = tokio::time::timeout(TRANSCRIPT_TIMEOUT, wait_for(&mut lines, "final", 2));
    let finals = match transcripts.await {
        Ok(Ok(seen)) => seen,
        Ok(Err(e)) => {
            let err = stderr_handle.await.unwrap_or_default();
            panic!("should receive 2 final transcripts: {e}\n--- sidecar stderr ---\n{err}");
        }
        Err(_) => panic!(
            "should receive 2 final transcripts: fewer than 2 arrived within {:?}",
            TRANSCRIPT_TIMEOUT
        ),
    };
    assert_eq!(finals.len(), 2, "should receive 2 final transcripts");

    // --- stop --------------------------------------------------------------------------
    let stop_msg = serde_json::json!({
        "v": 1,
        "type": "stop",
        "session_id": "test-session",
    });
    stdin
        .write_all(format!("{}\n", stop_msg).as_bytes())
        .await
        .unwrap();
    stdin.flush().await.unwrap();

    // `stopped` is now asserted rather than best-effort. The old loop broke out on it and
    // checked nothing, so the sidecar could have ignored `stop` entirely and the test passed.
    // The protocol says it answers; this is the only place that can hold it to that.
    tokio::time::timeout(HANDSHAKE_TIMEOUT, wait_for(&mut lines, "stopped", 1))
        .await
        .expect("the sidecar did not answer `stop` with `stopped` in time")
        .expect("the sidecar did not answer `stop` with `stopped`");

    // Bounded: `child.wait()` on its own hangs forever if the child ignores `stop`, and
    // `kill_on_drop(true)` only helps once this function returns.
    match tokio::time::timeout(HANDSHAKE_TIMEOUT, child.wait()).await {
        Ok(Ok(status)) => assert!(
            status.success(),
            "the fake sidecar exited with {status} after `stop`"
        ),
        Ok(Err(e)) => panic!("failed to wait for the fake sidecar: {e}"),
        Err(_) => panic!("the fake sidecar did not exit after `stop`"),
    }
}
