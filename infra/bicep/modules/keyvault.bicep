// Key Vault (Standard with RBAC)
// Stores: postgres admin password, JWT signing key, Azure Speech key, OpenAI key.
// Access via the Container Apps Managed Identity + Key Vault Secrets User role assignment.

@description('Nome do Key Vault (3-24 chars, alfanumerico + hifen).')
@minLength(3)
@maxLength(24)
param name string

@description('Regiao do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('Tenant ID do Azure AD.')
param tenantId string = subscription().tenantId

@description('Object IDs (Entra) que recebem role Key Vault Secrets User (read-only em secrets).')
param secretsUserPrincipalIds array = []

@description('Tipo do principal nos role assignments. ServicePrincipal pra SPs e Managed Identities.')
@allowed([
  'ServicePrincipal'
  'User'
  'Group'
])
param principalType string = 'ServicePrincipal'

@description('Habilitar purge protection. Em dev manter false pra permitir teardown rapido; em prod virar true.')
param enablePurgeProtection bool = false

@description('Lista de secrets a serem criados/atualizados no KV. Formato: [{ name, value }]. Values sao secure por contrato.')
@secure()
#disable-next-line secure-parameter-default
param secrets object = { items: [] }

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
    softDeleteRetentionInDays: 7 // minimum allowed
    enablePurgeProtection: enablePurgeProtection ? true : null // null = disabled; the field cannot be explicitly false
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: 'Allow'
    }
  }
}

// Role: Key Vault Secrets User (read-only on secrets)
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
      principalType: principalType
    }
  }
]

// Creates/updates secrets in the KV. Bicep has no strongly typed array so we use
// `secrets.items` as an array of { name, value } objects.
resource vaultSecrets 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = [
  for s in (secrets.?items ?? []): {
    parent: keyVault
    name: s.name
    properties: {
      value: s.value
      attributes: {
        enabled: true
      }
    }
  }
]

output id string = keyVault.id
output name string = keyVault.name
output uri string = keyVault.properties.vaultUri
