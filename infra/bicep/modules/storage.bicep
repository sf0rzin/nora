// Storage Account (Standard LRS)
// Blob containers pra transcripts e uploads futuros (áudio/vídeo).
// Public blob access desabilitado — acesso só via SAS ou managed identity.

@description('Nome do storage account (3-24 chars, lowercase alfanumérico).')
@minLength(3)
@maxLength(24)
param name string

@description('Região do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('SKU. LRS é o mais barato (sem replicação cross-zone).')
@allowed([
  'Standard_LRS'
  'Standard_GRS'
  'Standard_ZRS'
])
param sku string = 'Standard_LRS'

@description('Containers criados ao provisionar.')
param containerNames array = [
  'meeting-transcripts'
  'meeting-uploads'
]

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: name
  location: location
  tags: tags
  sku: {
    name: sku
  }
  kind: 'StorageV2'
  properties: {
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: true // habilitado pra MVP; migrar pra managed identity quando estabilizar
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      defaultAction: 'Allow'
      bypass: 'AzureServices'
    }
    encryption: {
      services: {
        blob: {
          enabled: true
          keyType: 'Account'
        }
        file: {
          enabled: true
          keyType: 'Account'
        }
      }
      keySource: 'Microsoft.Storage'
    }
    accessTier: 'Hot'
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
  properties: {
    deleteRetentionPolicy: {
      enabled: true
      days: 7
    }
    containerDeleteRetentionPolicy: {
      enabled: true
      days: 7
    }
  }
}

resource containers 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = [
  for containerName in containerNames: {
    parent: blobService
    name: containerName
    properties: {
      publicAccess: 'None'
    }
  }
]

output id string = storage.id
output name string = storage.name
output primaryBlobEndpoint string = storage.properties.primaryEndpoints.blob
