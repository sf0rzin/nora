use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;

/// Tests the full flow: spawn fake sidecar → receive ready → receive transcripts → stop.
#[tokio::test]
async fn test_sidecar_fake_lifecycle() {
    // Find the fake sidecar script relative to CARGO_MANIFEST_DIR
    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap_or_else(|_| ".".into());
    let fake_sidecar_path = std::path::Path::new(&manifest_dir)
        .join("../sidecar/tests/fake_sidecar.py");

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
    let _stderr = child.stderr.take().unwrap();

    // Send start message
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

    // Read stdout
    let reader = BufReader::new(stdout);
    let mut lines = reader.lines();

    let mut got_ready = false;
    let mut got_final = 0;

    // Wait for ready
    for _ in 0..50 {
        if let Ok(Ok(Some(line))) = tokio::time::timeout(Duration::from_millis(200), lines.next_line()).await {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
                if json.get("type").and_then(|v| v.as_str()) == Some("ready") {
                    got_ready = true;
                    break;
                }
            }
        }
    }

    assert!(got_ready, "should receive ready message");

    // Wait for final transcripts
    let timeout = tokio::time::Duration::from_secs(5);
    let deadline = tokio::time::Instant::now() + timeout;

    while tokio::time::Instant::now() < deadline && got_final < 2 {
        if let Ok(Ok(Some(line))) = tokio::time::timeout(Duration::from_millis(500), lines.next_line()).await {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
                if json.get("type").and_then(|v| v.as_str()) == Some("final") {
                    got_final += 1;
                }
            }
        }
    }

    // Send stop
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

    // Wait for stopped
    for _ in 0..20 {
        if let Ok(Ok(Some(line))) = tokio::time::timeout(Duration::from_millis(200), lines.next_line()).await {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
                if json.get("type").and_then(|v| v.as_str()) == Some("stopped") {
                    break;
                }
            }
        }
    }

    let _ = child.wait().await;

    assert_eq!(got_final, 2, "should receive 2 final transcripts");
}
