// Azure AI Search (Basic SKU)
// IMPORTANT: the Search service has no pause/start — it is either provisioned (charging ~$0.10/h) or destroyed.
// NORA strategy: the module is only deployed when enableSearch=true (controlled by main.bicep).
// For the FIAP presentation on 12/06: provision ~14 days before (~R$200), destroy afterwards.
//
// Operating mode:
//   - Dev: enableSearch=false, worker uses a local stub
//   - Pre-presentation: enableSearch=true, populate the index, tune relevance
//   - Post-presentation: enableSearch=false again (or destroy the whole RG)

@description('Nome do serviço (2-60 chars, lowercase + hífen).')
@minLength(2)
@maxLength(60)
param name string

@description('Região do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('SKU do Search. Basic = ~$73 USD/mês (~R$400). Free é compartilhado e tem latência imprevisível — não recomendado pra demo.')
@allowed([
  'free'
  'basic'
  'standard'
  'standard2'
  'standard3'
])
param sku string = 'basic'

@description('Número de réplicas (1 = sem HA, suficiente pra MVP).')
@minValue(1)
@maxValue(12)
param replicaCount int = 1

@description('Número de partições.')
@minValue(1)
@maxValue(12)
param partitionCount int = 1

@description('Habilitar Semantic Ranker. Adiciona custo extra — manter false até precisar.')
param enableSemanticRanker bool = false

resource searchService 'Microsoft.Search/searchServices@2024-06-01-preview' = {
  name: name
  location: location
  tags: tags
  sku: {
    name: sku
  }
  properties: {
    replicaCount: replicaCount
    partitionCount: partitionCount
    hostingMode: 'default'
    publicNetworkAccess: 'enabled'
    semanticSearch: enableSemanticRanker ? 'standard' : 'disabled'
    authOptions: {
      apiKeyOnly: {}
    }
    disableLocalAuth: false
  }
}

output id string = searchService.id
output name string = searchService.name
output endpoint string = 'https://${searchService.name}.search.windows.net'
