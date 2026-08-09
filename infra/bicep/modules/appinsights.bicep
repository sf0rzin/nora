// Application Insights (workspace-based)
// Telemetry for api/worker/web. Connects to the already created Log Analytics workspace.

@description('Nome do recurso.')
param name string

@description('Região do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('ID do Log Analytics workspace pra associar.')
param workspaceId string

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: name
  location: location
  tags: tags
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: workspaceId
    IngestionMode: 'LogAnalytics'
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
    DisableLocalAuth: false
  }
}

output id string = appInsights.id
output name string = appInsights.name

@secure()
output connectionString string = appInsights.properties.ConnectionString

@secure()
output instrumentationKey string = appInsights.properties.InstrumentationKey
