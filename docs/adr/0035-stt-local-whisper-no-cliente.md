# ADR 0035 — STT local: Whisper embarcado no Tauri (Rust), rodando na máquina do cliente

- **Status:** aceito
- **Data:** 2026-08-07
- **Decisores:** sys0xFF (PO/dono) + Arquiteto NORA (Tech Lead, auditoria de migração)
- **Substitui:** ADR 0009 (Speech Token Broker — integralmente: o broker, o endpoint, o rate limit e
  a credencial deixam de existir)
- **Substitui parcialmente:** ADR 0008 (Desktop Tauri 2 + Sidecar Python — o **Tauri 2 permanece**;
  o **sidecar Python sai**, e com ele o PyInstaller e o segundo runtime)
- **Relacionados:** ADR 0034 (a migração apaga o recurso Azure Speech e é o que força esta
  decisão), ADR 0012 (PII), ADR 0029 (LGPD operacional), ADR 0033 (PII no caminho do chat),
  ADR 0005/0006/0015 (consumidores do sinal de falante)

## Contexto

O ADR 0034 desliga a subscription Azure. Com ela morre o recurso **Azure Speech (Cognitive Services
SpeechServices S0)**, e com ele o substrato dos ADRs 0008 (sidecar Python) e 0009 (token broker).
Não dá para "só remover": o desktop precisa de STT.

### O que existe hoje

| Peça | Onde | O que faz |
|---|---|---|
| Sidecar Python | `apps/desktop/sidecar/` (`azure-cognitiveservices-speech>=1.40`) | `ConversationTranscriber` sobre push stream 16 kHz/16-bit/mono; protocolo NDJSON v1 por stdin/stdout; empacotado com PyInstaller |
| **Dois** sidecars simultâneos | `src-tauri/src/commands.rs:135-180` | um por track: `track_label = "mic"` e `track_label = "system"` (loopback do sistema) |
| Diarização online | `transcriber.py:94-97` (`SpeechServiceResponse_DiarizeIntermediateResults`) | `speaker_id` (`Guest-1`, …) por falante, **dentro de cada track** |
| Confidence | `transcriber.py:152-164` | `NBest[0].Confidence` do `OutputFormat.Detailed` |
| Resiliência | `transcriber.py:166-222` | mapa `CancellationErrorCode` → `AUTH_FAILED`/`BAD_REQUEST`/`QUOTA`/`NETWORK`/`SERVICE_UNAVAILABLE`/`RUNTIME_ERROR`, com auto-restart e backoff exponencial (3 tentativas) **só** em `NETWORK`/`SERVICE_UNAVAILABLE` |
| Broker | `POST /speech/token`, `application.yml:175-183` | token de 540s emitido pelo STS **regional** (`https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken`, header `Ocp-Apim-Subscription-Key`), rate limit Bucket4j 6/min/usuário |

### A restrição que reordena o cálculo

O **áudio bruto de reunião é a PII mais sensível do produto** — e hoje ele sai da máquina do usuário
em tempo real, para um terceiro, **sem nenhum gate**.

O ADR 0012 e o ADR 0033 tratam de texto. O ADR 0029 aponta `transcripts.raw_text` como PII em
repouso. Mas o **áudio é upstream de tudo isso**: o PII Shield roda no worker, depois, sobre o texto
já transcrito. Entre o microfone e o texto, a fala inteira — nomes, números, o que for dito — já
atravessou a internet até um provedor externo. Esse trecho do caminho nunca teve ADR.

O ADR 0009 tratou esse ponto como resolvido porque a *credencial* estava protegida ("subscription
key nunca sai do servidor", "blast radius limitado", "compliance: menor superfície de ataque LGPD").
Proteger a credencial não protege o áudio. A migração forçada é a oportunidade de corrigir isso, não
apenas de trocar de fornecedor.

## Decisão

**Whisper embarcado no binário Tauri (Rust), transcrevendo na máquina do cliente.**

1. **`whisper.cpp` via binding Rust (`whisper-rs`), compilado dentro do binário Tauri.** Sem
   processo separado, sem Python, sem PyInstaller, sem IPC por stdin/stdout.
2. **Modelo GGML quantizado, baixado no primeiro uso** — não embarcado no instalador (ver
   §Estratégia de download).
3. **Duas instâncias in-process**, uma por track — o desenho de dois transcribers do
   `commands.rs` se mantém, deixando de ser dois processos.
4. **Atribuição por TRACK, não por falante:** `track: "mic"` = usuário local; `track: "system"` =
   participantes remotos. `speaker_id` fica **null** dentro do track.
5. **O broker sai inteiro:** `SpeechController`, `SpeechTokenService`, `AzureSpeechTokenBroker`, o
   rate limit Bucket4j, `AZURE_SPEECH_KEY`/`AZURE_SPEECH_REGION`, o bloco `nora.speech.*` do
   `application.yml`, a mensagem `refresh_token` do protocolo NDJSON e o timer de renovação de 8
   minutos no Rust.
6. **Offline-first:** com o modelo presente, a gravação e a transcrição funcionam sem rede. Só o
   upload da reunião e a análise LLM exigem conectividade.

### Por que na MÁQUINA DO CLIENTE, e não self-hosted no servidor

A alternativa óbvia depois do ADR 0034 seria subir um Whisper no worker e continuar mandando áudio
para lá. Os ganhos abaixo são de **local no cliente**; o self-hosting no servidor **não entrega
nenhum deles**:

**a) O áudio bruto com PII nunca sai da máquina.** Self-hosting moveria a PII de um terceiro (Azure)
para nós mesmos. Isso melhora a governança contratual e **não reduz a exposição**: o áudio
continuaria trafegando, continuaria em buffer em algum lugar, e nos tornaria controladores de mais
um dado sensível — mais superfície para o direito ao esquecimento e a retenção do ADR 0029 cobrirem.
Local **reforça** o ADR 0012 (o gate mais forte é o dado não existir fora) e **reforça** o ADR 0029
(não há áudio a reter, nem a apagar) em vez de piorar os dois.

**b) Some o token broker inteiro.** Endpoint autenticado, rate limit, secret, região, renovação a
cada 8 min, a mensagem `refresh_token` no protocolo, o erro `TOKEN_REFRESH_FAILED` e a classe de
falha "o token expirou no meio da reunião": tudo deixa de existir. Self-hosting **não** faria isso
sumir — continuaria sendo preciso autenticar o cliente contra o serviço de STT, ou seja, um broker
equivalente com outro nome.

**c) Funciona offline.** Reunião presencial, wifi de hotel, VPN corporativa restritiva: o STT
continua. Self-hosting no servidor não dá isso em nenhuma configuração.

**d) Não reintroduz a topologia que o próprio ADR 0009 rejeitou.** A "Alternativa A — Proxy
Server-Side (Rejeitada): todo áudio passa pelo backend NORA", com veredito *"latência alta, custo de
banda, ponto único de falha, LGPD complexo — não adequado para STT em tempo real"*. Self-hosting
Whisper no servidor **é literalmente essa alternativa**, agora agravada: depois do ADR 0034 o
"servidor" é **uma VM doméstica única, sem GPU**. Streaming de áudio de N clientes para um host
residencial é a pior variante possível de uma topologia que já havia sido descartada com a infra
boa.

**e) O custo marginal de STT vai a zero para a NORA.** O ADR 0009 listava "custo centralizado" como
*positiva* — na prática significa NORA pagando por minuto transcrito de todo mundo. Deixa de ser um
item de unit economics.

<a id="trade-off"></a>

## Trade-off — o que se perde, explicitamente

Nada aqui é mitigado por configuração. São perdas.

### 1. Diarização online por falante

**Whisper não faz diarização.** É um encoder-decoder de transcrição; não tem noção de falante. As
soluções usuais — **WhisperX + pyannote.audio** — são **batch por construção**: clusterizam
embeddings de falante sobre o áudio **completo**. Não existe variante online delas que caiba num
loop de tempo real; isso não é limitação de implementação, é o desenho do método.

Consequências concretas:

- A atribuição passa a ser **por track**: `mic` = usuário local, `system` = participantes remotos.
- `speaker_id` fica **null** dentro do track. O contrato **não quebra**: o NDJSON já declara
  `speaker_id: str | None` (`protocol.py:52,59`) e o `TranscriptEvent` do Rust já carrega
  `track: String` (`stt_sidecar.rs:17`). O contrato aguenta; o **conteúdo empobrece**.
- Numa call com 4 participantes remotos, os 4 viram **um bloco só**. A UI de renomear falante do
  `overlay.tsx` (`detectedSpeakers`, evento `nora://rename-speaker`) degrada de N falantes
  detectados para exatamente **dois** rótulos fixos.
- **O que NÃO se perde:** a separação eu-vs-eles. É ela que sustenta talk-ratio e boa parte do
  Productivity Score (ADR 0005) e do Customer Confidence (ADR 0006/0015). O sinal mais valioso
  sobrevive; a granularidade **dentro** do lado remoto, não.

Recuperação possível — **declarada, não prometida**: diarização **pós-reunião**, em batch,
localmente, ao fim da gravação, reescrevendo `speaker_id` no transcript já salvo. Não entra agora e
não é pré-requisito desta decisão.

### 2. Confidence calibrada

`NBest[0].Confidence` é uma probabilidade **calibrada pelo serviço**. Whisper expõe logprob por
token e `no_speech_prob`; uma média de logprob **não é calibrada** e não é comparável ao número
anterior.

Decisão: **`confidence` passa a ser `null`**, e não uma média de logprob disfarçada de
probabilidade. Publicar um número não calibrado num campo que antes era calibrado é pior do que
publicar nada — qualquer consumidor que aplique um limiar herdaria um limiar sem significado. Os
tipos já são opcionais (`Option<f32>` em `stt_sidecar.rs:23`, `confidence?: number` em
`lib/types.ts:98`), então nada quebra por tipo. Se algum consumidor precisar de um sinal de
qualidade, ele será um campo **novo e explicitamente não calibrado**, não este.

### 3. Taxonomia `CancellationErrorCode` e o auto-restart que ela sustentava

O mapa de erros vinha do SDK, e o auto-restart com backoff exponencial disparava **só** em `NETWORK`
e `SERVICE_UNAVAILABLE`. Sem rede no caminho, **essas duas categorias deixam de existir** — e com
elas o gatilho do auto-restart. Somem também `AUTH_FAILED` e `QUOTA` (não há credencial nem cota) e
`TOKEN_REFRESH_FAILED` (não há token).

As falhas locais são de outra natureza e exigem taxonomia nova, em que **backoff não é a resposta**:

| Código novo | Natureza | Tratamento |
|---|---|---|
| `MODEL_MISSING` | modelo não baixado | fail-fast; gate de UI com ação "baixar modelo" |
| `MODEL_CHECKSUM_MISMATCH` | download corrompido/adulterado | fail-fast; apaga e re-baixa |
| `MODEL_LOAD_FAILED` | arquivo inválido / RAM insuficiente na carga | fail-fast com mensagem acionável |
| `OUT_OF_MEMORY` | estouro durante decode | degradar para modelo menor ou single-track |
| `DECODE_FAILED` | erro na inferência | 1 retry imediato; depois fail-fast |
| `AUDIO_DEVICE_LOST` | dispositivo sumiu | recuperável — é o **único** caso que mantém retry |

Ou seja: a resiliência não é "portada", é **reprojetada**. Registrar isso importa porque o
auto-restart do `transcriber.py:193-222` nasceu de duas auditorias (#113, #116) e some inteiro.

### 4. Whisper não é streaming

É um encoder-decoder de janela de 30s. Tempo real é **emulado** por janela deslizante com VAD.
Consequências:

- `partial` deixa de ser palavra-a-palavra e vira **chunk-a-chunk**.
- A latência do primeiro texto sai de centenas de milissegundos para o tamanho da janela (alvo: ~5s
  com flush por silêncio, no lugar dos 800 ms de `Speech_SegmentationSilenceTimeoutMs`).
- Há **reescrita de borda**: o texto de um chunk pode mudar quando a janela avança. A UI de overlay
  precisa tolerar rewrite de linha parcial — hoje ela assume append.

## Custo em hardware do cliente

Este custo não desaparece: ele **sai do nosso billing e entra no laptop do usuário**. Precisa estar
escrito.

- **Duas instâncias simultâneas** (mic + system) rodando durante a reunião inteira. Dobra CPU e RAM
  em relação a qualquer benchmark de instância única.
- **Modelo padrão: `small` quantizado** (`ggml-small-q5_0`, ~190 MB em disco, ~600 MB residentes por
  instância). É o piso prático para pt-BR com qualidade utilizável.
  - `base` q5 (~60 MB) como **degradação automática** em máquina fraca — mais rápido, pior em pt-BR.
  - `medium` q5 (~540 MB) **opt-in** para quem tem folga.
- **CPU:** `small` com ~4 threads roda perto de tempo real em x86-64 moderno com AVX2. Duas
  instâncias ocupam ~4-8 threads efetivos **durante toda a reunião** — em laptop isso significa
  ventoinha ligada e bateria drenando, num momento em que a máquina **já está numa call de vídeo**.
- **Piso declarado:** 4 núcleos físicos + 8 GB RAM para `small` dual-track. Abaixo disso: degradar
  para `base`; se ainda não couber, para **single-track** (só `system`, que é o lado que mais
  importa em contexto comercial).
- **Aceleração:** Metal (macOS) e Vulkan/CUDA (Windows/Linux) via feature flags do whisper.cpp, em
  best-effort. **Não é requisito** — a decisão precisa fechar em CPU.
- **Consequência de produto, sem maquiagem:** o desktop deixa de ser "leve" no sentido do ADR 0008.
  O argumento de bundle (~5-15 MB vs ~150 MB do Electron) **continua valendo** — o binário não
  cresce. O que cresce é o **footprint em execução**, que é uma métrica diferente e que o ADR 0008
  nunca discutiu.

## Estratégia de download do modelo

**Não embarcar no instalador.** `small` q5 somaria ~190 MB a um bundle cujo argumento central no ADR
0008 era justamente tamanho.

- **Download no primeiro uso**, com barra de progresso, **retomável** (HTTP Range), gravação
  **atômica** (baixa em `.part`, `fsync`, renomeia).
- **Verificação SHA-256 obrigatória** contra um manifesto embarcado no binário. Checksum diferente =
  apaga e falha (`MODEL_CHECKSUM_MISMATCH`); nunca usa arquivo não verificado.
- **Destino:** diretório de dados do app — `%APPDATA%\NORA\models` (Windows),
  `~/Library/Application Support/NORA/models` (macOS), `$XDG_DATA_HOME/nora/models` (Linux).
  **Nunca** o diretório de instalação: no Windows exigiria elevação.
- **Origem: mirror próprio** (`models.<dominio>`, estático atrás da Cloudflare) com o upstream Hugging
  Face (`ggerganov/whisper.cpp`) como fallback. Dois motivos: não depender da disponibilidade nem da
  política de rate limit de terceiro **no primeiro uso** — o momento mais frágil do onboarding — e,
  por ser conteúdo estático cacheado na borda, **não passar pela VM do ADR 0034**: a queda do host
  não impede alguém de instalar o desktop.
- **Instalação offline:** `NORA_WHISPER_MODEL_PATH` aponta para um arquivo já presente; o app valida
  o checksum e usa. Cobre ambiente corporativo sem saída para a internet — que é, não por acaso,
  exatamente o público que mais quer STT local.
- **Gate:** sem modelo válido, a gravação fica **desabilitada com mensagem acionável**, não quebrada
  em silêncio.
- **Atualização:** uma versão nova do app pode declarar um modelo novo no manifesto. O antigo é
  mantido até o novo ser baixado **e verificado**; só então é removido.

## Consequências

**Positivas**

- Áudio bruto com PII **nunca sai da máquina do usuário** — fecha um trecho do caminho que nunca
  teve gate, reforçando ADR 0012 e ADR 0029.
- Um runtime a menos: sai o Python, sai o PyInstaller, sai o build matricial do sidecar nas 3
  plataformas, sai o IPC por stdin/stdout. O negativo "dois runtimes para manter" do ADR 0008 é
  resolvido.
- Superfície de servidor menor: um endpoint autenticado, um rate limit, uma credencial e uma região
  a menos para operar, rotacionar e auditar.
- Custo de STT por minuto some do unit economics.
- Funciona offline.

**Negativas / dívidas**

- Diarização por falante, confidence calibrada e a taxonomia de erro do SDK: perdidas (ver
  §Trade-off).
- Qualidade em pt-BR passa a depender do modelo local e do hardware do usuário — **variável entre
  máquinas**, ao contrário de um serviço gerenciado que entrega o mesmo resultado para todos.
- Primeiro uso ganha um passo de download de ~190 MB.
- Reescrita real, não port: novo motor, nova taxonomia de erro, nova estratégia de janela, nova UI
  de progresso e de gate de modelo. Os testes `test_transcriber_fake.py` / `test_protocol.py` são
  substituídos, não adaptados.
- O piso de hardware exclui máquinas fracas do dual-track. É uma restrição de produto nova.

## Alternativas Consideradas

1. **Whisper self-hosted no servidor NORA** (endpoint no worker, cliente manda áudio). Rejeitado: é
   a Alternativa A do ADR 0009 (proxy server-side) de volta, agora sobre uma VM doméstica única sem
   GPU (ADR 0034). Mantém o broker, mantém o áudio saindo da máquina, não funciona offline e
   concentra streaming de áudio de todos os clientes num host residencial.
2. **Outro STT em nuvem** (Deepgram, AssemblyAI, OpenAI `gpt-4o-transcribe`). Menor esforço,
   preserva diarização e confidence. Rejeitado: paga por minuto, mantém o broker (ou pior: chave no
   cliente), mantém o áudio bruto saindo e **reintroduz a dependência de terceiro que o ADR 0034
   acabou de nos custar**. Fica como **plano B declarado** se a qualidade local em pt-BR for
   inaceitável. *Trigger:* WER do `small` q5 em pt-BR pior que o baseline de aceitação medido contra
   as transcrições de `data/synthetic/`.
3. **Manter o sidecar Python trocando só o SDK** (`faster-whisper` no lugar do Azure SDK). Diff
   menor, protocolo NDJSON e PyInstaller intactos. Rejeitado: preserva os dois runtimes que o
   próprio ADR 0008 já listava como negativa, e tirar o Python do bundle é justamente o ganho que
   paga a reescrita. **Aceitável como passo intermediário** se o binding Rust der problema em alguma
   plataforma.
4. **WhisperX / pyannote embarcado para recuperar diarização.** Rejeitado por construção: é batch,
   exige o áudio completo, e traz o Python de volta (pyannote é PyTorch — bundle e complexidade de
   empacotamento muito acima do que o sidecar atual custa). Reaberto apenas como pós-processamento
   offline (§Trade-off, item 1).
5. **BYO-key do usuário.** Já rejeitado pelo ADR 0009 (alternativas B e C) e nada mudou no
   raciocínio.

## Impacto no código (mapa do que sai)

| Sai | Onde |
|---|---|
| Sidecar Python inteiro | `apps/desktop/sidecar/` (`transcriber.py`, `protocol.py`, `build_sidecar.py`, testes) |
| `externalBin` do sidecar + job de build PyInstaller | config Tauri + `.github/workflows/ci.yml` |
| Broker e rate limit | `SpeechController`, `SpeechTokenService`, `AzureSpeechTokenBroker`, Bucket4j do speech |
| Config do broker | bloco `nora.speech.*` (`application.yml:175-183`) |
| Credenciais | `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION`, secret `azure-speech-key`, `AZURE_SPEECH_ENDPOINT` (esta já era inconsumida — ver ADR 0034) |
| Renovação de token | timer de 8 min em `speech_token.rs`, mensagem `refresh_token` do NDJSON |

| Fica | Observação |
|---|---|
| Tauri 2 + Rust | ADR 0008 mantido nesta parte |
| Captura de áudio (`cpal`, WASAPI loopback, PulseAudio/PipeWire, ScreenCaptureKit) | inalterada |
| Campo `track` no `TranscriptEvent` | passa a ser a **única** fonte de atribuição |
| `speaker_id` e `confidence` no contrato | mantidos como opcionais, agora **sempre nulos** |

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-08-07 | sys0xFF + Arquiteto NORA | Criação e aceite. Forçado pelo ADR 0034 (fim do recurso Azure Speech). Substitui o ADR 0009 integralmente e o ADR 0008 na parte do sidecar Python. Trade-off de diarização, confidence e taxonomia de erro registrado como perda explícita, não mitigada. |
