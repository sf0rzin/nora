// Log Analytics Workspace
// Backing store for Container Apps Environment + Application Insights.
// Kept in a separate module because both consumers need the same workspaceId.

@description('Nome do workspace.')
param name string

@description('Região do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('Retenção em dias (mínimo 30 no PerGB2018).')
@minValue(30)
@maxValue(730)
param retentionInDays int = 30

resource workspace 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: retentionInDays
    features: {
      enableLogAccessUsingOnlyResourcePermissions: true
    }
    workspaceCapping: {
      // Daily ingestion cap in GB. Avoids bill surprises.
      // -1 = no cap. For dev MVP, 1 GB/day is enough.
      dailyQuotaGb: 1
    }
  }
}

output id string = workspace.id
output customerId string = workspace.properties.customerId
output name string = workspace.name

@secure()
output sharedKey string = workspace.listKeys().primarySharedKey
