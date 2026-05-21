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

/// Cliente HTTP compartilhado (timeout + pool). Reutilizado pelo proxy e por
/// callers diretos (upload_meeting, live-analysis) pra evitar abrir um pool
/// novo por chamada — o que acontecia antes com `reqwest::Client::new()`.
pub fn http_client() -> &'static Client {
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

    // Defesa em profundidade: em release builds rejeita HTTP plain para hosts não-loopback.
    // Em dev (debug_assertions) o backend Spring local roda em http://localhost:8080 sem TLS,
    // por isso o relaxamento só vale lá. Em prod, JWT *nunca* deve viajar em claro.
    #[cfg(not(debug_assertions))]
    {
        if target.scheme() == "http" {
            let host = target.host_str().unwrap_or("");
            let is_loopback = matches!(host, "localhost" | "127.0.0.1" | "::1");
            if !is_loopback {
                return Err(format!(
                    "HTTP plain bloqueado em release para host '{}': use HTTPS",
                    host
                ));
            }
        }
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
        // Bloqueia header injection via CRLF / NUL / chars não-ASCII em chaves.
        // reqwest tipicamente rejeita, mas a validação aqui produz erro nítido.
        if k.is_empty()
            || !k.bytes().all(|b| b.is_ascii_graphic() && b != b':')
            || v.bytes().any(|b| b == b'\r' || b == b'\n' || b == 0)
        {
            return Err(format!("header inválido: {}", k));
        }
        clean_headers.insert(k, v);
    }
    // Só força Content-Type se o frontend não enviou um
    if !clean_headers.contains_key("Content-Type") {
        clean_headers.insert("Content-Type".into(), "application/json".into());
    }

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
        "PATCH" => client.patch(target),
        "DELETE" => client.delete(target),
        "HEAD" => client.head(target),
        "OPTIONS" => client.request(reqwest::Method::OPTIONS, target),
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
