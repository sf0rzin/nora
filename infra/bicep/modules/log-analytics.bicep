// Log Analytics Workspace
// Backing store para Container Apps Environment + Application Insights.
// Mantido em um módulo separado porque os dois consumidores precisam do mesmo workspaceId.

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
      // Cap de ingestão diária em GB. Evita surpresa de bill.
      // -1 = sem cap. Pra dev MVP, 1 GB/dia é suficiente.
      dailyQuotaGb: 1
    }
  }
}

output id string = workspace.id
output customerId string = workspace.properties.customerId
output name string = workspace.name

@secure()
output sharedKey string = workspace.listKeys().primarySharedKey
