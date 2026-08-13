use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWriteExt, BufReader, Lines};
use tokio::process::{Child, Command};
use tokio::task::JoinHandle;
use tokio::time::Instant;

// This test spawns a real `python3` and speaks NDJSON to it, so it is the only coverage the
// sidecar handshake has. It was also the flakiest thing in `desktop-rust`, which is a `needs`
// of `ci-gate` — main's only required status check — so a random failure here blocks an
// unrelated pull request, and "just re-run it" is the habit that hides the next real one.
//
// WHAT ACTUALLY WENT WRONG, from the logs rather than from reading the code. Run
// 31459327444 on PR #431 failed at "should receive ready message" with the suite taking
// 11.12s against 2.49s on a healthy run, and the same commit passed on re-run. The old wait
// was
//
//     for _ in 0..50 {
//         if let Ok(Ok(Some(line))) = timeout(Duration::from_millis(200), lines.next_line())
//
// so ~10 s of budget, and the extra time says it elapsed with the child alive and silent.
//
// Windows is where this bites, and the reason is VARIANCE rather than a mechanism. Six
// measured `desktop-rust` suite times on `windows-latest` (this test is the last to finish,
// so suite time is a good proxy for it):
//
//     31459327444 att.1   11.12 s   FAILED
//     31459327444 att.2    2.49 s   passed — the same commit, re-run
//     31657727812          2.96 s
//     31656616648          4.55 s
//     31658190466          6.47 s
//     31657213834         10.14 s
//
// A ~4x spread between healthy runs, against ~1.5 s on Linux. A 10 s budget sits inside
// that spread, which is the whole defect: the budget was too small for the distribution it
// had to survive.
//
// An earlier version of this comment attributed the slow runs to "a python3 interpreter
// starting while whisper.cpp builds". That is FALSE and is left recorded rather than
// deleted: cargo finishes compiling before it runs any test binary, and whisper.cpp was
// already built by the preceding `cargo check --features stt-local` step. The failing run's
// own log shows compilation ending, then `running 21 tests` at 04:51:55.71 and the failure
// at 04:52:06.83 — eleven seconds of pure test execution with nothing else building.
//
// (An earlier version of this comment blamed `Lines::next_line` for not being
// cancellation-safe. That is wrong and worth leaving written down: tokio documents
// `Lines::next_line` as cancel SAFE, because `Lines<R>` holds its `buf`/`bytes`/`read` state
// in the struct, so dropping the future leaves the partial read there and the next call
// resumes from it. The method that is NOT cancel safe is `AsyncBufReadExt::read_line`, which
// writes into a caller-owned String. Confusing the two turned "the timeout is too short" into
// a much more interesting story that happened to be false.)
//
// So the fix is a bigger budget — but expressed differently, which is the part worth keeping.
// `iterations x per-line timeout` is not a time budget at all: a line that arrives, or an EOF,
// consumes an iteration in zero elapsed time. Fifty iterations could expire in microseconds or
// in ten seconds depending on what the child did. ONE deadline over the whole lifecycle states
// the budget directly and bounds the worst case, which four independent per-phase timeouts do
// not.
//
// Three other things the old shape hid, each turning a specific failure into the same
// unhelpful assertion:
//   - stderr was dropped (`let _stderr`), so a child dying on import printed its traceback
//     into nothing. It is drained concurrently now, which also removes a deadlock: a child
//     filling the stderr pipe with nobody reading blocks on write.
//   - EOF (`Ok(Ok(None))`, the child exited) fell through the same `if let` as a timeout, so
//     a crashed child produced "should receive ready message" and no cause.
//   - `child.wait()` was unbounded, so a child that ignored `stop` hung until the harness
//     killed the run.
//
// ON THE DEADLINE, honestly: this relaxes the latency the test tolerates, from ~10 s for the
// handshake to 90 s for everything. That is the trade, and it is the right one for a test
// inside the only required check. The old budget was ~4x the FASTEST healthy Windows run and
// barely above the SLOWEST, which is how it ended up inside the distribution; a margin that
// means anything has to clear the slow tail by an order of magnitude, not by a factor.
//
// It costs nothing when the child behaves, because every wait returns as soon as its message
// arrives — the run is ~1.5 s on Linux and 2.5-10 s on Windows either way.

/// One budget for the whole exchange, not one per phase. Normal completion is ~1.5 s on
/// Linux and ~10 s on Windows, both set by the three `time.sleep(0.5)` calls in the fake
/// sidecar plus interpreter startup — not by this number.
const LIFECYCLE_BUDGET: Duration = Duration::from_secs(90);

/// Bounds the failure path itself. Reached only when something already went wrong, and a
/// child that closed stdout while still holding stderr open would otherwise hang here until
/// the job's 30-minute timeout — turning a clean panic into a burned runner.
///
/// Only the EOF/error arms call it. The deadline arms deliberately do not: a child that has
/// gone silent past the deadline is, by construction, one still holding its pipes open, so
/// draining would spend this timeout to report "still holding it". `child_state` answers the
/// question that actually matters there — alive, or dead and how.
const STDERR_DRAIN_TIMEOUT: Duration = Duration::from_secs(5);

/// Reads lines until it has seen `count` messages whose `type` is `want`.
///
/// No internal timeout: the caller bounds the whole lifecycle with a single deadline.
/// Returns `Err` on EOF, which means the child exited — terminal, and worth distinguishing
/// from "nothing has arrived yet", because the old code treated the two alike.
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
                    "stdout reached EOF after {} of {} '{}' message(s) — the child exited",
                    seen.len(),
                    count,
                    want
                ));
            }
            Err(e) => return Err(format!("error reading stdout: {e}")),
        }
    }
}

/// Drains whatever the child wrote to stderr, so a panic can name the cause instead of only
/// the symptom. Consumes the handle: the second caller gets a note rather than a hang.
async fn stderr_text(handle: &mut Option<JoinHandle<String>>) -> String {
    let Some(h) = handle.take() else {
        return "(already drained and reported above)".to_string();
    };
    match tokio::time::timeout(STDERR_DRAIN_TIMEOUT, h).await {
        Ok(Ok(s)) if s.trim().is_empty() => "(the child printed nothing on stderr)".to_string(),
        Ok(Ok(s)) => s,
        Ok(Err(e)) => format!("(the stderr reader task failed: {e})"),
        Err(_) => format!(
            "(stderr did not close within {STDERR_DRAIN_TIMEOUT:?} — the child is still holding it)"
        ),
    }
}

/// Whether the child is alive, ASKED rather than inferred. The distinction changes what a
/// reader should look at: a live child means a hang in the protocol, a dead one means a crash.
fn child_state(child: &mut Child) -> String {
    match child.try_wait() {
        Ok(None) => "the child is still running, so this is a hang, not a crash".to_string(),
        Ok(Some(status)) => format!("the child had already exited with {status}"),
        Err(e) => format!("could not determine the child's state: {e}"),
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

    // Started here so the budget covers interpreter startup, which is the part that was
    // actually running out of time.
    let deadline = Instant::now() + LIFECYCLE_BUDGET;

    let mut stdin = child.stdin.take().unwrap();
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();

    let mut stderr_handle = Some(tokio::spawn(async move {
        let mut buf = String::new();
        let mut lines = BufReader::new(stderr).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            buf.push_str(&line);
            buf.push('\n');
        }
        buf
    }));

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
    match tokio::time::timeout_at(deadline, wait_for(&mut lines, "ready", 1)).await {
        Ok(Ok(_)) => {}
        Ok(Err(e)) => panic!(
            "should receive ready message: {e}\n--- sidecar stderr ---\n{}",
            stderr_text(&mut stderr_handle).await
        ),
        Err(_) => panic!(
            "should receive ready message: nothing arrived before the {LIFECYCLE_BUDGET:?} \
             deadline — {}",
            child_state(&mut child)
        ),
    }

    // --- two final transcripts ---------------------------------------------------------
    let finals = match tokio::time::timeout_at(deadline, wait_for(&mut lines, "final", 2)).await {
        Ok(Ok(seen)) => seen,
        Ok(Err(e)) => panic!(
            "should receive 2 final transcripts: {e}\n--- sidecar stderr ---\n{}",
            stderr_text(&mut stderr_handle).await
        ),
        Err(_) => panic!(
            "should receive 2 final transcripts: fewer than 2 arrived before the \
             {LIFECYCLE_BUDGET:?} deadline — {}",
            child_state(&mut child)
        ),
    };
    // Cannot fail — `wait_for` only returns once `seen.len() >= count` and it pushes one at
    // a time. Kept deliberately: it is the original assertion, verbatim, so a reader diffing
    // against `main` can see that the guarantee did not move, only the machinery under it.
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

    // `stopped` is ASSERTED, where the old loop merely broke out on it and checked nothing —
    // so a sidecar that ignored `stop` entirely used to pass. The protocol says it answers.
    match tokio::time::timeout_at(deadline, wait_for(&mut lines, "stopped", 1)).await {
        Ok(Ok(_)) => {}
        Ok(Err(e)) => panic!(
            "the sidecar did not answer `stop` with `stopped`: {e}\n--- sidecar stderr ---\n{}",
            stderr_text(&mut stderr_handle).await
        ),
        Err(_) => panic!(
            "the sidecar did not answer `stop` with `stopped` before the {LIFECYCLE_BUDGET:?} \
             deadline — {}",
            child_state(&mut child)
        ),
    }

    // Bounded: `child.wait()` alone hangs forever if the child ignores `stop`, and
    // `kill_on_drop(true)` only helps once this function returns.
    match tokio::time::timeout_at(deadline, child.wait()).await {
        Ok(Ok(status)) => assert!(
            status.success(),
            "the fake sidecar exited with {status} after `stop`"
        ),
        Ok(Err(e)) => panic!("failed to wait for the fake sidecar: {e}"),
        Err(_) => panic!(
            "the fake sidecar did not exit within the {LIFECYCLE_BUDGET:?} deadline after `stop`"
        ),
    }
}
