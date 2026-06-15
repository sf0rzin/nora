use std::io::Write;
use tauri::{AppHandle, Manager};

/// Logger de arquivo best-effort compartilhado pelo fluxo de gravação.
///
/// Em release o binário roda com `windows_subsystem = "windows"` (sem console),
/// então `eprintln!` some. Esta função append uma linha com timestamp em
/// `<app_log_dir>/desktop.log` — a única forma de o usuário diagnosticar
/// "cliquei gravar e o microfone não abriu".
///
/// REGRA: NUNCA pode fazer o caller falhar nem panicar. Qualquer erro de
/// IO/resolução de path é silenciosamente ignorado (a gravação importa mais
/// que o log). Seguro pra chamar de dentro da thread de áudio.
pub fn log_line(app_handle: &AppHandle, msg: &str) {
    // Timestamp simples: segundos (f64) desde o epoch — só precisamos de ordem
    // + delta entre linhas pra debug.
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0);

    let Ok(dir) = app_handle.path().app_log_dir() else {
        return;
    };
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("desktop.log");

    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
    {
        let _ = writeln!(file, "[{:.3}] {}", ts, msg);
    }
}
