use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::State;

const ALLOWED_KEYS: &[&str] = &[
    "access-token",
    "current-user",
];

const STORE_FILENAME: &str = "secrets.json";

/// Persistent secret store backed by a JSON file under the app config dir.
///
/// MVP-grade: not encrypted, but survives app restarts (fixes #30).
/// Production should migrate to OS keychain (keyring crate) or Stronghold.
pub struct SecretStore {
    data: Arc<Mutex<HashMap<String, String>>>,
    path: PathBuf,
}

impl SecretStore {
    pub fn new(config_dir: PathBuf) -> Self {
        let path = config_dir.join(STORE_FILENAME);
        let data = Self::load_from_disk(&path).unwrap_or_default();
        Self {
            data: Arc::new(Mutex::new(data)),
            path,
        }
    }

    fn load_from_disk(path: &PathBuf) -> Option<HashMap<String, String>> {
        let bytes = fs::read(path).ok()?;
        let map: HashMap<String, String> = serde_json::from_slice(&bytes).ok()?;
        // Drop any unknown keys defensively.
        Some(
            map.into_iter()
                .filter(|(k, _)| ALLOWED_KEYS.contains(&k.as_str()))
                .collect(),
        )
    }

    fn flush_to_disk(&self, data: &HashMap<String, String>) -> Result<(), String> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("create config dir: {}", e))?;
        }
        let bytes = serde_json::to_vec(data).map_err(|e| format!("serialize: {}", e))?;
        fs::write(&self.path, bytes).map_err(|e| format!("write secrets: {}", e))?;
        Ok(())
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
        self.flush_to_disk(&data)?;
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
        self.flush_to_disk(&data)?;
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
pub fn secret_get(
    store: State<'_, SecretStore>,
    key: String,
) -> Result<Option<String>, String> {
    store.get(&key)
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
