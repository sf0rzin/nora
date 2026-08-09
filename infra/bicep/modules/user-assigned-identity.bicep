// User-Assigned Managed Identity
//
// Pre-created before the Container Apps so that role assignments can be
// done BEFORE the app references Key Vault. Solves the SystemAssigned circular
// problem (the identity only exists after the app, the role can only be granted
// after that, and the app cannot pull the KV at startup without the role).
//
// Each Container App (api, worker, web) has its own dedicated UAI.

@description('Nome da identidade (2-128 chars).')
@minLength(2)
@maxLength(128)
param name string

@description('Regiao do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

resource uai 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: name
  location: location
  tags: tags
}

output id string = uai.id
output principalId string = uai.properties.principalId
output clientId string = uai.properties.clientId
