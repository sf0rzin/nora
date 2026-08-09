# ADR 0009: Speech Token Broker — Azure Speech Credential Strategy

## Status

Superseded by ADR 0035 (local STT: Whisper embedded in Tauri, on the client machine)

## Status history

| Date | Status | Notes |
|---|---|---|
| 2026-05-07 (creation) | Proposed | Initial draft |
| 2026-05-12 | Accepted | Implemented via PR #29 (`SpeechController` + `AzureSpeechTokenBroker`); Bucket4j rate limit; the `docs/adr/README.md` index already marked it as accepted. This line's update was brought by Sub-phase 1.10 (Docs Refresh), which reconciled a minor divergence between the doc's status and the index's |
| 2026-08-07 | Superseded by 0035 | ADR 0034 shuts down the Azure subscription and with it the Azure Speech resource, removing the substrate of this decision. ADR 0035 is the functional replacement: STT moves to running on-device (Whisper via `whisper-rs`), and the entire broker ceases to exist — the `POST /speech/token` endpoint, the Bucket4j rate limit, `AZURE_SPEECH_KEY`/`AZURE_SPEECH_REGION` and token renewal. Note: the "Alternative A — Server-Side Proxy" rejected here is the argument that ADR 0035 reuses to reject self-hosted Whisper on the server |

## Context

NORA Desktop needs access to Azure Speech Services for real-time transcription. The initial model (BYO-key) required the user to configure their own Azure subscription key in the desktop application. This presented several problems:

1. **Credential exposure**: the subscription key was stored on the user's device (even if encrypted)
2. **Complexity**: users had to create an Azure account and manage keys
3. **Cost**: each user had to have their own Azure subscription
4. **Security**: long-lived keys exposed on multiple devices

## Decision

Implement a **Speech Token Broker** in the NORA backend that:

1. Keeps the Azure subscription key on the server (never on the client)
2. Issues ephemeral authorization tokens (10 minutes) on demand
3. The client uses those tokens to authenticate directly with Azure Speech
4. Tokens are renewed automatically during long sessions

### Flow

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

## Consequences

### Positive

- **Security**: the subscription key never leaves the server
- **Simplified UX**: the user does not have to configure anything
- **Centralized cost**: NORA manages the Azure cost
- **Limited blast radius**: a leaked token is valid for only 10 minutes
- **Compliance**: smaller LGPD attack surface

### Negative

- **Network dependency**: the client needs internet access to obtain tokens
- **Additional latency**: +50-100ms at the start of recording
- **Operational cost**: NORA takes on the Azure Speech cost
- **Backend complexity**: new endpoint + rate limiting + broker

## Alternatives Evaluated

### A. Server-Side Proxy (Rejected)

- **Description**: all audio passes through the NORA backend
- **Problem**: high latency, bandwidth cost, single point of failure, complex LGPD
- **Verdict**: not suitable for real-time STT

### B. BYO Key with Stronghold (Rejected)

- **Description**: encrypt the key on disk with tauri-plugin-stronghold
- **Problem**: the key is still exposed at the moment the Speech SDK uses it
- **Verdict**: does not solve the fundamental problem

### C. Azure Key Vault on the Client (Rejected)

- **Description**: use Key Vault to store the key
- **Problem**: it merely shifts the problem to the Vault access credential
- **Verdict**: does not solve the exposure

## Implementation

### Backend

- `POST /speech/token` — endpoint authenticated with JWT
- Rate limit: 6 tokens/minute per user
- Allowed regions: brazilsouth, eastus, westeurope, etc.
- Timeout: 5s for the Azure call

### Desktop

- Removes Azure configuration fields from the UI
- Fetches the token automatically at the start of recording
- Renews the token every 8 minutes during a session
- The Python sidecar uses `SpeechConfig(auth_token=...)`

## References

- [Microsoft Docs — Authenticate with authorization token](https://learn.microsoft.com/azure/ai-services/speech-service/get-started-speech-to-text?pivots=programming-language-python#authenticate-using-an-authorization-token)
- ADR 0008 — Desktop Tauri + Sidecar
- Issue #28 — Speech Token Broker
