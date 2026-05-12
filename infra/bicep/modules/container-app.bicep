// Container App (template reusável)
// Usado pra api, worker e web. Cada chamada do main.bicep passa parâmetros específicos.
// Identity SystemAssigned habilitada — usar `output principalId` pra role assignments no chamador.

@description('Nome da Container App (2-32 chars, lowercase + hífen).')
@minLength(2)
@maxLength(32)
param name string

@description('Região do recurso.')
param location string = resourceGroup().location

@description('Tags aplicadas ao recurso.')
param tags object = {}

@description('Resource ID do Container Apps Environment.')
param environmentId string

@description('Imagem do container (ex.: ghcr.io/sys0xff/nora-api:latest).')
param image string

@description('Nome interno do container dentro da app.')
param containerName string = 'app'

@description('Porta interna que o container escuta.')
param targetPort int = 8080

@description('Tipo de ingress. external = público, internal = só dentro do env, none = sem HTTP.')
@allowed([
  'external'
  'internal'
  'none'
])
param ingress string = 'external'

@description('Permitir tráfego inseguro (HTTP). Default false força HTTPS.')
param allowInsecure bool = false

@description('CPU em cores. 0.25/0.5/0.75/1.0/etc. Consumption suporta múltiplos de 0.25.')
param cpu string = '0.25'

@description('Memória em Gi. Ratio CPU:Memory permitido: 0.25 → 0.5Gi, 0.5 → 1Gi, etc.')
param memory string = '0.5Gi'

@description('Número mínimo de réplicas. 0 = scale-to-zero (paga só quando há requests).')
@minValue(0)
@maxValue(25)
param minReplicas int = 0

@description('Número máximo de réplicas.')
@minValue(1)
@maxValue(25)
param maxReplicas int = 3

@description('Variáveis de ambiente. Formato: [{ name, value } | { name, secretRef }].')
param envVars array = []

@description('Secrets da Container App. Formato: { items: [{ name, value }] }. Values são secure por contrato com o chamador.')
@secure()
#disable-next-line secure-parameter-default
param secretsObject object = { items: [] }

@description('Registro de container (caso imagem seja privada). Formato: { server, username, passwordSecretRef }.')
param registry object = {}

@description('Comando customizado pra sobrescrever ENTRYPOINT do container.')
param command array = []

@description('Args passados pro comando.')
param args array = []

var hasRegistry = !empty(registry)
var ingressConfig = ingress == 'none' ? null : {
  external: ingress == 'external'
  targetPort: targetPort
  transport: 'auto'
  allowInsecure: allowInsecure
  traffic: [
    {
      weight: 100
      latestRevision: true
    }
  ]
}

resource containerApp 'Microsoft.App/containerApps@2024-10-02-preview' = {
  name: name
  location: location
  tags: tags
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    environmentId: environmentId
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: ingressConfig
      secrets: secretsObject.items
      registries: hasRegistry ? [
        {
          server: registry.server
          username: registry.username
          passwordSecretRef: registry.passwordSecretRef
        }
      ] : []
    }
    template: {
      containers: [
        {
          name: containerName
          image: image
          command: empty(command) ? null : command
          args: empty(args) ? null : args
          resources: {
            cpu: json(cpu)
            memory: memory
          }
          env: envVars
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
        rules: ingress == 'external' || ingress == 'internal' ? [
          {
            name: 'http-scaling'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ] : null
      }
    }
  }
}

output id string = containerApp.id
output name string = containerApp.name
output principalId string = containerApp.identity.principalId
output fqdn string = ingress == 'none' ? '' : containerApp.properties.configuration.ingress.fqdn
output latestRevisionName string = containerApp.properties.latestRevisionName
