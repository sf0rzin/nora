// PostgreSQL Flexible Server (Burstable B1ms)
// Banco transacional do backend Spring. SKU mais barato disponível.
// Public access habilitado pra MVP — migrar pra VNet integration em prod.

@description('Nome do servidor (3-63 chars, lowercase + hífen).')
@minLength(3)
@maxLength(63)
param name string

@description('Região do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('Versão do Postgres.')
@allowed([
  '14'
  '15'
  '16'
])
param postgresVersion string = '16'

@description('SKU. Standard_B1ms é o Burstable mais barato (1 vCPU, 2 GiB RAM).')
param skuName string = 'Standard_B1ms'

@description('Tier.')
@allowed([
  'Burstable'
  'GeneralPurpose'
  'MemoryOptimized'
])
param skuTier string = 'Burstable'

@description('Storage em GB. Mínimo 32 no Burstable.')
@minValue(32)
@maxValue(16384)
param storageSizeGB int = 32

@description('Backup retention em dias. 7 é o mínimo.')
@minValue(7)
@maxValue(35)
param backupRetentionDays int = 7

@description('Usuário admin.')
param adminLogin string

@description('Senha admin. Injetar via @secure() param ou bicepparam com getSecret().')
@secure()
param adminPassword string

@description('Nome do database criado por padrão.')
param databaseName string = 'nora'

@description('Permite que serviços Azure (Container Apps, etc.) acessem o servidor.')
param allowAzureServices bool = true

@description('Lista de IPs pra firewall (cada item: { name, startIpAddress, endIpAddress }).')
param firewallRules array = []

@description('Extensions Postgres a serem allow-listed via parameter `azure.extensions`. Azure Flexible Server bloqueia CREATE EXTENSION por padrao; precisa allow-list explicita por nome em UPPERCASE separado por virgula. Default cobre as extensoes do schema NORA.')
param allowedExtensions string = 'PGCRYPTO,CITEXT'

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: name
  location: location
  tags: tags
  sku: {
    name: skuName
    tier: skuTier
  }
  properties: {
    version: postgresVersion
    administratorLogin: adminLogin
    administratorLoginPassword: adminPassword
    storage: {
      storageSizeGB: storageSizeGB
      autoGrow: 'Enabled'
    }
    backup: {
      backupRetentionDays: backupRetentionDays
      geoRedundantBackup: 'Disabled' // mais barato
    }
    highAvailability: {
      mode: 'Disabled' // HA dobra o custo; off em dev
    }
    network: {
      publicNetworkAccess: 'Enabled'
    }
    authConfig: {
      activeDirectoryAuth: 'Disabled'
      passwordAuth: 'Enabled'
    }
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: server
  name: databaseName
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

// IMPORTANTE: os recursos-filho abaixo sao ENCADEADOS via dependsOn de proposito.
// O Flexible Server serializa operacoes de gerenciamento; sem o encadeamento o ARM
// aplica database/firewall/configuration em PARALELO e o segundo write falha com
// 'ServerIsBusy' (flakiness intermitente observada em deploys consecutivos no B1ms).
resource allowAzure 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = if (allowAzureServices) {
  parent: server
  name: 'AllowAllAzureServicesAndResourcesWithinAzureIps'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
  dependsOn: [
    database
  ]
}

resource customFirewallRules 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = [
  for rule in firewallRules: {
    parent: server
    name: rule.name
    properties: {
      startIpAddress: rule.startIpAddress
      endIpAddress: rule.endIpAddress
    }
    dependsOn: [
      allowAzure
    ]
  }
]

// Allow-list de extensions Postgres. Sem isso, CREATE EXTENSION falha com
// 'extension X is not allow-listed for users in Azure Database for PostgreSQL'.
// Pgcrypto + citext sao usadas pelo schema NORA (gen_random_uuid + email case-insensitive).
resource extensionsConfig 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = {
  parent: server
  name: 'azure.extensions'
  properties: {
    value: allowedExtensions
    source: 'user-override'
  }
  dependsOn: [
    allowAzure
    customFirewallRules
  ]
}

output id string = server.id
output name string = server.name
output fqdn string = server.properties.fullyQualifiedDomainName
output databaseName string = database.name
output jdbcUrl string = 'jdbc:postgresql://${server.properties.fullyQualifiedDomainName}:5432/${database.name}?sslmode=require'
