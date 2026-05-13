// User-Assigned Managed Identity
//
// Pre-criada antes das Container Apps pra que role assignments possam ser
// feitos ANTES da app referenciar Key Vault. Resolve o problema circular do
// SystemAssigned (identity só existe depois da app, role só pode ser dado
// depois disso, app não consegue puxar KV no startup sem role).
//
// Cada Container App (api, worker, web) tem sua propria UAI dedicada.

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
