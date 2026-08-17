fn main() {
    // Injects the API base URL at build-time (CI/production) via env NORA_API_BASE_URL.
    // Without the env (local dev), api_base_url() falls back to the tauri.conf.json default (localhost).
    if let Ok(url) = std::env::var("NORA_API_BASE_URL") {
        println!("cargo:rustc-env=NORA_API_BASE_URL={url}");
    }
    println!("cargo:rerun-if-env-changed=NORA_API_BASE_URL");

    // There used to be a second block here baking `NORA_STT_BACKEND` and `NORA_WHISPER_MODEL`
    // into the binary, because an app opened from Explorer does not inherit the shell's env and
    // those two were the only way to pick an engine and a model size. Both questions are gone:
    // transcription is the provider's realtime API (ADR 0039/0045), there is one backend, and the
    // model is chosen by the server when it mints the session — which is where a cost decision
    // belongs. `NORA_API_BASE_URL` is the one thing left that has to be decided at build time,
    // because it says which NORA to talk to.

    tauri_build::build()
}
