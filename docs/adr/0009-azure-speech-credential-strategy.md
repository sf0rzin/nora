# ADR 0009: Speech Token Broker — Estratégia de Credenciais Azure Speech

## Status

Proposto

## Contexto

A NORA Desktop precisa de acesso ao Azure Speech Services para transcrição em tempo real. O modelo inicial (BYO-key) exigia que o usuário configurasse sua própria subscription key da Azure no aplicativo desktop. Isso apresentava vários problemas:

1. **Exposição de credenciais**: A subscription key ficava armazenada no dispositivo do usuário (mesmo com criptografia)
2. **Complexidade**: Usuários precisavam criar conta Azure e gerenciar keys
3. **Custo**: Cada usuário precisava ter sua própria subscription Azure
4. **Segurança**: Keys com longa duração expostas em múltiplos dispositivos

## Decisão

Implementar um **Speech Token Broker** no backend NORA que:

1. Guarda a subscription key Azure no servidor (nunca no cliente)
2. Emite tokens de autorização efêmeros (10 minutos) sob demanda
3. O cliente usa esses tokens para autenticar diretamente com Azure Speech
4. Tokens são renovados automaticamente durante sessões longas

### Fluxo

```
Cliente Desktop          Backend NORA           Azure Speech
     |                       |                       |
     |-- POST /speech/token ->|                       |
     |   (JWT NORA)          |                       |
     |                       |-- POST /issueToken -->|
     |                       |   (Subscription Key)  |
     |                       |<-- Token (10 min) ----|
     |<-- {token, region} ---|                       |
     |                       |                       |
     |-- SpeechConfig.from_authorization_token() ---->|
     |                       |                       |
     |-- Audio Stream -------------------------------->|
```

## Consequências

### Positivas

- **Segurança**: Subscription key nunca sai do servidor
- **UX simplificada**: Usuário não precisa configurar nada
- **Custo centralizado**: NORA gerencia o custo Azure
- **Blast radius limitado**: Token vazado vale apenas 10 minutos
- **Compliance**: Menor superfície de ataque LGPD

### Negativas

- **Dependência de rede**: Cliente precisa de internet para obter tokens
- **Latência adicional**: +50-100ms no início da gravação
- **Custo operacional**: NORA assume custo Azure Speech
- **Complexidade backend**: Novo endpoint + rate limiting + broker

## Alternativas Avaliadas

### A. Proxy Server-Side (Rejeitada)

- **Descrição**: Todo áudio passa pelo backend NORA
- **Problema**: Latência alta, custo de banda, ponto único de falha, LGPD complexo
- **Veredito**: Não adequado para STT em tempo real

### B. BYO Key com Stronghold (Rejeitada)

- **Descrição**: Criptografar key no disco com tauri-plugin-stronghold
- **Problema**: Key ainda é exposta no momento de uso pelo SDK Speech
- **Veredito**: Não resolve o problema fundamental

### C. Azure Key Vault no Cliente (Rejeitada)

- **Descrição**: Usar Key Vault para armazenar a key
- **Problema**: Só desloca o problema para a credencial de acesso ao Vault
- **Veredito**: Não resolve a exposição

## Implementação

### Backend

- `POST /speech/token` — Endpoint autenticado com JWT
- Rate limit: 6 tokens/minuto por usuário
- Regiões permitidas: brazilsouth, eastus, westeurope, etc.
- Timeout: 5s para chamada Azure

### Desktop

- Remove campos de configuração Azure da UI
- Busca token automaticamente no início da gravação
- Renova token a cada 8 minutos durante sessão
- Sidecar Python usa `SpeechConfig(auth_token=...)`

## Referências

- [Microsoft Docs — Authenticate with authorization token](https://learn.microsoft.com/azure/ai-services/speech-service/get-started-speech-to-text?pivots=programming-language-python#authenticate-using-an-authorization-token)
- ADR 0008 — Desktop Tauri + Sidecar
- Issue #28 — Speech Token Broker
