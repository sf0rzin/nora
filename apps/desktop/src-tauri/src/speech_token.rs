use serde::Deserialize;
use std::time::Duration;

#[derive(Debug, Deserialize, Clone)]
pub struct SpeechTokenResponse {
    pub token: String,
    pub region: String,
    #[serde(rename = "expiresAt")]
    pub expires_at: String,
}

impl SpeechTokenResponse {
    /// Computes how many seconds are left until expiration, with a 60s safety buffer.
    /// Returns None if it cannot parse expires_at.
    pub fn ttl_seconds(&self) -> Option<u64> {
        let expires = chrono::DateTime::parse_from_rfc3339(&self.expires_at).ok()?;
        let now = chrono::Utc::now();
        let diff = expires.with_timezone(&chrono::Utc) - now;
        let secs = diff.num_seconds().max(0) as u64;
        // 60s buffer to avoid a race condition
        Some(secs.saturating_sub(60))
    }
}

pub async fn fetch_speech_token(
    backend_base_url: &str,
    access_token: &str,
    region: Option<&str>,
) -> Result<SpeechTokenResponse, String> {
    let mut url = format!("{}/speech/token", backend_base_url.trim_end_matches('/'));
    if let Some(r) = region {
        url.push_str(&format!("?region={}", urlencoding::encode(r)));
    }

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client
        .post(&url)
        .bearer_auth(access_token)
        .header("Content-Length", "0")
        .send()
        .await
        .map_err(|e| format!("speech token request failed: {e}"))?;

    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(format!("speech token http {}: {}", status, body));
    }

    serde_json::from_str::<SpeechTokenResponse>(&body)
        .map_err(|e| format!("speech token parse: {e} (body: {})", body))
}
