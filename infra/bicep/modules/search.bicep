// Azure AI Search (Basic SKU)
// IMPORTANTE: Search service não tem pause/start — ou está provisionado (cobrando ~$0.10/h) ou destruído.
// Estratégia NORA: módulo só é deployado quando enableSearch=true (controlado pelo main.bicep).
// Pra apresentação FIAP 12/06: provisionar ~14 dias antes (~R$200), destruir após.
//
// Modo de operação:
//   - Dev: enableSearch=false, worker usa stub local
//   - Pré-apresentação: enableSearch=true, popular índice, ajustar relevância
//   - Pós-apresentação: enableSearch=false novamente (ou destruir RG inteiro)

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
