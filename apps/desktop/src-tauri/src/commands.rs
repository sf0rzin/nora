#[tauri::command]
pub fn greet(name: &str) -> String {
    format!("Hello, {}! Welcome to NORA Desktop.", name)
}
