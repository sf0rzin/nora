use crate::secrets::SecretStore;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::OnceLock;

const FORBIDDEN_HEADERS: &[&str] = &[
    "host", "origin", "cookie",
    "x-forwarded-for", "x-real-ip",
];

const MAX_BODY_BYTES: usize = 1024 * 1024;

fn http_client() -> &'static Client {
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

#[derive(Deserialize)]
pub struct ProxyRequest {
    pub path: String,
    pub method: Option<String>,
    pub headers: Option<HashMap<String, String>>,
    pub body: Option<serde_json::Value>,
    pub auth: Option<bool>,
}

#[derive(Serialize)]
pub struct ProxyResponse {
    pub status: u16,
    pub body: serde_json::Value,
}

#[tauri::command]
pub async fn http_proxy(
    req: ProxyRequest,
    base_url: tauri::State<'_, ApiBaseUrl>,
    secrets: tauri::State<'_, SecretStore>,
) -> Result<ProxyResponse, String> {
    let target = base_url.0.join(&req.path)
        .map_err(|e| format!("invalid path: {}", e))?;

    #[cfg(debug_assertions)]
    eprintln!(
        "[http_proxy] {} {}",
        req.method.as_deref().unwrap_or("GET"),
        target
    );

    let mut clean_headers: HashMap<String, String> = HashMap::new();
    for (k, v) in req.headers.unwrap_or_default() {
        if FORBIDDEN_HEADERS.contains(&k.to_ascii_lowercase().as_str()) {
            continue;
        }
        clean_headers.insert(k, v);
    }
    clean_headers.insert("Content-Type".into(), "application/json".into());

    if req.auth.unwrap_or(true) {
        if let Ok(Some(token)) = secrets.get("access-token") {
            clean_headers.insert("Authorization".into(), format!("Bearer {}", token));
        }
    }

    let client = http_client();
    let method = req.method.unwrap_or_else(|| "GET".into());

    let mut builder = match method.to_ascii_uppercase().as_str() {
        "GET" => client.get(target),
        "POST" => client.post(target),
        "PUT" => client.put(target),
        "DELETE" => client.delete(target),
        other => return Err(format!("método não permitido: {}", other)),
    };

    for (k, v) in &clean_headers {
        builder = builder.header(k, v);
    }

    if let Some(body) = req.body {
        let bytes = serde_json::to_vec(&body)
            .map_err(|e| format!("body serialize: {}", e))?;
        if bytes.len() > MAX_BODY_BYTES {
            return Err(format!("body acima do limite ({} bytes)", bytes.len()));
        }
        builder = builder.body(bytes);
    }

    let response = builder
        .send()
        .await
        .map_err(|e| format!("Request failed: {}", e))?;

    let status = response.status().as_u16();
    #[cfg(debug_assertions)]
    eprintln!("[http_proxy] response status: {}", status);

    let bytes = response.bytes().await
        .map_err(|e| format!("read body: {}", e))?;
    if bytes.len() > MAX_BODY_BYTES {
        return Err("response acima do limite".into());
    }
    let body: serde_json::Value = serde_json::from_slice(&bytes)
        .unwrap_or(serde_json::Value::Null);

    Ok(ProxyResponse { status, body })
}

#[derive(Debug)]
pub struct ApiBaseUrl(pub url::Url);
