//! The one reqwest client the application shares.
//!
//! ============================================================================
//! WHAT THIS MODULE USED TO BE
//! ============================================================================
//! An `http_proxy` Tauri command: the renderer handed it a path, a method and headers, and it
//! signed the request with the keyring's `access-token` before forwarding it to the NORA API.
//! It carried a hand-written SSRF defence (an absolute URL in `path` would otherwise have been
//! accepted by `Url::join` and sent the Bearer anywhere), a forbidden-header list and a body
//! cap.
//!
//! It is gone because it had no traffic, in either direction. The login form that wrote
//! `access-token` was deleted with the local UI (PR #465), so the header it injected was
//! empty; and its only caller, `apiClient.request`, was reachable only from modules that
//! nothing live imported. `stt_token.rs` had already written that measurement down and chosen
//! `auth_bridge::web_session_jwt` instead. Keeping the command registered kept a path to the
//! Windows Credential Manager open on the IPC for nobody (audit #15).
//!
//! What is left is the piece that always had callers: one pooled client with the timeouts every
//! outbound request in this application should share. `commands.rs` (upload), `live_analysis.rs`
//! and `stt_token.rs` all take it from here, which is why the timeouts are stated once.

use reqwest::Client;
use std::sync::OnceLock;

pub(crate) fn http_client() -> &'static Client {
    static CLIENT: OnceLock<Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_secs(5))
            .timeout(std::time::Duration::from_secs(30))
            .pool_max_idle_per_host(4)
            .build()
            .expect("reqwest client")
    })
}
