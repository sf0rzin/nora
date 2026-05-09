use serde::Deserialize;
use std::time::Duration;

#[derive(Debug, Deserialize, Clone)]
pub struct SpeechTokenResponse {
    pub token: String,
    pub region: String,
    #[serde(rename = "expiresAt")]
    pub expires_at: String,
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
