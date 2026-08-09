// Container Apps Environment
// Shared by api, worker and web. Uses the Log Analytics workspace for logs/metrics.
// Consumption profile = scale-to-zero possible, pay only when there are requests.

@description('Nome do environment.')
param name string

@description('Região do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('Log Analytics workspace customerId (GUID).')
param workspaceCustomerId string

@description('Log Analytics workspace shared key (sensível).')
@secure()
param workspaceSharedKey string

@description('Application Insights connection string. Container Apps injeta em containers via env var (opcional).')
@secure()
param appInsightsConnectionString string = ''

resource environment 'Microsoft.App/managedEnvironments@2024-10-02-preview' = {
  name: name
  location: location
  tags: tags
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: workspaceCustomerId
        sharedKey: workspaceSharedKey
      }
    }
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    zoneRedundant: false
    daprAIConnectionString: empty(appInsightsConnectionString) ? null : appInsightsConnectionString
  }
}

output id string = environment.id
output name string = environment.name
output defaultDomain string = environment.properties.defaultDomain
output staticIp string = environment.properties.staticIp
