//! Fetches the ephemeral realtime transcription session from the NORA backend.
//!
//! This is the desktop half of ADR 0039 §Decision 4: the provider account key lives on the server,
//! this client asks for a short-lived credential, and the audio then goes straight from here to the
//! provider. **If the OpenAI key ever appears in `apps/desktop/.env` "just to test", the whole
//! design is gone** — test against a local API instead.
//!
//! ## Which credential authenticates the request
//!
//! `auth_bridge::web_session_jwt`, the session cookie read out of the remote `main` window, the
//! same way `commands.rs::upload_meeting` and `live_analysis.rs` already do it.
//!
//! It is NOT `http_proxy.rs`, and that is a decision rather than an omission. `http_proxy` signs
//! with the keyring's `access-token`, and measured on this tree that path has neither end: the
//! login form that used to write the secret was deleted with the local UI (PR #465), and nothing
//! reads it either — `apiClient.request` has no live caller, `meetings.ts`'s three wrappers have no
//! callers, and `uploadTranscript` goes through `invoke("upload_meeting")`. Building a new feature
//! on a path with no producer and no consumer would have made a dead branch look alive.
//!
//! ## This module is the ONLY thing between a recording and a paid provider
//!
//! It is called once per track at start, and once more per reconnection. Every call is a fresh
//! authorization check on the backend and a fresh telemetry row.

use serde::Deserialize;
use std::time::Duration;
use tauri::AppHandle;

/// Slack applied before treating a credential as usable.
///
/// Carried over from the deleted `speech_token.rs`, and for a narrower purpose. There, the 60 s
/// buffer fed an 8-minute renewal timer; here **there is no renewal timer at all**, because the
/// credential's expiry governs how long it can OPEN a connection, not how long an open session
/// lives (ADR 0045 §3). What survives is the race the buffer guarded against: a credential handed
/// over with two seconds left is one that will be refused between here and the handshake, and
/// finding that out as a connect failure is worse than asking for another one.
const EXPIRY_SLACK_SECS: i64 = 60;

/// Timeout of the mint call. Short on purpose: it sits between the user pressing record and the
/// recording actually starting, and a backend that is not answering in five seconds is not going
/// to answer in thirty.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(8);

/// One minted session, as the backend describes it.
///
/// Everything except `client_secret` is configuration the server chose, echoed here so this client
/// does not hardcode a provider endpoint, model or audio format. That is what makes a provider
/// rename an environment variable on the server instead of a desktop release.
#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RealtimeSession {
    pub client_secret: String,
    /// RFC 3339. When the secret stops being usable to OPEN a connection.
    pub expires_at: String,
    pub websocket_url: String,
    pub provider: String,
    pub model: String,
    pub language: String,
    pub audio_format: String,
    pub sample_rate: u32,
}

/// Hand-written so `{:?}` cannot publish the credential.
///
/// `#[derive(Debug)]` would print every field, and a single `eprintln!("{:?}", session)` added
/// later while debugging a handshake would put a live credential in the desktop log file. The
/// backend redacts the same value on its side for the same reason.
impl std::fmt::Debug for RealtimeSession {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RealtimeSession")
            .field("client_secret", &"<redacted>")
            .field("expires_at", &self.expires_at)
            .field("websocket_url", &self.websocket_url)
            .field("provider", &self.provider)
            .field("model", &self.model)
            .field("language", &self.language)
            .field("audio_format", &self.audio_format)
            .field("sample_rate", &self.sample_rate)
            .finish()
    }
}

impl RealtimeSession {
    /// Seconds left before the credential can no longer open a connection, minus the slack.
    /// `None` when `expires_at` does not parse — unknown, not expired.
    pub fn usable_seconds(&self) -> Option<i64> {
        let expires = chrono::DateTime::parse_from_rfc3339(&self.expires_at).ok()?;
        let diff = expires.with_timezone(&chrono::Utc) - chrono::Utc::now();
        Some(diff.num_seconds() - EXPIRY_SLACK_SECS)
    }

    /// False only when we can prove the credential is already too old to be worth a handshake.
    pub fn is_usable(&self) -> bool {
        self.usable_seconds().map(|s| s > 0).unwrap_or(true)
    }
}

/// A failure that the front end can tell apart, because the user's next move differs.
///
/// These are ADR 0039's "network classes come back" made concrete: auth rejection, quota, service
/// unavailable, transport. `code` is what goes out on the `stt-error` event.
#[derive(Debug, Clone)]
pub struct SessionError {
    pub code: &'static str,
    pub message: String,
}

impl SessionError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

/// Maps the backend's HTTP answer onto the error taxonomy.
///
/// Split out so it can be tested without a server. The backend's own `code` is not parsed out of
/// the body on purpose: the status already carries every distinction this client acts on, and one
/// less parse is one less way to turn a failure into a different failure.
pub fn classify(status: u16) -> &'static str {
    match status {
        401 | 403 => "STT_AUTH_REJECTED",
        429 => "STT_QUOTA_EXCEEDED",
        503 => "STT_NOT_CONFIGURED",
        502 | 504 => "STT_SERVICE_UNAVAILABLE",
        _ => "STT_SESSION_REFUSED",
    }
}

/// Asks the backend for one session. One call per stream; never cached across tracks.
pub async fn fetch_session(
    app: &AppHandle,
    language: &str,
) -> Result<RealtimeSession, SessionError> {
    let jwt = crate::auth_bridge::web_session_jwt(app)
        .map_err(|e| SessionError::new("STT_AUTH_REJECTED", e))?;

    let url = format!("{}/stt/sessions", crate::api_base_url().trim_end_matches('/'));
    let body = serde_json::json!({ "language": language });

    let response = crate::http_proxy::http_client()
        .post(&url)
        .header("Authorization", format!("Bearer {}", jwt))
        .header("Content-Type", "application/json")
        .json(&body)
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .map_err(|e| {
            SessionError::new(
                "STT_TRANSPORT_FAILED",
                format!("could not reach the NORA API: {}", e),
            )
        })?;

    let status = response.status().as_u16();
    let text = response.text().await.unwrap_or_default();

    if !(200..300).contains(&status) {
        // The backend's message is safe to surface: it never carries a credential (the API
        // redacts on its side and refuses to echo the provider's body).
        return Err(SessionError::new(
            classify(status),
            format!("the NORA API refused the session (HTTP {}): {}", status, first_line(&text)),
        ));
    }

    let session: RealtimeSession = serde_json::from_str(&text).map_err(|e| {
        // The body is NOT included here. On the success path it contains the credential.
        SessionError::new(
            "STT_SESSION_REFUSED",
            format!("could not read the session the API returned: {}", e),
        )
    })?;

    if !session.is_usable() {
        return Err(SessionError::new(
            "STT_SESSION_REFUSED",
            "the API returned a credential that has already expired".to_string(),
        ));
    }

    Ok(session)
}

/// Keeps an error message to one readable line — an HTML error page from a misrouted proxy is
/// otherwise pasted whole into a toast.
///
/// Truncates by CHARACTER, not by byte. `&line[..200]` panics when byte 200 lands inside a
/// multi-byte sequence, and pt-BR error text is full of accented characters — a slice that panics
/// while reporting an error turns a bad gateway into a dead task.
fn first_line(body: &str) -> String {
    const MAX_CHARS: usize = 200;
    let line = body.lines().next().unwrap_or("").trim();
    if line.chars().count() > MAX_CHARS {
        let truncated: String = line.chars().take(MAX_CHARS).collect();
        format!("{}…", truncated)
    } else {
        line.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(expires_at: &str) -> RealtimeSession {
        RealtimeSession {
            client_secret: "ek_supersecret".to_string(),
            expires_at: expires_at.to_string(),
            websocket_url: "wss://provider.example/realtime".to_string(),
            provider: "openai".to_string(),
            model: "gpt-live-transcribe".to_string(),
            language: "pt".to_string(),
            audio_format: "audio/pcm".to_string(),
            sample_rate: 24_000,
        }
    }

    /// The reason the manual Debug impl exists. A derived one would print the credential, and the
    /// desktop log file is written to disk in the user's profile.
    #[test]
    fn debug_never_prints_the_credential() {
        let printed = format!("{:?}", session("2099-01-01T00:00:00Z"));
        assert!(!printed.contains("ek_supersecret"), "debug leaked the credential: {}", printed);
        assert!(printed.contains("<redacted>"));
    }

    #[test]
    fn a_credential_with_room_to_spare_is_usable() {
        let far = chrono::Utc::now() + chrono::Duration::seconds(600);
        assert!(session(&far.to_rfc3339()).is_usable());
    }

    /// A credential inside the slack window is refused HERE rather than at the handshake: the
    /// failure is the same either way, but this one names the cause.
    #[test]
    fn a_credential_inside_the_slack_window_is_not_usable() {
        let soon = chrono::Utc::now() + chrono::Duration::seconds(10);
        assert!(!session(&soon.to_rfc3339()).is_usable());
    }

    /// Unknown is not expired. An unparseable timestamp must not stop a recording from starting.
    #[test]
    fn an_unparseable_expiry_is_treated_as_unknown_not_expired() {
        let s = session("not-a-timestamp");
        assert_eq!(s.usable_seconds(), None);
        assert!(s.is_usable());
    }

    #[test]
    fn http_status_maps_to_the_error_taxonomy() {
        assert_eq!(classify(401), "STT_AUTH_REJECTED");
        assert_eq!(classify(403), "STT_AUTH_REJECTED");
        assert_eq!(classify(429), "STT_QUOTA_EXCEEDED");
        assert_eq!(classify(503), "STT_NOT_CONFIGURED");
        assert_eq!(classify(502), "STT_SERVICE_UNAVAILABLE");
        assert_eq!(classify(418), "STT_SESSION_REFUSED");
    }

    #[test]
    fn first_line_truncates_an_html_error_page() {
        let long = "x".repeat(500);
        let out = first_line(&format!("{}\nmore", long));
        assert_eq!(out.chars().count(), 201, "200 chars plus the ellipsis");
        assert!(out.ends_with('…'));
    }

    /// Truncation is by character. Cutting this by byte panics — and it would panic while
    /// building the message that explains why the request failed.
    #[test]
    fn first_line_does_not_split_a_multibyte_character() {
        let accented = "ã".repeat(400);
        let out = first_line(&accented);
        assert_eq!(out.chars().count(), 201);
        assert!(out.starts_with('ã'));
    }
}
