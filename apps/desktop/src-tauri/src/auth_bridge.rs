use tauri::{AppHandle, Manager};

/// Reads the session JWT (nora_access cookie) from the main webview (loads nora.systems).
/// The backend accepts this JWT via Bearer (JwtAuthenticationFilter), so it works as a token.
pub fn web_session_jwt(app: &AppHandle) -> Result<String, String> {
    let win = app
        .get_webview_window("main")
        .ok_or("janela principal nao encontrada")?;
    let url = "https://nora.systems"
        .parse()
        .map_err(|_| "url invalida".to_string())?;
    let cookies = win
        .cookies_for_url(url)
        .map_err(|e| format!("falha ao ler cookies: {}", e))?;
    cookies
        .iter()
        .find(|c| c.name() == "nora_access")
        .map(|c| c.value().to_string())
        .ok_or_else(|| {
            "Sessao nao encontrada — faca login na janela principal do Nora.".to_string()
        })
}
