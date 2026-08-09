// PostgreSQL Flexible Server (Burstable B1ms)
// Transactional database of the Spring backend. Cheapest SKU available.
// Public access enabled for the MVP — migrate to VNet integration in prod.

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
      geoRedundantBackup: 'Disabled' // cheaper
    }
    highAvailability: {
      mode: 'Disabled' // HA doubles the cost; off in dev
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

// IMPORTANT: the child resources below are CHAINED via dependsOn on purpose.
// The Flexible Server serializes management operations; without the chaining ARM
// applies database/firewall/configuration in PARALLEL and the second write fails with
// 'ServerIsBusy' (intermittent flakiness observed in consecutive deploys on the B1ms).
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

// Allow-list of Postgres extensions. Without it, CREATE EXTENSION fails with
// 'extension X is not allow-listed for users in Azure Database for PostgreSQL'.
// Pgcrypto + citext are used by the NORA schema (gen_random_uuid + case-insensitive email).
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
