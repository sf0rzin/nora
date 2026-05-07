use tauri::State;
use std::sync::{Arc, Mutex};

const ALLOWED_KEYS: &[&str] = &[
    "azure_speech_key",
    "azure_region",
    "access_token",
    "current_user",
];

/// Simple in-memory secret store for MVP.
/// In production, migrate to Stronghold or OS keychain.
pub struct SecretStore {
    store: Arc<Mutex<std::collections::HashMap<String, String>>>,
}

impl SecretStore {
    pub fn new() -> Self {
        Self {
            store: Arc::new(Mutex::new(std::collections::HashMap::new())),
        }
    }

    pub fn set(&self, key: &str, value: &str) -> Result<(), String> {
        if !ALLOWED_KEYS.contains(&key) {
            return Err(format!("chave não permitida: {}", key));
        }
        let mut store = self.store.lock().map_err(|e| format!("lock error: {}", e))?;
        store.insert(key.to_string(), value.to_string());
        Ok(())
    }

    #[allow(dead_code)]
    pub fn get(&self, key: &str) -> Result<Option<String>, String> {
        if !ALLOWED_KEYS.contains(&key) {
            return Err(format!("chave não permitida: {}", key));
        }
        let store = self.store.lock().map_err(|e| format!("lock error: {}", e))?;
        Ok(store.get(key).cloned())
    }

    pub fn delete(&self, key: &str) -> Result<(), String> {
        if !ALLOWED_KEYS.contains(&key) {
            return Err(format!("chave não permitida: {}", key));
        }
        let mut store = self.store.lock().map_err(|e| format!("lock error: {}", e))?;
        store.remove(key);
        Ok(())
    }

    pub fn has(&self, key: &str) -> Result<bool, String> {
        if !ALLOWED_KEYS.contains(&key) {
            return Err(format!("chave não permitida: {}", key));
        }
        let store = self.store.lock().map_err(|e| format!("lock error: {}", e))?;
        Ok(store.contains_key(key))
    }
}

#[tauri::command]
pub fn secret_set(
    secrets: State<'_, SecretStore>,
    key: String,
    value: String,
) -> Result<(), String> {
    secrets.set(&key, &value)
}

#[tauri::command]
pub fn secret_has(
    secrets: State<'_, SecretStore>,
    key: String,
) -> Result<bool, String> {
    secrets.has(&key)
}

#[tauri::command]
pub fn secret_delete(
    secrets: State<'_, SecretStore>,
    key: String,
) -> Result<(), String> {
    secrets.delete(&key)
}
