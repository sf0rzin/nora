fn main() {
    // Injeta a URL base da API em build-time (CI/produção) via env NORA_API_BASE_URL.
    // Sem a env (dev local), api_base_url() cai no default do tauri.conf.json (localhost).
    if let Ok(url) = std::env::var("NORA_API_BASE_URL") {
        println!("cargo:rustc-env=NORA_API_BASE_URL={url}");
    }
    println!("cargo:rerun-if-env-changed=NORA_API_BASE_URL");
    tauri_build::build()
}
