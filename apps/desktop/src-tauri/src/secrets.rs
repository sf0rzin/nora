use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tauri::State;

const ALLOWED_KEYS: &[&str] = &[
    "access-token",
    "current-user",
];

#[derive(Default)]
pub struct SecretStore {
    data: Arc<Mutex<HashMap<String, String>>>,
}

impl SecretStore {
    pub fn new() -> Self {
        Self {
            data: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    fn validate_key(key: &str) -> Result<(), String> {
        if !ALLOWED_KEYS.contains(&key) {
            return Err(format!("chave não permitida: {}", key));
        }
        Ok(())
    }

    pub fn set(&self, key: &str, value: &str) -> Result<(), String> {
        Self::validate_key(key)?;
        let mut data = self.data.lock().map_err(|e| e.to_string())?;
        data.insert(key.to_string(), value.to_string());
        Ok(())
    }

    pub fn get(&self, key: &str) -> Result<Option<String>, String> {
        Self::validate_key(key)?;
        let data = self.data.lock().map_err(|e| e.to_string())?;
        Ok(data.get(key).cloned())
    }

    pub fn delete(&self, key: &str) -> Result<(), String> {
        Self::validate_key(key)?;
        let mut data = self.data.lock().map_err(|e| e.to_string())?;
        data.remove(key);
        Ok(())
    }

    pub fn has(&self, key: &str) -> Result<bool, String> {
        match self.get(key)? {
            Some(_) => Ok(true),
            None => Ok(false),
        }
    }
}

#[tauri::command]
pub fn secret_set(
    store: State<'_, SecretStore>,
    key: String,
    value: String,
) -> Result<(), String> {
    store.set(&key, &value)
}

#[tauri::command]
pub fn secret_has(
    store: State<'_, SecretStore>,
    key: String,
) -> Result<bool, String> {
    store.has(&key)
}

#[tauri::command]
pub fn secret_delete(
    store: State<'_, SecretStore>,
    key: String,
) -> Result<(), String> {
    store.delete(&key)
}
