use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::OnceLock;

fn http_client() -> &'static Client {
    static CLIENT: OnceLock<Client> = OnceLock::new();
    CLIENT.get_or_init(Client::new)
}

#[derive(Deserialize)]
pub struct ProxyRequest {
    pub url: String,
    pub method: Option<String>,
    pub headers: Option<HashMap<String, String>>,
    pub body: Option<serde_json::Value>,
}

#[derive(Serialize)]
pub struct ProxyResponse {
    pub status: u16,
    pub body: serde_json::Value,
}

#[tauri::command]
pub async fn http_proxy(req: ProxyRequest) -> Result<ProxyResponse, String> {
    #[cfg(debug_assertions)]
    eprintln!("[http_proxy] {} {}", req.method.as_deref().unwrap_or("GET"), req.url);

    let client = http_client();
    let method = req.method.unwrap_or_else(|| "GET".into());

    let mut builder = match method.as_str() {
        "POST" => client.post(&req.url),
        "PUT" => client.put(&req.url),
        "DELETE" => client.delete(&req.url),
        _ => client.get(&req.url),
    };

    if let Some(headers) = &req.headers {
        for (k, v) in headers {
            builder = builder.header(k.as_str(), v.as_str());
        }
    }

    if let Some(body) = req.body {
        if !body.is_null() {
            builder = builder.json(&body);
        }
    }

    let response = builder.send().await.map_err(|e| {
        #[cfg(debug_assertions)]
        eprintln!("[http_proxy] send error: {}", e);
        format!("Request failed: {}", e)
    })?;

    let status = response.status().as_u16();
    #[cfg(debug_assertions)]
    eprintln!("[http_proxy] response status: {}", status);

    let body: serde_json::Value = response.json().await.unwrap_or(serde_json::Value::Null);

    Ok(ProxyResponse { status, body })
}
