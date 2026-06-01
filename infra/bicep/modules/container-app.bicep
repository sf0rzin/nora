// Container App (template reusavel)
// Usado pra api, worker e web. Cada chamada do main.bicep passa parametros especificos.
//
// Identity: aceita User-Assigned MI (passada via userAssignedIdentityId) ou
// SystemAssigned (default quando UAI vazio). Pra integrar com Key Vault references
// preferir UAI — permite role assignment ANTES da app ser criada, evitando o
// ciclo de SystemAssigned (identity so existe pos-criacao, role so apos isso).

@description('Nome da Container App (2-32 chars, lowercase + hifen).')
@minLength(2)
@maxLength(32)
param name string

@description('Regiao do recurso.')
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

@description('Tipo de ingress. external = publico, internal = so dentro do env, none = sem HTTP.')
@allowed([
  'external'
  'internal'
  'none'
])
param ingress string = 'external'

@description('Permitir trafego inseguro (HTTP). Default false forca HTTPS.')
param allowInsecure bool = false

@description('CPU em cores. 0.25/0.5/0.75/1.0/etc. Consumption suporta multiplos de 0.25.')
param cpu string = '0.25'

@description('Memoria em Gi. Ratio CPU:Memory permitido: 0.25 -> 0.5Gi, 0.5 -> 1Gi, etc.')
param memory string = '0.5Gi'

@description('Numero minimo de replicas. 0 = scale-to-zero (paga so quando ha requests).')
@minValue(0)
@maxValue(25)
param minReplicas int = 0

@description('Numero maximo de replicas.')
@minValue(1)
@maxValue(25)
param maxReplicas int = 3

@description('Variaveis de ambiente. Formato: [{ name, value } | { name, secretRef }].')
param envVars array = []

@description('Secrets da Container App. Formato: { items: [{ name, value } | { name, keyVaultUrl, identity }] }. Values sao secure por contrato com o chamador.')
@secure()
#disable-next-line secure-parameter-default
param secretsObject object = { items: [] }

@description('Resource ID da User-Assigned Managed Identity. Vazio = SystemAssigned.')
param userAssignedIdentityId string = ''

@description('Registro de container (caso imagem seja privada). Formato: { server, username, passwordSecretRef }.')
param registry object = {}

@description('Comando customizado pra sobrescrever ENTRYPOINT do container.')
param command array = []

@description('Args passados pro comando.')
param args array = []

@description('Allowlist de IPs no ingress (ADR 0023). Formato: [{ name, ipAddressRange (CIDR), action: "Allow"|"Deny" }]. Vazio = sem restrição de rede.')
param ipSecurityRestrictions array = []

@description('Containers extras (sidecars) no mesmo pod, ex.: cloudflared (ADR 0025). Default [] = só o principal — callers existentes (api/worker/web) ficam idênticos. Cada item segue o schema de container do Microsoft.App: { name, image, args?, env?, resources }.')
param sidecars array = []

@description('Easy Auth (Entra). Vazio ou enabled=false = sem Easy Auth. Formato: { enabled, clientId, openIdIssuer, clientSecretSettingName, unauthenticatedClientAction }. clientSecretSettingName deve referenciar um secret presente em secretsObject.')
param easyAuth object = {}

var hasRegistry = !empty(registry)
var easyAuthEnabled = !empty(easyAuth) && (easyAuth.?enabled ?? false)
var hasUai = !empty(userAssignedIdentityId)

var ingressConfig = ingress == 'none' ? null : {
  external: ingress == 'external'
  targetPort: targetPort
  transport: 'auto'
  allowInsecure: allowInsecure
  ipSecurityRestrictions: empty(ipSecurityRestrictions) ? null : ipSecurityRestrictions
  traffic: [
    {
      weight: 100
      latestRevision: true
    }
  ]
}

var identityConfig = hasUai ? {
  type: 'UserAssigned'
  userAssignedIdentities: {
    '${userAssignedIdentityId}': {}
  }
} : {
  type: 'SystemAssigned'
}

// Probes que o Container Apps reconhece (HEALTHCHECK do Dockerfile e ignorado pela
// plataforma). API Spring Boot expoe /actuator/health; worker e web expoem /healthz.
var probePath = ingress == 'none' ? '' : (containerName == 'api' ? '/actuator/health' : '/healthz')

var probes = ingress == 'none' ? [] : [
  {
    type: 'Liveness'
    httpGet: {
      path: probePath
      port: targetPort
    }
    initialDelaySeconds: 30
    periodSeconds: 30
    timeoutSeconds: 5
    failureThreshold: 3
  }
  {
    type: 'Readiness'
    httpGet: {
      path: probePath
      port: targetPort
    }
    initialDelaySeconds: 5
    periodSeconds: 10
    timeoutSeconds: 3
    failureThreshold: 3
  }
  {
    type: 'Startup'
    httpGet: {
      path: probePath
      port: targetPort
    }
    // API Spring Boot leva ~30s. Damos ate 60s ate marcar failure (12*5s).
    initialDelaySeconds: 5
    periodSeconds: 5
    timeoutSeconds: 3
    failureThreshold: 12
  }
]

resource containerApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: name
  location: location
  tags: tags
  identity: identityConfig
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
      containers: concat(
        [
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
            probes: probes
          }
        ],
        sidecars
      )
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

// Easy Auth (Entra) — child resource separado (ADR 0023). Só criado quando easyAuth.enabled.
// A plataforma Container Apps strippa headers X-MS-CLIENT-PRINCIPAL-* do cliente e injeta os
// seus após validar — o app downstream (nora-admin) lê a identidade do operador daí.
resource authConfig 'Microsoft.App/containerApps/authConfigs@2024-03-01' = if (easyAuthEnabled) {
  parent: containerApp
  name: 'current'
  properties: {
    platform: {
      enabled: true
    }
    globalValidation: {
      unauthenticatedClientAction: easyAuth.?unauthenticatedClientAction ?? 'RedirectToLoginPage'
      redirectToProvider: 'azureactivedirectory'
    }
    identityProviders: {
      azureActiveDirectory: {
        enabled: true
        registration: {
          openIdIssuer: easyAuth.openIdIssuer
          clientId: easyAuth.clientId
          clientSecretSettingName: easyAuth.clientSecretSettingName
        }
      }
    }
  }
}

output id string = containerApp.id
output name string = containerApp.name
// principalId: pra SystemAssigned vem do containerApp; pra UAI o chamador ja tem do UAI module
output principalId string = hasUai ? '' : containerApp.identity.principalId
output fqdn string = ingress == 'none' ? '' : containerApp.properties.configuration.ingress.fqdn
output latestRevisionName string = containerApp.properties.latestRevisionName
