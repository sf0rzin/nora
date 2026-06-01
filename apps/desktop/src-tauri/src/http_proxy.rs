use crate::secrets::SecretStore;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::OnceLock;

const FORBIDDEN_HEADERS: &[&str] = &[
    "host",
    "origin",
    "cookie",
    "x-forwarded-for",
    "x-real-ip",
    // O proxy sempre injeta Authorization a partir do SecretStore. Sem este filtro,
    // o renderer poderia mandar `authorization: ...` no payload e o `clean_headers`
    // posterior usaria o injetado, mas o sentinel evita confusao se o codigo de
    // injecao for refatorado.
    "authorization",
];

const MAX_BODY_BYTES: usize = 1024 * 1024;

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
    // SSRF defense: `url::Url::join` aceita URL absoluta no `path` (RFC 3986 §5.3).
    // Sem validar, atacante mandando `path: "https://evil.com/x"` faz o desktop
    // emitir request com Bearer NORA pra qualquer URL. Forcamos que (1) o path
    // comece com '/' e (2) a URL final tenha mesma origin do base_url.
    if !req.path.starts_with('/') || req.path.starts_with("//") {
        return Err("path deve comecar com '/' e nao ser protocol-relative".into());
    }
    let target = base_url
        .0
        .join(&req.path)
        .map_err(|e| format!("invalid path: {}", e))?;
    if target.origin() != base_url.0.origin() {
        return Err("path fora do origin permitido (SSRF blocked)".into());
    }

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
    // Só força Content-Type se o frontend não enviou um (case-insensitive —
    // senão um "content-type" minúsculo do renderer geraria header duplicado).
    if !clean_headers.keys().any(|k| k.eq_ignore_ascii_case("content-type")) {
        clean_headers.insert("Content-Type".into(), "application/json".into());
    }

    if req.auth.unwrap_or(true) {
        if let Ok(Some(token)) = secrets.get("access-token") {
            clean_headers.insert("Authorization".into(), format!("Bearer {}", token));
        }
    }

    let client = http_client();
    let upper = req.method.unwrap_or_else(|| "GET".into()).to_ascii_uppercase();

    let mut builder = match upper.as_str() {
        "GET" => client.get(target),
        "POST" => client.post(target),
        "PUT" => client.put(target),
        "DELETE" => client.delete(target),
        other => return Err(format!("método não permitido: {}", other)),
    };

    for (k, v) in &clean_headers {
        builder = builder.header(k, v);
    }

    // Só anexa body em métodos que o aceitam e quando não é null — senão um GET
    // com `body: null` (default do api-client) mandava o literal "null" no corpo.
    if let Some(body) = req.body {
        if !body.is_null() && matches!(upper.as_str(), "POST" | "PUT") {
            let bytes = serde_json::to_vec(&body)
                .map_err(|e| format!("body serialize: {}", e))?;
            if bytes.len() > MAX_BODY_BYTES {
                return Err(format!("body acima do limite ({} bytes)", bytes.len()));
            }
            builder = builder.body(bytes);
        }
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
