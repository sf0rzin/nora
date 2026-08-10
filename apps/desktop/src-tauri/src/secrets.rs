use tauri::State;

const SERVICE_NAME: &str = "nora-desktop";

const ALLOWED_KEYS: &[&str] = &[
    "access-token",
    "refresh-token",
    "current-user",
];

/// Secret store backed by the OS keychain (keyring crate).
///
/// Cross-platform:
/// - Windows: Credential Manager (DPAPI)
/// - macOS: Keychain
/// - Linux: Secret Service (D-Bus)
pub struct SecretStore;

impl SecretStore {
    pub fn new() -> Self {
        Self
    }

    fn validate_key(key: &str) -> Result<(), String> {
        if !ALLOWED_KEYS.contains(&key) {
            return Err(format!("key not allowed: {}", key));
        }
        Ok(())
    }

    fn entry(key: &str) -> Result<keyring::Entry, String> {
        keyring::Entry::new(SERVICE_NAME, key).map_err(|e| e.to_string())
    }

    pub fn set(&self, key: &str, value: &str) -> Result<(), String> {
        Self::validate_key(key)?;
        Self::entry(key)?.set_password(value).map_err(|e| e.to_string())
    }

    pub fn get(&self, key: &str) -> Result<Option<String>, String> {
        Self::validate_key(key)?;
        match Self::entry(key)?.get_password() {
            Ok(value) => Ok(Some(value)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(e.to_string()),
        }
    }

    pub fn delete(&self, key: &str) -> Result<(), String> {
        Self::validate_key(key)?;
        match Self::entry(key)?.delete_credential() {
            Ok(()) => Ok(()),
            Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }

    pub fn has(&self, key: &str) -> Result<bool, String> {
        match self.get(key)? {
            Some(_) => Ok(true),
            None => Ok(false),
        }
    }
}

#[tauri::command]
pub fn secret_set(store: State<'_, SecretStore>, key: String, value: String) -> Result<(), String> {
    store.set(&key, &value)
}

#[tauri::command]
pub fn secret_get(store: State<'_, SecretStore>, key: String) -> Result<Option<String>, String> {
    store.get(&key)
}

#[tauri::command]
pub fn secret_has(store: State<'_, SecretStore>, key: String) -> Result<bool, String> {
    store.has(&key)
}

#[tauri::command]
pub fn secret_delete(store: State<'_, SecretStore>, key: String) -> Result<(), String> {
    store.delete(&key)
}
