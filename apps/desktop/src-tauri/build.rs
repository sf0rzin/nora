fn main() {
    // Injects the API base URL at build-time (CI/production) via env NORA_API_BASE_URL.
    // Without the env (local dev), api_base_url() falls back to the tauri.conf.json default (localhost).
    if let Ok(url) = std::env::var("NORA_API_BASE_URL") {
        println!("cargo:rustc-env=NORA_API_BASE_URL={url}");
    }
    println!("cargo:rerun-if-env-changed=NORA_API_BASE_URL");

    // STT config injected at build-time. Needed because an app opened from
    // Finder/Explorer does NOT inherit env from the user's shell — without this you could only
    // switch backend/model in dev.
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
