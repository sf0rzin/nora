use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
use tokio::sync::mpsc;

/// Testa o fluxo completo: spawn sidecar fake → recebe ready → recebe transcripts → stop.
#[tokio::test]
async fn test_sidecar_fake_lifecycle() {
    let mut child = Command::new("python3")
        .arg("sidecar/tests/fake_sidecar.py")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("failed to spawn fake sidecar");

    let mut stdin = child.stdin.take().unwrap();
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();

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

    while let Ok(Some(line)) = tokio::time::timeout(Duration::from_secs(5), lines.next_line()).await
    {
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
            match json.get("type").and_then(|v| v.as_str()) {
                Some("ready") => got_ready = true,
                Some("final") => got_final += 1,
                Some("stopped") => break,
                _ => {}
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

    let _ = child.wait().await;

    assert!(got_ready, "should receive ready message");
    assert_eq!(got_final, 2, "should receive 2 final transcripts");
}
