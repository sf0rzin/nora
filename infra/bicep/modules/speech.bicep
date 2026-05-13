// Azure Cognitive Services — Speech account.
// Usado pelo backend (speech token broker) pra emitir token efêmero pro Desktop
// Tauri sidecar que faz ConversationTranscriber com diarization (ADR 0009).
//
// SKU S0 (Standard) é o mais barato com SLA. Free F0 existe mas é compartilhado
// e tem rate limit baixo — não recomendado nem em dev.

@description('Nome do recurso Speech (2-64 chars, lowercase + hifen).')
@minLength(2)
@maxLength(64)
param name string

@description('Região do recurso. Speech tem disponibilidade ampla; alinhar com main location.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('SKU. S0 = standard paid (~$1/hora de audio transcrito).')
@allowed([
  'F0'
  'S0'
])
param sku string = 'S0'

resource speech 'Microsoft.CognitiveServices/accounts@2024-10-01' = {
  name: name
  location: location
  tags: tags
  sku: {
    name: sku
  }
  kind: 'SpeechServices'
  properties: {
    customSubDomainName: name
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      defaultAction: 'Allow'
      // bypass NAO eh suportado pra kind 'SpeechServices' (apesar de outros
      // Cognitive Services kinds aceitarem 'AzureServices'). Sem bypass aqui.
    }
    disableLocalAuth: false // backend usa key auth pra emitir token efemero
  }
}

output id string = speech.id
output name string = speech.name
output endpoint string = speech.properties.endpoint
output region string = speech.location

@secure()
output key1 string = speech.listKeys().key1
