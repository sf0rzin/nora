// Azure Cognitive Services — Speech account.
// Used by the backend (speech token broker) to issue an ephemeral token for the Desktop
// Tauri sidecar that runs ConversationTranscriber with diarization (ADR 0009).
//
// SKU S0 (Standard) is the cheapest one with an SLA. Free F0 exists but is shared
// and has a low rate limit — not recommended even in dev.

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
      // bypass is NOT supported for kind 'SpeechServices' (even though other
      // Cognitive Services kinds accept 'AzureServices'). No bypass here.
    }
    disableLocalAuth: false // the backend uses key auth to issue the ephemeral token
  }
}

output id string = speech.id
output name string = speech.name
output endpoint string = speech.properties.endpoint
output region string = speech.location

@secure()
output key1 string = speech.listKeys().key1
