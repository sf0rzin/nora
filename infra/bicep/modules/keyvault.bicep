// Key Vault (Standard com RBAC)
// Armazena: postgres admin password, JWT signing key, Azure Speech key, OpenAI key.
// Acesso via Managed Identity das Container Apps + role assignment.

@description('Nome do Key Vault (3-24 chars, alfanumérico + hífen).')
@minLength(3)
@maxLength(24)
param name string

@description('Região do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('Tenant ID do Azure AD.')
param tenantId string = subscription().tenantId

@description('Object IDs (Entra) que recebem role Key Vault Secrets User. Vazio em MVP — adicionar SP do CI/CD depois.')
param secretsUserPrincipalIds array = []

@description('Habilitar purge protection. Em dev manter false pra permitir teardown rápido; em prod virar true.')
param enablePurgeProtection bool = false

resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    sku: {
      family: 'A'
      name: 'standard'
    }
    tenantId: tenantId
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7 // mínimo permitido
    enablePurgeProtection: enablePurgeProtection ? true : null // null = desabilitado; campo não pode ser false explicitamente
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: 'Allow'
    }
  }
}

// Role: Key Vault Secrets User (read-only em secrets)
// ID: 4633458b-17de-408a-b874-0445c86b69e6
var secretsUserRoleId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  '4633458b-17de-408a-b874-0445c86b69e6'
)

resource secretsUserRoleAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for principalId in secretsUserPrincipalIds: {
    scope: keyVault
    name: guid(keyVault.id, principalId, secretsUserRoleId)
    properties: {
      roleDefinitionId: secretsUserRoleId
      principalId: principalId
      principalType: 'ServicePrincipal'
    }
  }
]

output id string = keyVault.id
output name string = keyVault.name
output uri string = keyVault.properties.vaultUri
