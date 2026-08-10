use tauri::{AppHandle, Manager};

/// Reads the session JWT (nora_access cookie) from the main webview (loads nora.systems).
/// The backend accepts this JWT via Bearer (JwtAuthenticationFilter), so it works as a token.
pub fn web_session_jwt(app: &AppHandle) -> Result<String, String> {
    let win = app
        .get_webview_window("main")
        .ok_or("main window not found")?;
    let url = "https://nora.systems"
        .parse()
        .map_err(|_| "invalid url".to_string())?;
    let cookies = win
        .cookies_for_url(url)
        .map_err(|e| format!("failed to read cookies: {}", e))?;
    cookies
        .iter()
        .find(|c| c.name() == "nora_access")
        .map(|c| c.value().to_string())
        .ok_or_else(|| {
            "Session not found — please log in in the Nora main window.".to_string()
        })
}
