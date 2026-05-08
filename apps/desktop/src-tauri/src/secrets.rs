use azure_identity::DeveloperToolsCredential;
use azure_security_keyvault_secrets::models::{SecretClientGetSecretOptions, SetSecretParameters};
use azure_security_keyvault_secrets::{ResourceExt, SecretClient};
use std::sync::Arc;
use tauri::State;

const ALLOWED_KEYS: &[&str] = &[
    "azure-speech-key",
    "azure-region",
    "access-token",
    "current-user",
];

pub struct KeyVaultStore {
    client: Arc<SecretClient>,
}

impl KeyVaultStore {
    pub fn new(vault_url: &str) -> Result<Self, String> {
        let credential =
            DeveloperToolsCredential::new(None).map_err(|e| format!("azure credential: {}", e))?;
        let client = SecretClient::new(vault_url, credential.clone(), None)
            .map_err(|e| format!("keyvault client: {}", e))?;
        Ok(Self {
            client: Arc::new(client),
        })
    }

    fn validate_key(key: &str) -> Result<(), String> {
        if !ALLOWED_KEYS.contains(&key) {
            return Err(format!("chave não permitida: {}", key));
        }
        Ok(())
    }

    pub async fn set(&self, key: &str, value: &str) -> Result<(), String> {
        Self::validate_key(key)?;
        let params = SetSecretParameters {
            value: Some(value.to_string()),
            ..Default::default()
        };
        self.client
            .set_secret(key, params.try_into().map_err(|e| format!("serialize: {}", e))?, None)
            .await
            .map_err(|e| format!("set_secret: {}", e))?;
        Ok(())
    }

    pub async fn get(&self, key: &str) -> Result<Option<String>, String> {
        Self::validate_key(key)?;
        match self.client.get_secret(key, None).await {
            Ok(response) => {
                let model = response
                    .into_model()
                    .map_err(|e| format!("parse secret: {}", e))?;
                Ok(model.value)
            }
            Err(err) => {
                let inner = err.into_inner();
                match inner {
                    Some(detail) => {
                        if detail
                            .error
                            .as_ref()
                            .and_then(|e| e.code.as_deref())
                            .map_or(false, |c| c == "SecretNotFound")
                        {
                            Ok(None)
                        } else {
                            Err(format!("get_secret: {:?}", detail))
                        }
                    }
                    None => Err("get_secret: unknown error".to_string()),
                }
            }
        }
    }

    pub async fn delete(&self, key: &str) -> Result<(), String> {
        Self::validate_key(key)?;
        match self.client.delete_secret(key, None).await {
            Ok(_) => Ok(()),
            Err(err) => {
                let inner = err.into_inner();
                match inner {
                    Some(detail) => {
                        if detail
                            .error
                            .as_ref()
                            .and_then(|e| e.code.as_deref())
                            .map_or(false, |c| c == "SecretNotFound")
                        {
                            Ok(())
                        } else {
                            Err(format!("delete_secret: {:?}", detail))
                        }
                    }
                    None => Err("delete_secret: unknown error".to_string()),
                }
            }
        }
    }

    pub async fn has(&self, key: &str) -> Result<bool, String> {
        match self.get(key).await {
            Ok(Some(_)) => Ok(true),
            Ok(None) => Ok(false),
            Err(e) => Err(e),
        }
    }
}

#[tauri::command]
pub async fn secret_set(
    store: State<'_, KeyVaultStore>,
    key: String,
    value: String,
) -> Result<(), String> {
    store.set(&key, &value).await
}

#[tauri::command]
pub async fn secret_has(
    store: State<'_, KeyVaultStore>,
    key: String,
) -> Result<bool, String> {
    store.has(&key).await
}

#[tauri::command]
pub async fn secret_delete(
    store: State<'_, KeyVaultStore>,
    key: String,
) -> Result<(), String> {
    store.delete(&key).await
}
