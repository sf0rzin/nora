fn main() {
    // Injeta a URL base da API em build-time (CI/produção) via env NORA_API_BASE_URL.
    // Sem a env (dev local), api_base_url() cai no default do tauri.conf.json (localhost).
    if let Ok(url) = std::env::var("NORA_API_BASE_URL") {
        println!("cargo:rustc-env=NORA_API_BASE_URL={url}");
    }
    println!("cargo:rerun-if-env-changed=NORA_API_BASE_URL");

    // Config de STT injetada em build-time. Necessario porque um app aberto pelo
    // Finder/Explorer NAO herda env do shell do usuario — sem isto so daria pra
    // trocar backend/modelo em dev.
    //   NORA_STT_BACKEND  = local | azure
    //   NORA_WHISPER_MODEL = tiny | base | small | medium
    for key in ["NORA_STT_BACKEND", "NORA_WHISPER_MODEL"] {
        if let Ok(v) = std::env::var(key) {
            println!("cargo:rustc-env={key}={v}");
        }
        println!("cargo:rerun-if-env-changed={key}");
    }

    tauri_build::build()
}
