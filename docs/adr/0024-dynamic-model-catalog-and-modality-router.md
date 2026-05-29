# 0024 — Catálogo de modelos dinâmico + router por modalidade + resolução runtime

- Status: aceito
- Data: 2026-05-28
- Decisores: Co-arquitetos (Opus) + Stratfy (PO/dono)
- Estende: ADR 0004 (provider-agnóstico) — não o substitui; 0004 permanece a base. Relacionado: ADR
  0003 (JSON Schema strict), ADR 0022/0023.

## Contexto

ADR 0004 tornou o LLM provider-agnóstico, mas a escolha de modelo/provider é **estática por env var**
no deploy (`LLM_PROVIDER/LLM_BASE_URL/LLM_API_KEY/LLM_MODEL`), uma instância = um modelo. ADR 0004 §
Operacional admite que **custo é monitorado manualmente** e alerta que **structured output strict tem
suporte irregular fora do gpt-4o** (motivo de OpenAI ser default).

O control plane (ADR 0022) precisa de: catálogo de modelos com CRUD, **troca de modelo por serviço em
runtime sem redeploy**, e roteamento por **modalidade** (texto → modelo de texto; áudio/vídeo/imagem
→ modelo multimodal). Modelos de mai/2026 em jogo: DeepSeek V4 Flash (texto, barato), gpt-4o-mini
(texto+visão, strict first-class), Gemini 3.5 Flash (multimodal).

## Decisão

1. **Catálogo dinâmico em banco** (`llm_models`, banco de plataforma — ADR 0022) com CRUD via
   `/admin/platform/models`. Catálogo **fechado porém editável** (seeds: DeepSeek V4 Flash,
   gpt-4o-mini, Gemini 3.5 Flash). Cada modelo modela os eixos do ADR 0004 (`provider`, `baseUrl`,
   `model`, `modality`) + pricing (`priceInput/Output/CachedInputPerMTok`) + flag
   **`supportsStrictJsonSchema`**.
2. **Binding por serviço** (`llm_config`): cada serviço (`chat`, `analysis`, `multimodal`) aponta pra
   um modelo. Editável em runtime via `PUT /admin/platform/config/{service}`.
3. **Router por modalidade:** o serviço determina a modalidade. `analysis`/`chat` são texto;
   `multimodal` (áudio/vídeo/imagem, futuro) usa modelo `modality=multimodal`. Validação no binding:
   - `analysis` exige `supportsStrictJsonSchema=true` (protege o pipeline strict — ADR 0003);
   - `multimodal` exige `modality=multimodal`.
4. **Resolução em runtime com cache ~60s** (Caffeine `expireAfterWrite(60s)`): o consumidor lê
   `GET /internal/platform/llm-config?service=…`. Propagação de troca ≤ 60s, sem redeploy.
5. **Fallback SOFT, fail-soft pro env default:** se a config quebrar (plataforma off/fora, binding
   ausente, modelo desabilitado), o resolver devolve a config do **env default** (`LLM_*`) do serviço.
   **Nunca derruba chat/worker** — regra inviolável do design. O hot-path sempre recebe 200.
6. **Chave de API fora do catálogo** (ADR 0023 / decisão #C): `llm-config` devolve só
   `provider/model/baseUrl/enabled`; a chave é resolvida pelo consumidor via `provider → secret`. O
   catálogo (exposto na UI do operador) **nunca** guarda chave.
7. **Telemetria de custo** (`usage_events`): tokens × pricing do catálogo, recalculado server-side
   (catálogo é fonte da verdade de preço). Eventos `status=stub` não contam.

## Consequências

**Positivas:**
- Operador troca modelo por serviço sem deploy; realiza economia (ex.: chat→DeepSeek) na hora.
- Pricing centralizado ⇒ custo consistente, sem hardcode de preço espalhado.
- `supportsStrictJsonSchema` impede o operador de quebrar o pipeline de análise sem querer.
- ADR 0004 continua válido: o env default é o piso de fallback; nada quebra se a plataforma sumir.

**Negativas / trade-offs:**
- Trocar pra um provider **sem chave provisionada** exige deploy (a chave não está no catálogo).
  Aceito e documentado.
- Cache de 60s ⇒ troca não é instantânea (até 60s). Aceitável.
- Custo pode subestimar levemente em cenários de retry do SDK (tentativas anteriores não aparecem em
  `usage`) e em cache-hit (preço cache-hit aplicado só se conhecido). Documentado.
- Catálogo dinâmico é uma evolução da config estática do ADR 0004 — daí este ADR sucessor (0004
  imutável, referenciado).

## Alternativas Consideradas

1. **Manter env var estática (status quo ADR 0004)** — rejeitado: não permite troca em runtime nem
   telemetria automatizada de custo (gap que o ADR 0004 já admitia).
2. **Config dinâmica sem fallback (hard-fail)** — rejeitado: um erro no banco de plataforma derrubaria
   chat/worker pra todos os tenants. Inaceitável.
3. **Chave no catálogo (config completa via llm-config)** — rejeitado: expõe secret na UI do operador
   e no contrato HTTP. Chave fica fora (decisão #C).
4. **Cache longo / sem cache** — sem cache bate no DB no hot-path; cache longo atrasa demais a troca.
   60s equilibra (e casa com o padrão Caffeine já usado no `AuthRateLimiter`).

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-28 | Co-arquitetos + Stratfy | Criação. Estende ADR 0004. Seed de `chat` → DeepSeek V4 Flash (analysis fica gpt-4o-mini strict). Exceção consciente ao ADR 0014 autorizada. |
