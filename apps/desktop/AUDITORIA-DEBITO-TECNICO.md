# Auditoria de Débito Técnico — NORA Desktop

> Documento gerado a partir de uma auditoria multi-agente automatizada do app **desktop** (`apps/desktop/`).
> **Data:** 2026-05-29 · **Escopo:** apps/desktop/** (Rust/Tauri + React/TS + sidecar Python). `services/nlp-worker` ficou fora de escopo.

## Metodologia

- **12 agentes finders** varrendo o código por área (Rust audio/STT/core, TS overlay/pages/hooks/lib/components, Python sidecar) + cross-cutting (DRY, consistência de IPC Tauri, config/build).
- **Verificação adversarial** de cada achado: um segundo agente cético abria o arquivo no local citado e confirmava só se conseguisse apontar o código exato e o dano concreto — rejeitando nitpicks e falsos-positivos, e medindo o risco de cada fix quebrar comportamento.
- **Síntese**: deduplicação, agrupamento por arquivo e ordenação dos fixes.
- Custo: **169 agentes**, ~4,3M tokens, ~27 min.

## Sumário executivo

- **155 achados brutos → 135 confirmados** (20 rejeitados na verificação adversarial como falsos-positivos / nitpicks sem impacto).
- Deduplicados pela síntese para **~95 issues únicas** (40 duplicatas removidas) em **44 arquivos**.

**Por severidade:**

| Severidade | Qtd |
|---|---|
| 🔴 HIGH | 6 |
| 🟠 MEDIUM | 24 |
| 🟡 LOW | 105 |

**Por categoria:**

| Categoria | Qtd |
|---|---|
| logic-error | 27 |
| dry | 27 |
| dead-code | 19 |
| error-handling | 15 |
| tech-debt | 14 |
| professionalism | 11 |
| security | 7 |
| type-safety | 7 |
| gambiarra | 5 |
| cross-platform | 3 |

**Por risco do fix:**

| Risco | Qtd | Significado |
|---|---|---|
| safe | 92 | fix direto, preserva comportamento |
| needs-care | 42 | exige atenção/coordenação |
| risky | 1 | alto risco de regressão |

### Destaques de severidade alta (🔴)

- **Header da overlay usa data-tauri-drag-region — drag quebrado no WebKitGTK (Linux)** — `apps/desktop/src/components/overlay.tsx`
- **isAnalyzing do contexto nunca vira true — spinner da overlay está morto** — `apps/desktop/src/hooks/use-live-highlights.tsx`
- **console.log do response.body vaza accessToken/refreshToken em texto puro no console da webview** — `apps/desktop/src/lib/api-client.ts`
- **Overlay header usa data-tauri-drag-region (não funciona no WebKitGTK/Linux) em vez de startDragging()** — `apps/desktop/src/components/overlay.tsx`
- **Overlay header usa data-tauri-drag-region — janela inarrastavel no Linux (WebKitGTK)** — `apps/desktop/src/components/overlay.tsx`
- **URL da API de producao hardcoded como http://localhost:8080 e lida em compile-time via include_str!** — `apps/desktop/src-tauri/tauri.conf.json`

---

## Plano de correção (síntese)

Auditoria do desktop NORA: 135 achados confirmados deduplicados para ~95 issues unicas, agrupadas por arquivo. Duplicatas principais: overlay header drag (data-tauri-drag-region) tinha 4 entradas identicas (cross-platform high); api-client.ts console.log de tokens tinha 2; formatDuration duplicado overlay/dock tinha 3; DOCK_STORAGE_KEY/dock-pref tinha 3; getPendingCount/pending count inline tinha 3; NoraBars/NoraLogo DRY tinha 2; CSP unsafe-inline tinha 3; checkbox action item cosmetico tinha 2; await emit().catch() confuso tinha 2; ShaderOrb WebGL leak tinha 2; capabilities maximize/shell tinha 2; console.log de transcript em use-recording tinha 2; KIND_META/ICONS JSX.Element + icones SVG sobrepostos; live-trigger console.log apontava errado (era use-live-highlights, nao overlay). Highlights de severidade alta: (1) overlay header inarrastavel no Linux/WebKitGTK, (2) isAnalyzing nunca vira true (spinner morto), (3) console.log vaza access/refresh token no console da webview, (4) API base URL de producao hardcoded como localhost via include_str! em compile-time. Estrategia de ordem: primeiro criar os utils compartilhados (src/lib/format.ts, dock-prefs, prefs/storage, error-banner, spinner, chip, IconButton, feed-icons, keyframes em styles.css, useNow hook, secret keys, desktop-events, _spec_shared) para que os fixes pontuais depois possam importar deles sem reconflito; depois aplicar fixes isolados por arquivo; deixar refactors grandes/risky (split de overlay.tsx, NoraBars/NoraLogo, ShaderOrb effect re-split) por ultimo.

### Refactors cross-cutting (DRY — tocam múltiplos arquivos)

1. Criar src/lib/format.ts com formatDuration (versao hours-aware do overlay L70-76) e relTime (export SEPARADO, assinatura em ms). Consome: overlay.tsx (remove L70-76), dock-bar.tsx (remove L16-20, corrige overflow >1h). DEDUP de 3 achados.
2. Criar src/lib/dock-prefs.ts (DOCK_STORAGE_KEY='nora.dock.visible' + getDockVisible/setDockVisible, default true, '1'/'0', try/catch silencioso). Consome: overlay.tsx (L18,54-68), use-active-recording.tsx (IIFE em start), dock-bar.tsx (setItem ',0'). Preservar VALOR da chave byte-a-byte (storage persistido). DEDUP de 3 achados.
3. Criar src/lib/desktop-events.ts (EVENTS.DOCK_VISIBILITY_CHANGED='nora://dock-visibility-changed' + tipos + opcionalmente os outros eventos nora://: save-result, stop-and-save, cancel-recording, retry-save, rename-speaker, restart-recording, live-analysis-start, stt-error). Preservar VALORES byte-a-byte (canais runtime entre janelas). Pode coalescer com dock-prefs/storage keys num modulo de prefs unico.
4. Criar src/lib/prefs.ts com createLocalStore<T>(key, default, {parse, serialize}) generico tipado, absorvendo dock-prefs + highlights (default false) + overrides (JSON) do overlay. CAVEATS: default por instancia; formato serializado byte-identico; catch silencioso no set; persistMeta null->removeItem. (needs-care)
5. Mover @keyframes paletteFadeIn e paletteSlideIn do <style> inline de new-meeting-modal.tsx (L344-350) para src/styles.css (importado por todos os entrypoints). Corrige CloseConfirmDialog da overlay (animacao morta na janela non-main). Deletar o <style> inline. Durations ficam no shorthand de cada call site.
6. Adicionar tokens de cor reutilizaveis em styles.css: canais rgb (--danger-rgb, --success-rgb, --warn-rgb) para rgb(var(--x)/alpha) E --danger-soft-bg/--danger-soft-border. Substituir literais rgba/hex em overlay.tsx (L1397/1539/1786/1816/1908), overlay-notifications.tsx (L51/74/85), dock-bar.tsx (L187/189), settings.tsx (L35/36), login.tsx (L65), meeting-detail.tsx (card de falha). Validar pixel-identico. PR proprio de baixa prioridade. (needs-care)
7. Criar src/components/error-banner.tsx (padding '10px 12px', radius 8, fontSize 13, lineHeight 1.45, color var(--danger-ink), usando --danger-soft-bg/border). Consome: meetings.tsx, new-meeting-modal.tsx, login.tsx. Card de meeting-detail fica fora (componente diferente). Depende dos tokens de cor acima.
8. Criar src/components/spinner.tsx (Spinner presentacional: size, color, topColor, thickness; renderiza UM span com border/animation nora-spin). Consome 7 spans inline em meetings.tsx (L340-350), meeting-detail.tsx (L495-504), new-meeting-modal.tsx (L315-323). Preservar size/cores/thickness exatos por call site; sem wrapper extra.
9. Criar src/components/chip.tsx (props variant neutral|accent, size sm|md, borderRadius 999). Consome 3 chips de meeting-detail.tsx (L355-398, L420-424) e 1 de meetings.tsx (preservar 'sm'/'1px 8px'/fontSize 11). Manter paddings/fontSizes exatos via prop size.
10. Criar src/components/brand/feed-icons.tsx exportando DecisionIcon/NextStepIcon/ObservationIcon/TaskIcon (SVG 12x12 strokeWidth=2). Consome KIND_META (overlay.tsx L296-333) e ICONS (overlay-notifications.tsx L26-95). NAO unificar save-error icon (geometria diferente). WarnIcon opcional com props size/strokeWidth.
11. Trocar JSX.Element por React.ReactElement em overlay.tsx:296, overlay-notifications.tsx:22 E sidebar.tsx:9 (este esquecido pelo auditor). Garantir React importado. NAO usar React.ReactNode.
12. Criar hook src/hooks/use-now.ts (useNow(active, intervalMs=500) + opcional useElapsedSeconds(startedAt, active)) eliminando os force-render de relogio. Consome dock-bar.tsx (L64,L90-94) e use-live-transcript.tsx (L163-173, void tick). Preservar gating startedAt==null + updater funcional. DEDUP de 2 achados.
13. Criar src/lib/secrets.ts SECRET_KEYS const (incl. legacy azure-*) + tipo SecretKey via keyof. Trocar literais em auth.ts e settings.tsx. Pode coalescer com a centralizacao de storage keys (prefs/desktop-events). VALORES byte-identicos (keyring).
14. Extrair toUserMessage(err: unknown, fallback: string): string em src/lib/ (instanceof Error -> message; objeto com message string -> message; senao fallback; NUNCA JSON.stringify na UI; console.error antes). Consome os 6 sitios de extracao de erro (login.tsx L262-268 etc.).
15. Extrair focusOn/focusOff para src/components/ui/field.ts (React.FocusEvent<HTMLElement>). Consome 5 sites: meetings.tsx SearchBar, new-meeting-modal.tsx (trocar import), login.tsx (2 inputs), overlay.tsx. Manter transition no style base; NAO unificar inputCss (padding/fontSize divergem).
16. Criar classes .nora-icon-btn (+ :hover/:focus-visible) e variante --danger em styles.css; trocar handlers de hover inline em DockButton (dock-bar.tsx L22-59), titlebar.tsx ControlButton (L18-59), sidebar.tsx (L86-91,L120-127,L170-178,L188-195). Estado ativo via .is-active. NAO mexer em startDragging nem backgrounds solidos. Comecar por DockButton+ControlButton.
17. Extrair buildTranscript(lines, getSpeakerName) (map+join com prefix [speaker]) em use-recording.ts ou lib; usar em saveMeeting (L235-240) e fullTranscript (useMemo, L335-340). Behavior-preserving.
18. Extrair performSave(m) em use-active-recording.tsx (saveMeeting + emit save-result + toggle_overlay) compartilhado entre stopAndSave (L186-226) e retry-save (L115-154). NAO mover toggle_dock/stopRecording/isFinishing pra dentro.
19. RECONCILIACAO SHELL (resolver CONFLITO entre achados): decidir se shell:allow-open e tauri-plugin-shell ficam ou saem. Achados que querem REMOVER (stt_sidecar.rs dead-weight: lib.rs:63, capabilities L14, package.json:15, Cargo.toml:17; capabilities default.json shell:allow-open). Achados que querem USAR open() do plugin-shell (settings.tsx 'Abrir docs' L529, login.tsx 'Falar com vendas' L552). Recomendado: remover plugin-shell+shell:allow-open (peso morto; sidecar usa tokio::process::Command + externalBin) E converter 'Abrir docs'/'Falar com vendas' para <a href target=_blank>. MANTER shell:allow-execute? NAO — sidecar nao usa o plugin shell (verificar). NAO remover externalBin de tauri.conf.json:72. Aplicar este conjunto coeso antes dos fixes individuais de settings/login.
20. Extrair hook useBarsPhase(animate, animKey) + constantes HEIGHTS/BAR_W/BAR_GAP compartilhados entre nora-bars.tsx e nora-logo.tsx (state machine privada + reveal de texto dependente de phase==='done', delay 380ms). Preservar defaults divergentes (16 vs 20), cor fixa ink no logo, props active/color/dotPulse so no NoraBars. Validar 5 call-sites. DEDUP de 2 achados. (needs-care)
21. Limpeza de console.log de debug num unico passe (DEV-gate ou remover): use-recording.ts L68 (PRIORIDADE, vaza fala em hot path), L105/L108/L315; use-live-highlights.tsx L147/156/160; api-client.ts L50/L60 (L60 vaza tokens). MANTER todos console.error/warn de falhas reais.

### Ordem de aplicação recomendada

1. FASE 0 — RECONCILIAR CONFLITOS DE DECISAO antes de qualquer codigo: (a) reconciliacao shell (manter vs remover shell:allow-open/plugin-shell — define o fix de settings 'Abrir docs', login 'Falar com vendas', stt_sidecar.rs dead-weight, package.json, Cargo.toml, capabilities); (b) decidir se cache getCachedUser sai por inteiro (afeta api-client.ts, auth.ts, use-auth.tsx, bootstrapSession).
2. FASE 1 — CRIAR UTILS/MODULOS COMPARTILHADOS (sem trocar call sites ainda, so adicionar): styles.css (mover keyframes paletteFadeIn/paletteSlideIn + tokens de cor rgb/danger-soft + classes .nora-icon-btn); src/lib/format.ts; src/lib/desktop-events.ts + src/lib/dock-prefs.ts (ou prefs.ts unico); src/lib/secrets.ts SECRET_KEYS; src/lib/toUserMessage; src/components/ui/field.ts (focusOn/focusOff); src/components/spinner.tsx; src/components/chip.tsx; src/components/error-banner.tsx; src/components/brand/feed-icons.tsx; src/hooks/use-now.ts; helper buildTranscript; sidecar/_spec_shared.py.
3. FASE 2 — APLICAR OS CONSUMIDORES DOS UTILS (DRY, trocar call sites para importar): formatDuration em overlay.tsx+dock-bar.tsx; dock-prefs/storage keys nos 3 arquivos; desktop-events; SECRET_KEYS; spinner/chip/error-banner/field/feed-icons nos seus call sites; useNow em dock-bar.tsx+use-live-transcript.tsx; JSX.Element->React.ReactElement nos 3 arquivos; classes .nora-icon-btn; CloseConfirmDialog keyframes (so deletar <style> inline). Rodar npm run typecheck.
4. FASE 3 — FIXES DE SEVERIDADE ALTA isolados: overlay header drag (data-tauri-drag-region->startDragging, usar helper de drag se extraido); isAnalyzing via evento live-analysis-start (live_analysis.rs + use-live-highlights.tsx); api-client.ts:60 nao logar body; tauri.conf.json API base URL via build.rs/env.
5. FASE 4 — FIXES MEDIUM isolados (frontend): handleCancel stopping + reset; FeedRow uid (use-live-highlights merge + buildFeed); isTransient (meetings.ts) + meeting_id ausente (commands.rs upload_meeting, coordenados); bootstrapSession .finally; backpressure observability (commands.rs); stt-error listener na overlay; reqwest client compartilhado (commands.rs + live_analysis.rs); ShaderOrb WebGL cleanup; useEffect retry-worker (use-recording.ts).
6. FASE 5 — FIXES MEDIUM isolados (sidecar Python): confidence via result.json + speaker_id direto (mesmas linhas, juntos); restart guard _stopped + reset _restart_count + error_code_map BadRequest (mesma fn _on_canceled, juntos); locks/race (coordenar com restart).
7. FASE 6 — FIXES LOW isolados (Rust/sidecar): audio_capture system_tx unwrap + dupla resolucao; system_audio leak WASAPI + resampler unwrap (mesma regiao, juntos) + carry buffer + stop_flag/join Linux + remover sample_rate_hint; commands.rs canal system + eprintln + upload struct; lib.rs SecretStore .manage + IPC orfa; secrets.rs hardening (com CSP); http_proxy case-insensitive + body GET (coordenar com api-client body); transcriber constantes magicas + restart backoff; __main__ _audio_seq + signal handler + audio errors; protocol aliases; logging_setup idempotente; build_sidecar shutil; specs import os; pyproject pin; Cargo.toml deps; package.json react-router-dom; tsconfig tsc --noEmit; capabilities maximize/shell; tauri.conf $schema; CSP hardening (com validacao dev).
8. FASE 7 — FIXES LOW frontend isolados: overlay consolidar useEffect + log catches + cores hardcoded (com tokens) + AudioConfigSection applying; meeting-detail checkbox/footer/tags/confidence; meetings/login/settings (botoes sem handler, cores, migracao azure, stealth feedback, isSupported tag); chat key + placeholder TODO; use-recording zumbi/deps/pendingCount/transcript/stopRecording; use-active-recording emit logging/performSave/useMemo deps; use-live-transcript track fallback; use-live-highlights cast/dedup; auth.ts roles/isAuthenticated/auth-expired emit; pending-meetings validacao/dead-code; sidebar isActive/JSX; App.tsx NoraBars loading.
9. FASE 8 — REFACTORS GRANDES/RISKY por ultimo (PRs dedicados): NoraBars/NoraLogo useBarsPhase (needs-care); _spec_shared.py consumidores (needs-care, validar build por plataforma); prefs.ts createLocalStore generico (needs-care); split de overlay.tsx monolitico (risky, NAO acionar — opcional).

---

## Achados detalhados (todos os 135 confirmados)

Cada achado traz: localização, categoria, severidade, risco do fix, descrição do problema, fix recomendado (já refinado pela verificação) e a justificativa da verificação.


## 1. Rust — src-tauri

### `apps/desktop/src-tauri/src/commands.rs`  ·  6 achado(s)

#### 1. 🟠 MEDIUM — reqwest::Client::new() criado por chamada em vez de reusar o client pooled de http_proxy

- **Local:** `L284, e live_analysis.rs L98`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** http_proxy.rs ja mantem um `static CLIENT: OnceLock<Client>` com connect_timeout/timeout/pool configurados (http_client()). Porem upload_meeting (commands.rs L284) e analyze_live (live_analysis.rs L98) chamam `reqwest::Client::new()` a cada invocacao. Cada Client novo aloca seu proprio pool de conexoes e estado TLS, descartado logo apos o request — desperdicio de recurso, sem reuso de conexao, e sem os timeouts configurados (o Client::new() default nao tem connect_timeout). Tres clients reqwest distintos no mesmo crate e debito de DRY e de performance.

**Fix recomendado:** Tornar o pool compartilhado acessivel e reusa-lo. Passos: (1) em lib.rs trocar `mod http_proxy;` por `pub(crate) mod http_proxy;` e em http_proxy.rs tornar a fn `pub(crate) fn http_client() -> &'static Client`. (2) Em upload_meeting (commands.rs:284) e analyze_live (live_analysis.rs:98) substituir `reqwest::Client::new()` por `crate::http_proxy::http_client()`. CAVEATS importantes: (a) analyze_live deve PRESERVAR o timeout de 15s do path live — manter o `.timeout(Duration::from_secs(15))` por-request (reqwest permite override por request sobre o timeout do builder de 30s), nao remover; (b) upload_meeting passaria a herdar o timeout de 30s do pool — isso e desejavel (hoje nao tem timeout algum), mas como uploads multipart podem ser grandes, considere um override por-request mais generoso (ex: .timeout(120s)) pra nao cortar uploads legitimos; (c) speech_token.rs:35 pode opcionalmente migrar tambem pro pool com override de 8s, mas e secundario e fica como follow-up. Mudancas sao pequenas e localizadas; risco principal e regredir timeouts, por isso revisar cada call site individualmente em vez de aplicar cegamente.

<details><summary>Justificativa da verificação</summary>

Confirmado nos tres pontos citados. http_proxy.rs:22-32 mantem um pool reutilizavel via OnceLock (http_client()) com connect_timeout(5s), timeout(30s) e pool_max_idle_per_host(4). Em contraste, commands.rs:284 (upload_meeting) e live_analysis.rs:98 (analyze_live) chamam reqwest::Client::new() a cada invocacao. Cada Client::new() aloca pool de conexoes + estado TLS proprios, descartados ao fim do request -> sem reuso de keep-alive e sem connect_timeout (o default nao tem). commands.rs:284 e o pior caso: nao tem timeout NENHUM aplicado (verifiquei L284-291, a chamada nao usa .timeout()), entao um backend lento pode pendurar o upload indefinidamente. live_analysis.rs aplica .timeout(15s) por request (L104) mas ainda sem connect_timeout. Existe ainda um quarto Client em speech_token.rs:35 (builder com timeout 8s) — entao sao na verdade 4 construcoes distintas no crate, reforcando o debito DRY. Achado real e acionavel, alem do beneficio de robustez (timeout faltando no upload). Nao e bug funcional (tudo funciona hoje), por isso severity medium e honesta — high nao se justifica.

</details>

#### 2. 🟠 MEDIUM — Amostras de audio descartadas silenciosamente sob backpressure (try_send com _ ignorado)

- **Local:** `L107-110 e L116-119 (bridge tasks try_send)`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** As bridge tasks fazem `let _ = sidecar.try_send(samples);`. try_send em canal bounded retorna Err(Full) quando o buffer do sidecar enche (ex: sidecar Python lento, CPU saturada) e os samples sao perdidos sem nenhum log, contador ou metrica. Em uma sessao de transcricao isso vira buracos no audio/transcript que ninguem percebe ate o resultado vir errado. Tambem engole o caso Closed (sidecar morreu) sem reagir.

**Fix recomendado:** Manter o comportamento de drop-on-backpressure (correto pra audio realtime), mas adicionar observabilidade e tratar Closed. Nas duas bridge tasks (commands.rs L107-110 e L116-119), trocar `let _ = sidecar.try_send(samples);` por algo como:

```rust
use tokio::sync::mpsc::error::TrySendError;
let mut dropped = 0u64;
while let Some(samples) = mic_rx.recv().await {
    match sidecar.try_send(samples) {
        Ok(()) => {}
        Err(TrySendError::Full(_)) => {
            dropped += 1;
            #[cfg(debug_assertions)]
            if dropped % 50 == 0 {
                eprintln!("[bridge:mic] backpressure: {} chunks de audio descartados", dropped);
            }
        }
        Err(TrySendError::Closed(_)) => {
            #[cfg(debug_assertions)]
            eprintln!("[bridge:mic] sidecar canal fechado — encerrando bridge");
            break; // sidecar morreu: parar de drenar mic_rx inutilmente
        }
    }
}
```

Caveats: (a) o `eprintln!` segue o padrao do arquivo (debug_assertions gated) — nao introduzir crate de logging novo so por isso; (b) emitir telemetria de drop pro frontend (sugestao do auditor) e opcional e fora do minimo — fica como follow-up, nao bloquear o fix nisso; (c) aumentar a capacidade do canal de 100 e um tuning ortogonal e de baixo valor isolado (apenas adia o estouro), pode ser deixado de fora ou ajustado com medicao real. Risco do fix: baixo — nao altera o comportamento intencional (drop em vez de bloquear), so adiciona log e encerra a bridge quando o sidecar ja caiu (caminho que hoje so gasta CPU).

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. commands.rs L109 (`let _ = sidecar.try_send(samples);`) e L118 (`let _ = tx.try_send(samples);`) descartam o Result de um canal bounded (capacidade 100, definido em stt_sidecar.rs L158). Sob backpressure (sidecar Python lento / CPU saturada) o canal enche, try_send retorna Err(Full) e o sample de audio e jogado fora sem log, contador nem metrica — viram buracos de audio/transcript invisiveis num produto cujo core e transcricao. O caso Err(Closed) (audio_rx dropado quando a writer task/run_sidecar termina, ex: sidecar morreu) tambem e engolido: a bridge continua drenando mic_rx e descartando tudo ate o stop_recording fechar o canal upstream — trabalho inutil sem reagir. AJUSTE em duas coisas que o auditor superdimensionou: (1) o DROP sob backpressure em si e uma decisao defensavel num pipeline de audio realtime — usar send().await em vez de try_send so empurraria o backpressure pra callback de captura (audio_capture.rs L205 ja usa try_send nao-bloqueante de proposito), entao o conserto NAO deve ser trocar pra send().await; o problema real e a AUSENCIA de observabilidade, nao o drop. (2) Severidade medium e justa mas no piso da faixa: so se materializa sob saturacao, nao e crash. Nao e high.

</details>

#### 3. 🟠 MEDIUM — meeting_id ausente vira string vazia silenciosa em vez de erro

- **Local:** `L305-306 (upload_meeting response parsing)`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Apos um POST 2xx, `json["id"].as_str().unwrap_or("")` retorna meeting_id="" se o campo `id` estiver faltando ou nao for string. O frontend recebe um UploadMeetingResponse 'de sucesso' com meeting_id vazio e nao consegue distinguir upload real de resposta malformada — leva a navegacao/polling pra um meeting inexistente. O mesmo padrao mascara processingStatus com fallback 'PENDING'.

**Fix recomendado:** Em commands.rs, validar o campo obrigatorio `id` antes de construir a resposta: `let meeting_id = json["id"].as_str().ok_or_else(|| format!("Resposta sem 'id': {}", body_text))?.to_string();` e usar esse valor no struct. Manter `processing_status: json["processingStatus"].as_str().unwrap_or("PENDING").to_string()` (campo legitimamente opcional). Risco baixo: so muda comportamento para respostas genuinamente malformadas (ja quebradas hoje); backend bem-comportado sempre devolve `id`. Sem impacto cross-platform (codigo Rust puro, fora do WebKitGTK). Nota: o erro retornado e uma String, que isTransient() em meetings.ts trata como transient -> ira retentar ate maxRetries e entao propagar o erro real ao usuario via saveError, o que e o comportamento desejado (falha visivel em vez de sucesso fantasma). Opcional/defensivo no frontend: trocar o guard em use-active-recording.tsx:176 para um cleanup que tambem limpe isFinishing/persistMeta em caso de id invalido, mas isso e secundario ao fix no Rust.

<details><summary>Justificativa da verificação</summary>

Confirmado o codigo exato em apps/desktop/src-tauri/src/commands.rs:305 — `meeting_id: json["id"].as_str().unwrap_or("").to_string()`. Apos um POST 2xx com body sem o campo `id` (ou `id` nao-string), o comando retorna um UploadMeetingResponse semanticamente "de sucesso" com meeting_id vazio, mascarando uma resposta malformada. Dano concreto rastreado no desktop: o "" propaga via uploadTranscript() -> setSavedMeetingId("") em use-recording.ts:263. AJUSTE no sintoma descrito pelo auditor: em use-active-recording.tsx:176 o guard `if (!recording.savedMeetingId) return;` trata "" como falsy, entao a navegacao para #/meetings/ NAO acontece (o auditor errou ao dizer que navega/poll pra meeting inexistente). O dano REAL e diferente e igualmente ruim: o effect faz early-return ANTES de chamar persistMeta(null) (L181) e setIsFinishing(false) (L182), deixando a UI travada em estado de "finishing/salvo" enquanto o overlay ja recebeu nora://save-result {ok:true} — sucesso reportado + estado inconsistente, sem feedback de erro ao usuario. O fallback de processingStatus pra "PENDING" e benigno (PENDING e um default legitimo e o campo e plausivelmente opcional), entao deve ser mantido. Categoria error-handling/swallow confirmada; severidade medium honesta (depende de backend devolver body malformado num 2xx, mas quando ocorre falha silenciosa e dificil de diagnosticar).

</details>

#### 4. 🟡 LOW — Canal de system audio (system_tx/system_rx) alocado mesmo quando capture_system=false

- **Local:** `L60-71 e L86-123 (criacao incondicional do canal system)`
- **Categoria:** dead-code · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** O par `(system_tx, mut system_rx)` e sempre criado (L62), mas quando capture_system e false o sink recebe None (L66-70), o system_sidecar e None (L100-102) e nenhuma bridge consome system_rx (L114-123). O receiver fica orfao e e dropado no fim. Nao quebra nada, mas e alocacao morta e confunde a leitura (parece que system audio esta sempre ligado).

**Fix recomendado:** Manter tx e rx pareados num unico Option pra nao threadear duas variaveis por escopos diferentes. Em vez de L62 + L66-70, fazer:\n\n```rust\nlet (mic_tx, mut mic_rx) = tokio::sync::mpsc::channel::<Vec<i16>>(100);\nlet system_chan = if capture_system {\n    Some(tokio::sync::mpsc::channel::<Vec<i16>>(100))\n} else {\n    None\n};\nlet (system_tx, system_rx) = match system_chan {\n    Some((tx, rx)) => (Some(tx), Some(rx)),\n    None => (None, None),\n};\nlet sinks = CaptureSinks { mic_tx, system_tx };\n```\n\nDepois o bridge (L114-123) passa a casar tambem o rx:\n```rust\nlet system_bridge = match (system_sidecar.as_ref(), system_rx) {\n    (Some(sidecar), Some(mut rx)) => {\n        let tx = sidecar.audio_tx.clone();\n        Some(tokio::spawn(async move {\n            while let Some(samples) = rx.recv().await {\n                let _ = tx.try_send(samples);\n            }\n        }))\n    }\n    _ => None,\n};\n```\n\nAssim quando `capture_system == false` nenhum canal e alocado e nao ha rx orfao. CAVEAT: e refatoracao de baixo retorno (ganho ~cosmetico) num caminho sensivel de audio — so vale se ja for mexer nessa funcao por outro motivo; senao deixar como esta e aceitavel. Rodar `cargo build`/clippy e um teste manual de gravacao com e sem system audio depois.

<details><summary>Justificativa da verificação</summary>

Confirmado nos fatos. Em commands.rs L62 o par `(system_tx, mut system_rx)` e criado incondicionalmente. Quando `capture_system == false`: L66-70 poe `sinks.system_tx = None`, L100-102 deixa `system_sidecar = None`, e L114-123 (`if let Some(ref sidecar) = system_sidecar`) nao entra, entao nada consome `system_rx`. O `system_tx` e dropado no fim do bloco do `sinks` e o `system_rx` no fim da funcao — receiver/sender orfaos. Confere com o contrato em audio_capture.rs L19-24 (CaptureSinks.system_tx e Option).\n\nAjusto a caracterizacao em dois pontos: (1) o impacto e desprezivel — e um mpsc bounded(100) de Vec<i16> que nunca recebe nada; aloca um ring buffer pequeno (centenas de bytes) liberado no retorno da funcao. Sem leak, sem zombie, sem bug funcional. `low` esta correto, mas e basicamente cosmetico. (2) A frase \"parece que system audio esta sempre ligado\" e exagerada: o padrao `Some(system_tx)/None` em L66-70 e o `if capture_system` em L86-102 ja deixam claro que system audio e condicional; so a alocacao do canal e que e incondicional.\n\nRisco do fix: needs-care. `system_tx` e consumido no bloco do `sinks` (L64-71) e `system_rx` no bloco do `system_bridge` (L114-123) — escopos distintos. Mover a criacao pra dentro de um unico `if` exige declarar previamente algo como `let system_rx: Option<Receiver<...>>` e threadear o rx ate o bridge, ou empacotar tx+rx num Option<(tx,rx)>. Como isso mexe no caminho de captura de audio, um deslize (dropar o rx antes do bridge, capacidade trocada) pode quebrar silenciosamente a captura de system audio. Vale fazer com cuidado e teste manual, nao e um find-and-replace trivial.

</details>

#### 5. 🟡 LOW — Excesso de eprintln de debug em start_recording (logging ad-hoc, sem framework)

- **Local:** `L34-41, L127-129, L132-133, L144-156, L178-179, L199-201`
- **Categoria:** professionalism · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** start_recording/stop_recording estao cheios de blocos `#[cfg(debug_assertions)] eprintln!(...)` granulares ('start_recording called', 'device_name', 'calling capture.start()...', 'capture.start FAILED', 'start_recording returning ok'). O mesmo padrao se repete em quase todos os modulos (stealth_mode, http_proxy, stt_sidecar). E println-driven debugging deixado no codigo; sem niveis, sem alvos, polui os fluxos e dificulta manutencao. Tokens sensiveis nao sao logados aqui, mas a densidade indica falta de logging estruturado.

**Fix recomendado:** Nao fazer o refactor grande agora (adotar tracing/log no crate inteiro) — custo/risco alto pra ganho ~zero, ja que tudo e #[cfg(debug_assertions)] e some no release. Se quiser higienizar com baixo risco e sem nova dependencia: (a) consolidar os blocos granulares de start_recording (L34-41 e os spalhados) num unico macro/helper de debug local (ex: `macro_rules! dbg_log` ou `fn dlog(args)` gated por cfg) pra reduzir ruido e centralizar formato; (b) remover os logs puramente narrativos de baixo valor ("calling capture.start()...", "start_recording returning ok") mantendo apenas os pontos de erro real (failed to lock, capture.start FAILED). Se/quando houver apetite pra observabilidade de verdade no desktop, ai sim trocar por `log` + impl simples num PR dedicado e fora do escopo congelado. Itens (a)/(b) sao mecanicos mas exigem cuidado de manter o gating cfg(debug_assertions) intacto pra nao vazar logs em release.

<details><summary>Justificativa da verificação</summary>

O codigo citado existe exatamente como descrito. commands.rs tem 12 eprintln! e os offsets batem: L34-41 (bloco no inicio de start_recording: "called", device_name, language, capture_system_audio, system_audio_device), L127-129 (falha ao lock state), L132-133 ("calling capture.start()..."), L144-156 (capture.start FAILED + capture started ok), L178-179 ("start_recording returning ok"), L199-201 (stop_recording stopping sidecar). O padrao realmente se repete: stt_sidecar.rs=19, stealth_mode.rs=9, http_proxy.rs=2; total 78 no crate, e nao ha tracing/log no Cargo.toml. PORÉM o impacto concreto e baixo e o auditor superdimensiona: (1) TODOS estao atras de #[cfg(debug_assertions)], logo sao removidos do build release — zero poluicao/perf/leak pro usuario final; (2) o proprio achado confirma que nenhum token sensivel e logado; (3) sao auxilios de debug intencionais num stack Tauri/WebKitGTK multi-window que e dificil de debugar. É um nit real de profissionalismo (logging ad-hoc sem framework), nao um bug nem risco. Por isso: confirmado mas rebaixado/ajustado, severidade low. O fix proposto (adotar tracing/log) e um refactor multi-arquivo que adiciona dependencia e toca todos os modulos sem nenhum ganho funcional pro que ja e dead-code em release — risco de regressao desproporcional ao valor, e colide com o congelamento de escopo (ADR 0014).

</details>

#### 6. 🟡 LOW — Upload forca conteudo via String UTF-8 e mime hardcoded text/plain

- **Local:** `L217-227 (UploadMeetingRequest.file_content) e L273, L281`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** file_content e tipado como String e convertido com into_bytes() (L273), entao qualquer arquivo nao-UTF-8 ja foi corrompido/rejeitado pelo serde antes de chegar aqui, e o mime e fixo em 'text/plain' (L281) independentemente do file_name. Para o caso atual (transcript de texto) funciona, mas o nome 'file_content'/'file_name' sugere upload generico que de fato nao suporta binarios e duplica todo o payload em memoria (String -> Vec<u8>).

**Fix recomendado:** Tratar apenas como melhoria opcional de clareza, nao como tech-debt acionavel agora. Se quiser deixar a intencao explicita sem mexer no contrato cross-window: renomear file_content/file_name para transcript_text/transcript_file_name (atualizando o mapeamento camelCase em meetings.ts L92-93 e use-recording.ts L255-256), e opcionalmente derivar o mime de request.transcript_format (TXT -> text/plain) em vez de hardcode, ja deixando pronto pra formatos futuros. NAO migrar para base64/bytes nem prometer upload binario — isso seria scope creep (ADR 0014) e a ponte IPC do Tauri e UTF-8/JSON de qualquer forma. Cuidado ao renomear: os nomes em camelCase precisam casar exatamente entre o struct serde (rename_all="camelCase") e os tres pontos do front (meetings.ts UploadTranscriptRequest, o objeto enviado ao invoke, e pending-meetings.ts L8-10).

<details><summary>Justificativa da verificação</summary>

Os fatos do codigo conferem: em commands.rs L225 file_content e String, L273 usa into_bytes(), L281 mime fixo em "text/plain". Mas a gravidade descrita esta inflada. (1) Nao ha bug: o unico chamador e use-recording.ts L254-256, que sempre envia transcriptFormat:"TXT", fileContent como string JS e fileName .txt. A propria ponte IPC do Tauri e JSON/UTF-8, entao o conteudo SEMPRE chega como texto UTF-8 valido e text/plain e sempre correto no fluxo real. Nao existe corrupcao, rejeicao ou mime errado em nenhum caminho de producao. (2) A alegacao de "duplica payload em memoria (String -> Vec<u8>)" esta factualmente errada: into_bytes() consome a String e reaproveita o mesmo buffer, sem copia. (3) "Nao suporta binarios" e verdade trivial mas non-issue: nao existe feature de upload binario, e ADR 0014 fecha o escopo v1 (defer scope creep) — propor contrato base64/bytes seria adicionar escopo. Sobra apenas um nit de clareza de naming: nomes genericos file_content/file_name + mime hardcoded que por acaso casa com o unico formato. Impacto funcional zero.

</details>

### `apps/desktop/src-tauri/src/secrets.rs`  ·  1 achado(s)

#### 7. 🟠 MEDIUM — secret_get/secret_set expostos ao renderer permitem ler o access-token do JS

- **Local:** `L66-79 (secret_set / secret_get expostos ao renderer)`
- **Categoria:** security · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** secret_get e um comando invocavel pelo webview e o frontend de fato o usa (apps/desktop/src/lib/secrets.ts L8 invoca 'secret_get'). Isso significa que qualquer codigo executando no renderer (incluindo XSS via 'unsafe-inline' habilitado na CSP do tauri.conf.json) consegue ler o access-token/refresh-token em texto puro do keychain. O http_proxy foi cuidadosamente desenhado pra injetar o Bearer no lado Rust (e ate filtra o header authorization) justamente pra NAO expor o token; secret_get burla esse modelo de ameaca. ALLOWED_KEYS limita as chaves, mas access-token e uma delas.

**Fix recomendado:** Tratar em duas frentes, da mais barata/segura pra mais invasiva: (1) PRIORIDADE e baixo risco — endurecer a CSP em apps/desktop/src-tauri/tauri.conf.json L61: remover 'unsafe-inline' de script-src (idealmente migrar inline scripts/handlers pra arquivos e, se preciso, usar nonce/hash). Isso corta o principal vetor que transforma 'renderer le token' em 'qualquer script injetado le token', sem mexer no fluxo de auth. Cuidado: validar que o bundle Vite nao gera scripts inline; testar a janela main, overlay e dock apos a mudanca. (2) Reduzir a superficie de secret_get pros tokens, com cuidado por causa do acoplamento atual: expor um comando dedicado tipo auth_session_claims que roda no Rust, le access-token do SecretStore, decodifica o JWT e devolve so {roles, exp, userId} (substituindo parseJwtRoles/getTokenExpirationMs no renderer); e mover a orquestracao de refresh (POST /auth/refresh com o refresh-token como Bearer) pra um comando Rust, ja que o http_proxy nao injeta o refresh-token. Com isso o renderer nunca mais precisa de secret_get('access-token') nem ('refresh-token'), e da pra remover essas duas chaves do caminho do renderer (ou validar no proprio secret_get que essas chaves so sejam legiveis internamente). Manter secret_has pro 'estou logado?'. NAO mexer nos padroes cross-platform (startDragging, backgrounds solidos) — sao ortogonais a isso.

<details><summary>Justificativa da verificação</summary>

Confirmado nos arquivos citados. secret_get e um comando Tauri exposto ao renderer (secrets.rs L71-74, registrado em lib.rs L89) e o frontend o usa pra ler access-token e refresh-token em texto puro (auth.ts L90, L152, L174, L203). O modelo de ameaca que o achado descreve e REAL e ate documentado no codigo: http_proxy.rs L89-93 injeta o Bearer no lado Rust a partir do SecretStore, e L7-18 coloca 'authorization' em FORBIDDEN_HEADERS de proposito pra o renderer NAO conseguir manipular o token. Ou seja, a intencao explicita era manter o token dentro do processo Rust — e secret_get fura exatamente esse modelo. O amplificador citado tambem confere: tauri.conf.json L61 tem script-src 'self' 'unsafe-inline', entao um XSS leria access-token + refresh-token e os exfiltraria direto do keychain. ALLOWED_KEYS (L5-9) limita as chaves mas inclui justamente as tres sensiveis. POReM ajusto duas imprecisoes do achado: (1) a sugestao de 'restringir secret_get a chaves nao-token' nao e drop-in safe — o frontend HOJE depende do raw token em JS pra logica legitima: parseJwtPayload/parseJwtRoles decodificam o JWT pra extrair roles e expiry (auth.ts L15-44) e refreshAccessToken manda o refresh-token como Bearer pro /auth/refresh (L101). Logo o fix exige mover parsing/refresh pro Rust ou criar comandos dedicados; nao e so apagar um comando. (2) Severidade medium e honesta: e defesa-em-profundidade (precisa de XSS pra explorar, e o keychain ja protege em disco), nao um vazamento direto. Nao e high. O ganho de maior valor/menor risco e endurecer a CSP.

</details>

### `apps/desktop/src-tauri/src/stt_sidecar.rs`  ·  5 achado(s)

#### 8. 🟠 MEDIUM — Evento 'stt-error' emitido pelo Rust nao tem nenhum listener — erro de STT some sem feedback

- **Local:** `L491 (app.emit("stt-error", &json))`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** stt_sidecar.rs L491 emite 'stt-error' quando o sidecar Azure Speech reporta falha, mas nenhum listen('stt-error') existe em todo apps/desktop/src (grep confirma NONE). O erro de transcricao e completamente engolido: o usuario continua vendo a overlay 'gravando' enquanto o STT esta quebrado, sem nenhum toast/aviso. Contrasta com 'recording-status', 'transcript', 'live-analysis', 'live-analysis-telemetry' e 'clear-highlights' que todos tem listener.

**Fix recomendado:** Adicionar um listen("stt-error") na overlay (onde o sistema de notificacoes ja vive — overlay.tsx ja usa useNotifications com pushNotification, L1055-1056) que dispare pushNotification em falha de STT. CORRECAO IMPORTANTE ao fix proposto: NAO existe variant "error" no tipo NotificationVariant (overlay-notifications.tsx:3-9 = "info"|"decision"|"action"|"observation"|"task"|"warn"). Usar variant: "warn" (estilizado com --danger-ink), senao o TypeScript nao compila. Mapear o payload do evento para mensagem util, ex: pushNotification({ variant: "warn", title: "Falha na transcricao", body: json.message ?? json.code ?? "Erro desconhecido no STT" }) — o payload e o json bruto repassado pelo Rust contendo {type,session_id,code,message}. Idealmente registrar o listener junto dos demais (em use-live-transcript.tsx ou direto na overlay) usando o mesmo padrao attach()/UnlistenFn com cleanup, para nao vazar listener no unmount. Opcionalmente setar tambem um estado de erro visivel/parar o indicador de "gravando". Risco: safe — apenas adiciona um novo listener + chamada num sistema ja existente; nao toca em nenhum dos padroes intencionais cross-platform (startDragging, backgrounds solidos, decorations:false).

<details><summary>Justificativa da verificação</summary>

Confirmado. apps/desktop/src-tauri/src/stt_sidecar.rs:491 emite `app.emit("stt-error", &json)` quando o sidecar reporta `type:error`, e grep em todo apps/desktop/ retorna SO essa unica ocorrencia — nenhum listen("stt-error") existe (verifiquei use-live-transcript.tsx, use-live-highlights.tsx, use-recording.ts, dock-bar.tsx, overlay.tsx). Por contraste, todos os outros eventos citados tem listener confirmado: "transcript" (use-live-transcript.tsx:64, use-recording.ts:67), "recording-status" (use-live-transcript.tsx:109, use-recording.ts:96, dock-bar.tsx:66), "clear-highlights" (use-live-transcript.tsx:104, use-live-highlights.tsx:101), "live-analysis" (use-live-highlights.tsx:83), "live-analysis-telemetry" (use-live-highlights.tsx:96). O dano e concreto e nao-teorico: o sidecar Python emite ErrorMessage em multiplos caminhos reais de falha — falha ao iniciar sessao, falha no refresh de token, falha ao processar audio, falha ao iniciar/reiniciar o transcriber, JSON/mensagem invalida (__main__.py L37/64/77/88/111/165/174/183; transcriber.py L69/159) — com codes AUTH_FAILED|QUOTA|NETWORK|UNKNOWN (protocol.py:63, README.md:23). O Rust encaminha todos corretamente como "stt-error", mas o React descarta tudo. Resultado: em falha de auth/quota/rede a overlay continua mostrando "gravando" sem nenhum feedback, e a transcricao nunca aparece sem o usuario entender o porque. Severidade medium (degrada UX em falha real, nao corrompe dados). NOTA: olhei apps/desktop/sidecar/ apenas para confirmar que o caminho de erro e real — esse sidecar esta em escopo (apps/desktop/**); o componente fora de escopo era services/nlp-worker.

</details>

#### 9. 🟠 MEDIUM — Plugin shell e capabilities shell:* sao dead weight — sidecar e spawnado via tokio::process::Command

- **Local:** `lib.rs L63 (tauri_plugin_shell::init) + capabilities/default.json L14-20 + stt_sidecar.rs L6`
- **Categoria:** tech-debt · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** lib.rs registra tauri_plugin_shell::init() e default.json concede shell:allow-open + shell:allow-execute(binaries/nora-stt-sidecar, sidecar:true). Mas: (1) o sidecar e iniciado via `tokio::process::Command` (stt_sidecar.rs L6), NAO pelo mecanismo .sidecar() do plugin shell — grep nao acha nenhum shell()/.sidecar()/.command() no Rust; (2) nenhum open() do plugin-shell e chamado no JS (nenhum import de @tauri-apps/plugin-shell no source). Logo o plugin shell, a dep JS @tauri-apps/plugin-shell (package.json L15), o crate tauri-plugin-shell (Cargo.toml L17) e ambas as permissoes shell:* sao todas inuteis. shell:allow-execute com args:true e a permissao mais ampla do arquivo e nao protege nada que esteja em uso.

**Fix recomendado:** Remover o peso morto, MANTENDO o bundle do binario: (1) lib.rs:63 — apagar a linha .plugin(tauri_plugin_shell::init()); (2) capabilities/default.json — remover "shell:allow-open" (L14) e todo o objeto shell:allow-execute (L15-20); (3) package.json:15 — remover "@tauri-apps/plugin-shell"; (4) Cargo.toml:17 — remover tauri-plugin-shell = "2". CAVEAT CRITICO (nao mexer): NAO remover "externalBin": ["binaries/nora-stt-sidecar"] de tauri.conf.json:72 — e ELE, nao o plugin shell, que faz o Tauri empacotar/assinar o binario do sidecar no app; o tokio::process::Command resolve esse mesmo binario em runtime (resolve_sidecar_binary). VERIFICACAO pos-fix (por isso needs-care, toca 4 arquivos de build): rodar `npm install` (atualiza lock JS) + `cargo build` no src-tauri (atualiza Cargo.lock) e confirmar que compila e que `cargo tauri build` ainda inclui binaries/nora-stt-sidecar no bundle. Alternativa (nao recomendada agora): migrar o spawn pra app.shell().sidecar(...) pra ganhar cleanup automatico no exit — mas o codigo ja tem cleanup defensivo (Drop em SidecarHandle:33-45 + child.kill() em :523), entao a migracao nao agrega o suficiente pra justificar; remover o peso morto e a opcao mais limpa.

<details><summary>Justificativa da verificação</summary>

Confirmado em todos os pontos. (1) O sidecar e spawnado via tokio::process::Command — import em stt_sidecar.rs:6 e Command::new(&binary_path) em stt_sidecar.rs:329, NAO pelo mecanismo .sidecar() do plugin shell. (2) grep no Rust inteiro acha tauri_plugin_shell APENAS em lib.rs:63 (.plugin(tauri_plugin_shell::init())); nenhum .shell()/.sidecar()/.command(). (3) Zero imports ou uso de @tauri-apps/plugin-shell no src/ JS (nenhum open(), nenhum import). (4) default.json concede shell:allow-open (L14) e shell:allow-execute com binaries/nora-stt-sidecar/sidecar:true/args:true (L15-20) — args:true e a permissao mais ampla do arquivo e nao protege nada em uso, ja que o spawn via tokio ignora o gate do plugin. (5) Dep presente em package.json:15, Cargo.toml:17, instalada em node_modules e Cargo.lock. Logo: plugin shell + 2 permissoes shell:* + dep JS + crate = peso morto. Severidade medium honesta: nao e bug exploravel, mas e permissao ampla inutil + deps/bundle inchado. CORRECAO IMPORTANTE ao achado: o que mantem o binario do sidecar empacotado/assinado no app NAO e o plugin shell, e sim "externalBin": ["binaries/nora-stt-sidecar"] em tauri.conf.json:72 — isso PRECISA permanecer. O achado nao menciona externalBin, mas o fix proposto tambem nao o remove, entao o fix continua correto nesse ponto. Por isso 'adjusted', nao 'confirmed': o caveat externalBin e load-bearing e precisa estar explicito pra ninguem remover o bundle do binario por engano.

</details>

#### 10. 🟡 LOW — SidecarHandle::feed() is dead code, masked by a blanket #[allow(dead_code)] on the whole impl

- **Local:** `L208-212 (fn feed) + L146 (#[allow(dead_code)] on impl block)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** feed() is never called anywhere. The only consumer, commands.rs, clones `mic_sidecar.audio_tx` directly (commands.rs L106, L115) and uses `try_send`, completely bypassing feed(). The blanket `#[allow(dead_code)]` on `impl SidecarHandle` (L146) suppresses the compiler warning that would otherwise flag this, so the dead method hides indefinitely. This also creates two parallel ways to push audio (the public `feed` API vs. exposing `audio_tx` as a public field), which is confusing for future maintainers.

**Fix recomendado:** Deletar o metodo `feed()` (L208-212) — e morto e a alternativa de forca-lo via API privada nao se encaixa no padrao de clone do Sender pras bridge tasks em commands.rs. Apos remover feed(), remover tambem o `#[allow(dead_code)]` do `impl SidecarHandle` (L146), pois start()/stop() restantes sao usados e nao dispararao warning — isso faz futuros metodos mortos voltarem a ser sinalizados pelo compilador. NAO mexer na visibilidade de `audio_tx` (manter `pub`), pois commands.rs depende de clonar o Sender pra mover dentro das tasks tokio::spawn; trocar isso seria refactor maior e desnecessario. Risco: safe — nenhuma mudanca de comportamento em runtime, apenas remocao de codigo nao referenciado. Recompilar com `cargo build` em apps/desktop/src-tauri pra confirmar zero warnings novos.

<details><summary>Justificativa da verificação</summary>

Confirmado. Em stt_sidecar.rs L208-212, SidecarHandle::feed() existe mas nao e chamado em lugar nenhum — `grep "\.feed("` retorna zero callsites em todo apps/desktop/src-tauri/src/. O unico consumidor real (commands.rs L106 e L115) clona o campo publico `mic_sidecar.audio_tx` / `sidecar.audio_tx` e usa `try_send` diretamente, contornando feed() por completo. O `#[allow(dead_code)]` blanket no impl SidecarHandle (L146) suprime exatamente o warning que sinalizaria esse metodo morto. Verifiquei tambem que os outros metodos do impl SAO usados: start() em commands.rs L74/L88, stop() em commands.rs L201 — entao remover o allow blanket apos deletar feed() nao gera warnings novos. Dano concreto: codigo morto que persiste indefinidamente + duas APIs paralelas pra empurrar audio (feed vs. expor audio_tx publico), confundindo manutencao futura. Severity low e honesta: e debito/limpeza, sem impacto funcional ou de runtime. AJUSTE importante no fix proposto: a sub-sugestao de "tornar audio_tx privado e forcar callers via feed()" NAO e viavel como esta — commands.rs precisa clonar um `Sender` OWNED pra mover dentro das bridge tasks spawned (tokio::spawn move o tx), enquanto feed(&self) opera sobre referencia emprestada e nao pode ser movido pra dentro da task. Logo a alternativa correta e simplesmente deletar feed().

</details>

#### 11. 🟡 LOW — SidecarHandle.track_label field is stored but never read

- **Local:** `L30 (field track_label), L153/L201-205 (set in start)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** `track_label` is stored on the returned SidecarHandle (L204) but is never read by anyone — commands.rs never accesses `sidecar.track_label`, and the value actually used to tag transcripts is the separate `track_label` argument captured by `run_sidecar` (used at L470 `track: track_label.clone()`). So the field on the handle is pure dead state. It's only not flagged because of the same blanket `#[allow(dead_code)]` at L146.

**Fix recomendado:** Remover o campo `track_label` de SidecarHandle (L30) e a linha `track_label,` da construcao em L204. CAVEAT importante: a sugestao do auditor de remover tambem o `track_label_clone` bookkeeping (L164/L170) esta ERRADA — esse caminho alimenta o parametro `track_label` de run_sidecar, que e o valor vivo usado em L470 para etiquetar transcripts; remove-lo quebraria a tag mic/system. O refino correto: como `start` recebe `track_label` por valor e o move-closure (L166) so usa esse valor uma vez, basta remover o `.clone()` (L164) e passar `track_label` direto para `run_sidecar` (L170), eliminando a variavel `track_label_clone`. Depois disso, o `#[allow(dead_code)]` em L146 continua justificado pelos metodos publicos (start/feed/stop) que o compilador pode ver como nao-usados em certas configs, entao nao remover esse atributo. Verificar com `cargo build` (dir apps/desktop/src-tauri) que nao surge warning de campo/variavel nao usados e que L470 segue compilando.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. apps/desktop/src-tauri/src/stt_sidecar.rs L30 declara `pub track_label: String` em SidecarHandle; L204 seta o valor no handle retornado por start(). Grep em todo src-tauri/ (`\.track_label`, `SidecarHandle`) mostra que NENHUM consumidor le esse campo: lib.rs:23 so referencia o tipo (Vec<SidecarHandle>); commands.rs usa apenas `.audio_tx`, `.start()`, `.feed()`, `.stop()`. O valor que de fato etiqueta o transcript e o parametro `track_label` de run_sidecar (L313), capturado via `track_label_clone` (L164/L170) e usado em L470 `track: track_label.clone()` — totalmente independente do campo do handle. Logo o campo e estado morto puro, mascarado pelo blanket `#[allow(dead_code)]` em L146. Dano concreto: nenhum bug de runtime, mas e estado enganoso — um mantenedor futuro pode supor que `handle.track_label` controla a tag do track e tentar muta-lo/le-lo, sem efeito. Severidade low (debito tecnico/clareza), nao bug.

</details>

#### 12. 🟡 LOW — Inconsistent debug-gating of eprintln! — some errors/logs leak to stderr in release builds, others are stripped

- **Local:** `L279, L282, L291 (refresh loop) vs L242-243/L266-267/L286-287; also L438/L501/L505/L511 (stdout/stderr readers)`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Logging is gated inconsistently. Success/lifecycle messages are wrapped in `#[cfg(debug_assertions)]` (L242, L266, L286), but real error logs in the refresh loop are NOT gated (L279 'failed to write refresh_token', L282 'failed to flush', L291 'failed to refresh token'), and the stdout/stderr readers print unconditionally (L438 '[sidecar stderr]', L501 'stdout closed', L505 'read error', L511 'stop signal received'). The result: in a release build a user sees a noisy mix where errors print but the surrounding context (which session, what was being attempted) is debug-only, making the leaked lines hard to interpret. The sidecar's entire stderr is also echoed verbatim to the app's stderr in release, which can leak operational detail.

**Fix recomendado:** Reenquadrar como debito de consistencia de logging de severidade baixa, nao como vazamento de seguranca. Opcao minima (safe, point-fix): no refresh loop, alinhar o gating das tres linhas de erro com o resto do loop — ou gate L279/L283/L291 com #[cfg(debug_assertions)] (se forem ruido de dev), ou, se forem erros uteis ao usuario, mantenha-os ungated mas embuta o session_id na mensagem (ex: 'failed to refresh token for session {session_id}: {e}') para que a linha vazada continue interpretavel sem o contexto debug-only adjacente. Opcao correta de longo prazo (crate-wide, fora do escopo deste point-fix): adotar tracing/log com niveis e padronizar TODO o crate (lib.rs, commands.rs, system_audio.rs, etc.), ja que o problema de eprintln! ungated nao e exclusivo de stt_sidecar.rs. Caveat: NAO afirmar que ha leak de token nessas linhas — nao ha; o auth_token vai por stdin, e o echo do stderr do sidecar Python (L438) e materia de um componente fora do escopo desta auditoria.

<details><summary>Justificativa da verificação</summary>

As linhas citadas conferem exatamente: em apps/desktop/src-tauri/src/stt_sidecar.rs as mensagens de sucesso/lifecycle do refresh loop estao gated com #[cfg(debug_assertions)] (L242-243, L266-267, L286-287, e tambem L248-249, L253-254, L294-295), enquanto os erros do mesmo loop NAO estao (L279 'failed to write refresh_token', L283 'failed to flush refresh_token', L291 'failed to refresh token'). Os readers de stdout/stderr tambem nao estao gated (L438 '[sidecar stderr]', L501 'stdout closed', L505 'read error', L511 'stop signal received'). Entao a inconsistencia descrita existe literalmente.

POReM o framing precisa de dois ajustes que rebaixam o achado:

1) A premissa de "inconsistencia anomala" e fraca. Verifiquei o crate inteiro (grep): nao existe nenhum logger estruturado (tracing/log/env_logger) em lugar nenhum — tudo e eprintln! cru. E a MAIORIA absoluta dos eprintln! do crate ja roda ungated em release: lib.rs L34/L58, http_proxy.rs L71/L126, stealth_mode.rs (todos), commands.rs L36-200, system_audio.rs (todos), live_analysis.rs L91/L115/L168, e mesmo dentro de stt_sidecar.rs L43/L183/L327/L350/L412/L417. Ou seja, o gating parcial no refresh loop nao "vaza" em relacao a um codebase limpo — ele e coerente com um codebase que simplesmente nao tem politica de logging. O 'noisy mix' onde o erro imprime mas o contexto e debug-only e um incomodo cosmetico de DX, de impacto baixo.

2) A parte de SEGURANCA (vazamento de token / 'operational detail') NAO se sustenta. L279/L282/L291 logam apenas a mensagem de erro (e) e retry_delay — nenhum token, session_id ou access_token aparece ali. O auth_token e enviado ao sidecar via STDIN como JSON (L269-277), nunca via stderr. L438 ecoa o stderr do proprio sidecar Python verbatim — mas o que o sidecar imprime no proprio stderr esta fora de escopo (services/nlp-worker/sidecar Python e explicitamente fora de escopo nesta auditoria e nao foi verificado). Tratar isso como vazamento de token e especulativo e nao comprovado.

Resumindo: o fato (gating inconsistente) e real e verbatim, mas (a) e um problema crate-wide de code-quality, nao um ponto especifico dessas linhas, e (b) o angulo de seguranca/leak nao procede. Sobra um achado de consistencia/profissionalismo de severidade baixa. O risco do fix e safe: gate ou nao-gate de eprintln! nao muda comportamento funcional (no Linux/WebKitGTK nada disso afeta drag/transparencia/janelas).

</details>

### `apps/desktop/src-tauri/src/system_audio.rs`  ·  5 achado(s)

#### 13. 🟠 MEDIUM — Leak de mix_format (CoTaskMem) e de event handle em early-return no caminho WASAPI

- **Local:** `L238-L294 (run_loop: GetMixFormat .. CoTaskMemFree)`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** `mix_format_ptr` é alocado por `GetMixFormat()` (L238) e só é liberado por `CoTaskMemFree` no fim feliz (L293). Entre L238 e L293 há vários `?` (Initialize, SetEventHandle, GetService, Start, e o loop com GetNextPacketSize/GetBuffer/ReleaseBuffer). Qualquer erro nesses pontos retorna sem chamar `CoTaskMemFree` nem `CloseHandle(event)` — vaza memória COM e um handle do SO a cada falha. O `ComGuard` (L229-231) só cobre `CoUninitialize`, não o format nem o event. Em loopback isso pode repetir se a captura for reiniciada várias vezes (start/stop de gravação).

**Fix recomendado:** Aplicar RAII no estilo do ComGuard existente. Logo apos L238 declarar um guard que chama CoTaskMemFree(Some(ptr as *mut _)) no Drop, guardando o ponteiro; logo apos L241 declarar um guard que chama CloseHandle no Drop guardando o HANDLE. Assim qualquer `?` entre L243 e L291 libera tudo no unwind. Caveats: (1) REMOVER o CloseHandle(event)? de L292 e o CoTaskMemFree(...) de L293 para evitar double-free, deixando a liberacao a cargo dos guards; (2) o run_loop ainda usa mix_format (&*mix_format_ptr em L239 e mix_format.nSamplesPerSec/nChannels em L256-258) e passa mix_format_ptr para Initialize em L248 — manter o ponteiro vivo no guard nao impede esses usos pois sao todos antes do fim do escopo; (3) ordem de drop nao e load-bearing aqui (recursos independentes), mas declarar event-guard depois do format-guard mantem simetria. Fix nao altera comportamento do caminho feliz, so garante limpeza determinística no erro.

<details><summary>Justificativa da verificação</summary>

Confirmado lendo /home/pollo/Dev/nora/apps/desktop/src-tauri/src/system_audio.rs L223-295 (modulo gated por #[cfg(target_os = \"windows\")], L160). mix_format_ptr e alocado por GetMixFormat() em L238 (memoria COM) e event por CreateEventW() em L241 (handle do SO). A liberacao so acontece no caminho feliz: CloseHandle(event) L292 e CoTaskMemFree(mix_format_ptr) L293. Entre a alocacao e essa cauda existem multiplos `?` que retornam cedo sem liberar: Initialize() L243-250, SetEventHandle() L251, GetService() L253, Start() L254, e dentro do loop GetNextPacketSize() L267, GetBuffer() L273, ReleaseBuffer() L287; alem disso Stop()? em L291 falhando tambem pula L292-293. O ComGuard (L229-231) so cobre CoUninitialize, nao o format nem o event. SystemAudioCapture::start (L191) cria uma thread run_loop nova a cada inicio, entao start/stop repetidos com falha acumulam o vazamento. Severidade medium e honesta: vaza so no caminho de erro (o caminho de sucesso libera normalmente), e Windows-only; cada falha vaza um handle de kernel + um buffer pequeno (~dezenas de bytes), nao alta frequencia. Nao e padrao intencional — os padroes intencionais citados (startDragging, backgrounds solidos) sao de UI/WebKitGTK, irrelevantes aqui.

</details>

#### 14. 🟡 LOW — Flag de stop é inerte no caminho Linux; thread leitora só sai por EOF e nunca é joinada

- **Local:** `L120-L141 (reader thread) e L75-L77/L146-L157 (struct/stop Linux)`
- **Categoria:** dead-code · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** No Linux, `SystemAudioCapture` guarda apenas `child` (L76). A thread leitora roda `while flag.load(Ordering::SeqCst)` (L122), mas o `Arc<AtomicBool>` `flag` recebido em `start()` nunca é armazenado em lugar nenhum nem setado para false — o caller (audio_capture.rs:261) cria o flag e o move para `start()` sem reter referência. `stop()` (L146-149) apenas mata o processo `parecord`; a thread então sai por `read()==Ok(0)` (EOF) por acaso, e nunca é joinada (detached). Resultado: a condição `while flag` é morta/enganosa (sugere um mecanismo de parada que não existe) e há uma janela em que a thread sobrevive ao stop até o EOF chegar.

**Fix recomendado:** Preferir opcao (a) por consistencia com Windows/macOS: no struct Linux adicionar `stop_flag: Arc<AtomicBool>` e `thread: Option<std::thread::JoinHandle<()>>`. Em start(): `let stop_flag = flag.clone();`, capturar o `flag` original na thread (loop ja existente fica correto), guardar o JoinHandle de `std::thread::spawn(...)`, e retornar `Self { child, stop_flag, thread: Some(handle) }`. Em stop()/Drop: ordem segura -> `self.stop_flag.store(false, Ordering::SeqCst);` ANTES de matar o child (assim o loop pode sair pela flag mesmo se o EOF demorar), depois `self.child.kill(); self.child.wait();`, e por fim `if let Some(t) = self.thread.take() { let _ = t.join(); }`. CAVEAT de risco (needs-care): o join() em stop() passa a bloquear ate a thread sair; como a thread checa flag a cada iteracao do loop non-blocking (sleep 10ms no WouldBlock) e o read retorna apos no maximo ~10ms, o join e curto — mas se o read estiver bloqueado no momento exato, o kill+EOF garante o desbloqueio; setar a flag primeiro evita depender so do EOF. Assinatura de start() ja recebe `flag` em todos os SOs, entao armazenar no Linux NAO muda contrato cross-platform (baixo risco de regressao). Nao mexer no comportamento do PR_SET_PDEATHSIG nem nos backgrounds/drag — fora de escopo. Opcao (b) (remover o param flag no Linux) e mais invasiva pois diverge a assinatura por plataforma; preferir (a).

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. system_audio.rs (Linux): start() recebe `flag: Arc<AtomicBool>` (L84), a thread leitora faz `move` capturando `flag` e loopa `while flag.load(Ordering::SeqCst)` (L122). Mas o struct SystemAudioCapture (L75-77) guarda SO `child` — L143 retorna `Ok(Self { child })`, descartando o Arc (a thread vira a unica dona) e descartando o JoinHandle (thread detached). Nada nunca seta o flag pra false: o caller audio_capture.rs:261 cria `Arc::new(AtomicBool::new(true))` e move pra start() sem reter clone; stop() (audio_capture.rs:331) chama capture.stop() que (system_audio.rs L146-149) so faz child.kill()+wait(). A thread entao sai por `read()==Ok(0)` (EOF, L124) quando o pipe do parecord fecha, nunca pela condicao `while flag`. Logo a condicao `while flag.load()` e codigo morto/enganoso: sugere um mecanismo de parada que nao existe. Contraste direto: Windows (L185-217) e macOS (L440-586) guardam corretamente `stop_flag: Arc<AtomicBool>` + `thread: Option<JoinHandle>`, setam flag=false em stop() e dao join() — so o Linux esta no meio-termo inconsistente. Dano concreto e real porem baixo: (1) manutencao/clareza — leitor pensa que ha stop cooperativo que nao ha; (2) a thread sobrevive ao stop() ate o EOF chegar (~10ms, dado read non-blocking + sleep de 10ms no WouldBlock) e nunca e joinada (detached) — nao e leak de verdade (a thread termina), mas e nao-deterministico e divergente dos outros SOs. Nao e um bug critico de runtime.

</details>

#### 15. 🟡 LOW — Parâmetro sample_rate_hint é morto em todos os caminhos; Linux hardcoda --rate=16000

- **Local:** `L80-L85 (Linux start) e L446-L451 (macOS start) — parâmetro _sample_rate_hint`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** `SystemAudioCapture::start` recebe `_sample_rate_hint: u32` em todas as plataformas e o ignora em todas (prefixo _). O caller passa `16000` literal (audio_capture.rs:266). No Linux o rate é hardcodado em `--rate=16000` (L91) independentemente do hint. É um parâmetro de API que sugere configurabilidade inexistente — ou se usa de fato (ex.: passar o hint para o `--rate` do parecord e para o resampler) ou se remove para não enganar.

**Fix recomendado:** Remover o parametro `_sample_rate_hint: u32` das TRES implementacoes de `SystemAudioCapture::start` em apps/desktop/src-tauri/src/system_audio.rs (Linux L82, Windows L193, macOS L448 — o auditor omitiu a do Windows) e do unico call site em apps/desktop/src-tauri/src/audio_capture.rs:264-269 (remover o argumento `16000` da L266). O alvo 16kHz ja e fixo em todo o pipeline, entao nada muda em runtime. Nao mexer em commands.rs:136 (e outro metodo `start`, do recorder, nao do SystemAudioCapture). Alternativa (so se houver intencao futura de taxa configuravel): propagar de fato o hint para `--rate` do parecord no Linux e para o alvo do MonoResampler em Windows/macOS — mas isso e escopo novo e nao se justifica hoje.

<details><summary>Justificativa da verificação</summary>

Confirmado, com correcao de abrangencia. SystemAudioCapture::start recebe `_sample_rate_hint: u32` e ignora em TODAS as plataformas — nao so Linux (L82) e macOS (L448) como o auditor citou, mas tambem Windows/WASAPI (L193), que ele deixou de fora. O unico call site (audio_capture.rs:264-269) passa o literal `16000`. O alvo de 16kHz e fixo em todo o pipeline: Linux hardcoda `--rate=16000` (L90); macOS resampla para `16_000` (L516); Windows tambem; o caminho de microfone visa 16000 (audio_capture.rs:73-75,178); e RecordingStatus.sample_rate reporta 16000 (L293). Logo o parametro nao tem nenhum efeito e sugere configurabilidade inexistente — e ruido de API real. Importante: o `.start(` em commands.rs:136 NAO e este metodo (e o start do recorder), entao nao entra no fix. Severidade low e honesta: parametro com prefixo `_` ja suprime warning do compilador, sem bug nem impacto em runtime — apenas engana quem le a assinatura. Fix e safe: remover de 3 assinaturas + 1 call site e mudanca local, verificada em compilacao, sem efeito comportamental (o valor ja era ignorado).

</details>

#### 16. 🟡 LOW — unwrap() na criação do resampler dentro do run_loop WASAPI

- **Local:** `L260 (MonoResampler::new(...).unwrap()) no run_loop WASAPI`
- **Categoria:** error-handling · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** `crate::audio_resample::MonoResampler::new(src_sr, 16000).unwrap()` (L260) faz panic se a inicialização do rubato Fft falhar (ex.: src_sr exótico do mix format, ou parâmetros que o rubato rejeite). O caminho macOS trata esse mesmo erro com match e retorno limpo (system_audio.rs:516-523); o WASAPI usa unwrap, gerando inconsistência e crash da thread de captura em vez de log + saída limpa. `run_loop` já retorna `windows::core::Result` e o caller loga erros — então dá pra propagar.

**Fix recomendado:** Espelhar o caminho macOS: trocar o `.unwrap()` por um match que loga (sob `#[cfg(debug_assertions)]`) e sai cedo. Atencao a um detalhe que o achado original nao mencionou: nesse ponto (L260) o `audio_client.Start()` ja foi chamado (L254) e o cleanup das L291-293 (`Stop`/`CloseHandle`/`CoTaskMemFree`) seria pulado num `return` direto. O ComGuard (Drop, L230-231) ainda chama CoUninitialize, mas o event handle e o mix_format_ptr vazariam. Como erro de init do resampler e raro e a thread esta morrendo de qualquer forma, o vazamento de um handle e tolheravel; ainda assim o ideal e fazer cleanup antes de sair. Exemplo: `let mut resampler = match crate::audio_resample::MonoResampler::new(src_sr, 16000) { Ok(r) => r, Err(e) => { #[cfg(debug_assertions)] eprintln!("[wasapi] resampler init failed: {}", e); let _ = e; audio_client.Stop().ok(); CloseHandle(event).ok(); CoTaskMemFree(Some(mix_format_ptr as *const _ as *mut _)); return Ok(()); } };`. Retornar `Ok(())` (e nao Err) evita um log de "loop error" duplicado no caller, ja que a falha ja foi logada aqui — coerente com o macOS que so retorna. Nenhum padrao intencional cross-platform e afetado.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. system_audio.rs:260 faz `crate::audio_resample::MonoResampler::new(src_sr, 16000).unwrap()`. O `.unwrap()` e sobre um `Result<Self, String>` cujo caminho de erro e real: audio_resample.rs:24-32 chama `Fft::<f32>::new(...).map_err(...)`, ou seja rubato pode rejeitar parametros. O `src_sr` vem de `mix_format.nSamplesPerSec` (L256), valor dinamico do mix format do SO, nao constante hardcoded — entao a entrada e genuinamente incontrolada (sample rate exotico). O caminho macOS trata exatamente o mesmo erro com `match`/`return` limpo (L516-523), confirmando a inconsistencia. `run_loop` retorna `windows::core::Result<()>` (L226) e o caller loga o erro (L201-204), entao da pra propagar. Dano concreto: em sample rate atipico a thread de captura WASAPI da panic em vez do log+saida limpa do macOS. Severidade honestamente low: exige sample rate incomum, roda em thread propria (panic nao derruba o processo por padrao), mas e gap de robustez real e inconsistencia genuina entre plataformas.

</details>

#### 17. 🟡 LOW — Byte ímpar de uma leitura é descartado inteiro em vez de bufferizado, podendo dessincronizar PCM

- **Local:** `L123-L139 (match stdout.read), especialmente L126-128`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** A leitura é não-bloqueante sobre um pipe (L112-118). Em `Ok(n)`, se `n % 2 != 0` o código faz `continue`, descartando os `n` bytes lidos por completo (L126-128). Como leituras de pipe podem entregar contagens arbitrárias (não necessariamente alinhadas a 2 bytes do i16), o byte de transbordo de um frame i16 é perdido e o byte seguinte da próxima leitura passa a ser interpretado como byte-baixo do par errado — dessincronizando o stream s16le (ruído/troca de canal de magnitude). Na prática parecord costuma entregar blocos grandes alinhados, então é raro, mas é um bug latente real.

**Fix recomendado:** Manter um carry de 1 byte entre iteracoes em vez de `continue`. Antes do loop: `let mut carry: Option<u8> = None;`. A cada `Ok(n)`: construir o slice efetivo concatenando o byte de carry (se houver) com `read_buf[..n]`; processar `len - (len % 2)` bytes via `chunks_exact(2)`; guardar o ultimo byte (se `len` impar) em `carry` para a proxima leitura. Esboco:

```rust
let mut carry: Option<u8> = None;
let mut work: Vec<u8> = Vec::with_capacity(read_buf.len() + 1);
while flag.load(Ordering::SeqCst) {
    match stdout.read(&mut read_buf) {
        Ok(0) => break,
        Ok(n) => {
            work.clear();
            if let Some(b) = carry.take() { work.push(b); }
            work.extend_from_slice(&read_buf[..n]);
            let even = work.len() & !1; // drop trailing odd byte
            if work.len() & 1 == 1 { carry = Some(work[even]); }
            if even >= 2 {
                let samples: Vec<i16> = work[..even]
                    .chunks_exact(2)
                    .map(|c| i16::from_le_bytes([c[0], c[1]]))
                    .collect();
                let _ = sink.try_send(samples);
            }
        }
        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        Err(_) => break,
    }
}
```

Caveats: (1) o carry e bounded em no maximo 1 byte, sem risco de crescimento ilimitado; (2) preservar O byte de carry ainda pendente ao quebrar/parar nao importa (audio descartado no shutdown). (3) Como e severidade low, e aceitavel deixar como esta se o tempo for melhor gasto em outro lugar — mas o fix e barato e elimina o bug latente. Alternativa minima (sem carry, so parar de jogar tudo fora): trocar `if n % 2 != 0 { continue; }` por processar `&read_buf[..n - (n % 2)]` — isso evita descartar o bloco inteiro, mas NAO corrige a dessincronizacao porque ainda perde o byte sobrante; portanto o carry buffer e a unica correcao completa.

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo exato em apps/desktop/src-tauri/src/system_audio.rs L120-141. O loop le de um pipe NAO-bloqueante (O_NONBLOCK setado L112-118) para um buffer local fixo `read_buf` (L121) reusado a cada iteracao. Em `Ok(n)` (L125), se `n % 2 != 0` o codigo faz `continue` (L126-128), descartando os `n` bytes inteiros — nao so o byte impar. read() sobre pipe NAO tem contrato de alinhamento de 2 bytes: o kernel pode entregar qualquer contagem ate o tamanho do buffer. O dano e real: nao ha carry buffer, entao o alinhamento dos pares i16 nunca e preservado entre leituras. Quando uma leitura termina no meio de uma amostra, o byte sobrante e perdido (pelo `continue`, ou mesmo sem ele pelo `chunks_exact(2)` que dropa o ultimo byte), e a proxima leitura passa a interpretar byte-low/byte-high trocados → stream s16le dessincronizado (ruido, magnitude/canal errado). E um bug latente legitimo. Severidade honesta = low: na pratica parecord/PulseAudio entrega blocos grandes e o buffer de 6400 bytes (par) faz a maioria das leituras cair em fronteira par, entao o glitch e raro e recuperavel (nao trava nem corrompe permanentemente). Nao e estilo subjetivo nem nitpick: e perda de dados com efeito audivel possivel. Fora de escopo do worker, dentro de apps/desktop. NAO conflita com padroes intencionais cross-platform (este e o branch Linux/parecord; nada a ver com drag/transparencia WebKitGTK).

</details>

### `apps/desktop/src-tauri/src/audio_capture.rs`  ·  2 achado(s)

#### 18. 🟡 LOW — Device e best-config resolvidos duas vezes; sample_rate/channels externos são código morto em release

- **Local:** `L107-L128 (resolução de device/config no escopo externo) vs L142-L176 (re-resolução na thread)`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** `start()` resolve o device (L107-118), calcula `actual_name` (L120) e chama `find_best_config` (L124) obtendo `sample_rate`/`channels` (L125-126). Logo depois a thread (L142-176) re-enumera os devices, re-resolve o mesmo device e re-chama `find_best_config`. Os valores `config`/`sample_rate`/`channels` do escopo externo são usados APENAS num `#[cfg(debug_assertions)] eprintln!` (L128) — em release build viram computação morta. Além do desperdício (dupla enumeração ALSA/cpal, que pode ser lenta) há risco de divergência: se a lista de devices mudar entre as duas resoluções, a thread pode abrir um device diferente do validado, e o status reportado (`actual_name`) pode não corresponder ao que a thread efetivamente abriu.

**Fix recomendado:** Resolver device+config UMA vez no escopo externo (preservando a validacao precoce e o actual_name para o status) e MOVER device e config ja resolvidos para dentro do closure, em vez de re-enumerar/re-resolver. Como cpal::Device e cpal::StreamConfig sao Send (so cpal::Stream e !Send), o stream continua sendo construido dentro da thread com `device.build_input_stream(&config, ...)`, mas sem chamar host.input_devices()/find_best_config de novo. Isso elimina a dupla enumeracao, remove as bindings mortas (sample_rate/channels passam a ser lidas de config dentro da thread, onde os valores reais sao usados) e fecha a janela de divergencia. Tambem dispensa o clone de device_name na L138. CAVEAT (por isso needs-care): NAO adotar a alternativa "eliminar a resolucao externa e reportar tudo da thread" — hoje a resolucao externa valida que o device existe ANTES de start() retornar Ok; a thread, em falha, apenas faz eprintln e return, e start() ja retorna Ok(status) com is_recording:true (erro engolido — bug adjacente preexistente). Mover toda a resolucao para a thread pioraria esse swallow. A variante segura mantem a validacao externa e so reaproveita os valores no closure via move.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. Em apps/desktop/src-tauri/src/audio_capture.rs::start(): o device e resolvido no escopo externo (L107-118), actual_name e derivado (L120) e find_best_config e chamado (L124) produzindo config/sample_rate/channels (L124-126). Logo a thread spawnada (L142-176) re-enumera os devices via host.input_devices()/default_input_device(), re-resolve o mesmo device e re-chama find_best_config — dupla enumeracao ALSA/cpal. As bindings externas config/sample_rate/channels sao usadas APENAS no eprintln gated por #[cfg(debug_assertions)] na L127-128; em release build viram computacao morta (o compilador inclusive emite unused-variable warnings em release). Observacao: actual_name (L120) NAO e morto — e usado na L291 (mic_device: actual_name) no RecordingStatus, entao a resolucao externa do device em si nao e 100% inutil, so a do config e. O risco de divergencia tambem e real: a thread re-resolve o device independentemente do que gerou actual_name, entao se a lista de devices mudar na janela entre as duas resolucoes, a thread pode abrir um device diferente do nome reportado no status.

Viabilidade do fix verificada nas fontes do cpal 0.15.3: o ALSA Device e { name: String, handles: Arc<Mutex<DeviceHandles>> } e StreamConfig e plain data (channels/sample_rate/buffer_size) — ambos Send. Apenas cpal::Stream carrega o marker NotSendSyncAcrossAllPlatforms (platform/mod.rs L73), por isso so o Stream precisa nascer e viver dentro da thread (confirmado tambem pelo comentario em system_audio.rs L512). Logo a re-resolucao do device+config na thread e desnecessaria; device e config podem ser movidos para dentro do closure. Ajusto a severidade: continua low (debito DRY + codigo morto em release + risco de divergencia baixissimo na pratica), mas o ponto mais valioso e o codigo morto em release, nao a performance.

</details>

#### 19. 🟡 LOW — unwrap() em system_tx pode dar panic se contrato caller/callee divergir

- **Local:** `L262 (let system_tx = sinks.system_tx.unwrap();)`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Dentro de `start()`, quando `capture_system_audio == true`, o código faz `sinks.system_tx.unwrap()`. Hoje o único caller (commands.rs:64-71) garante `system_tx: Some(...)` exatamente quando `capture_system` é true, então funciona. Mas `AudioCapture::start` é `pub` e o invariante 'capture_system_audio==true => system_tx==Some' não é expresso no tipo. Qualquer caller futuro (ou refactor de commands.rs) que passe `capture_system_audio=true` com `system_tx=None` derruba a thread/processo com panic em vez de erro tratável. O sink de mic já é obrigatório, mas o de system é opcional no struct — a assimetria é a armadilha.

**Fix recomendado:** Trocar `let system_tx = sinks.system_tx.unwrap();` (L262) por um match/if-let que apenas pula o system audio em vez de panicar, mantendo coerencia com o arm Err(e) ja existente em L278-281 (system audio e best-effort): `let Some(system_tx) = sinks.system_tx else { #[cfg(debug_assertions)] eprintln!("[audio] capture_system_audio=true mas system_tx ausente; pulando system audio"); return Ok(status_sem_system); };` — porem, como o `status` so e montado depois (L289), na pratica e mais simples nao usar early-return e sim envolver o bloco system inteiro: `if let Some(system_tx) = sinks.system_tx { ... match SystemAudioCapture::start(... system_tx ...) ... }` deixando `system_audio_display_name = None` quando ausente. NAO usar `return Err(...)` como o auditor sugeriu: isso abortaria o start ja com a thread de mic spawnada/armazenada (L238-245), deixando-a orfa sem cleanup. Alternativa mais robusta (preferivel a longo prazo): expressar o invariante no tipo — ex. substituir `capture_system_audio: bool` + `system_tx: Option<...>` por um unico campo que carrega o Sender so no caminho de system audio (ex. `system: Option<SystemSink>` ou um enum), eliminando a assimetria na raiz.

<details><summary>Justificativa da verificação</summary>

Codigo confirmado em apps/desktop/src-tauri/src/audio_capture.rs:262 (`let system_tx = sinks.system_tx.unwrap();`), dentro do bloco `if capture_system_audio` -> `if let Some(source) = source`. A assimetria existe de fato: em CaptureSinks (L19-24) `mic_tx` e obrigatorio mas `system_tx: Option<...>` e opcional, e o invariante "capture_system_audio==true => system_tx==Some" nao esta no tipo, so e convencao de runtime. `start` (L87) e `CaptureSinks` (L19) sao ambos `pub`. Hoje NAO ha panic: o unico caller (commands.rs:64-71) acopla corretamente `system_tx: if capture_system { Some(system_tx) } else { None }` no mesmo ponto que passa `capture_system` como `capture_system_audio` (L58, L66). Logo o problema e um panic latente sem gatilho atual, nao um bug ativo -> "medium" e generoso, "low" e honesto (hardening defensivo contra refactor futuro). Ajusto tambem a forma do fix: retornar Err(...) como o auditor propos aborta o start inteiro DEPOIS de ja ter spawnado e armazenado a thread do mic (L238-245), que ficaria orfa sem cleanup nesse caminho; e inconsistente com o arm Err(e) ja existente em L278-281 que apenas loga e segue (system audio e best-effort). O fix mais correto e log-and-skip, igual ao tratamento que ja existe para falha de SystemAudioCapture::start.

</details>

### `apps/desktop/src-tauri/src/audio_resample.rs`  ·  1 achado(s)

#### 20. 🟡 LOW — leftover do resampler descartado silenciosamente em erro e no fim da captura

- **Local:** `L42-L76 (process) + leftover residual no fim do stream`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** Dois pontos: (1) em `InterleavedOwned::new_from` falhar (L52-56) ou `process_into_buffer` falhar (L69-72) o código faz `break`, deixando o restante de `self.leftover` no buffer — frames já drenados do início podem ser perdidos e o resto fica preso até a próxima chamada, sem nenhum sinal ao chamador além de um eprintln. (2) Não há método flush: quando a captura para, os <chunk frames acumulados em `leftover` nunca são processados (tail do áudio perdido). Para STT em tempo real o impacto é pequeno (perde-se uma fração de ~20ms no fim), mas é uma perda silenciosa de dados.

**Fix recomendado:** Tratar como nota de robustez de baixissima prioridade, nao bug. CAVEAT importante no fix proposto pelo auditor: trocar `break` por `continue` (claim 1) NAO e claramente melhor e pode piorar — se o erro for deterministico (ex: bug real de dimensao), `continue` faria o loop drenar+descartar todo chunk subsequente silenciosamente em vez de parar; manter `break` (que falha rapido) e defensavel. Se quiser melhorar de fato: (a) tornar o erro visivel ao chamador mudando a assinatura para `process(&mut self, input) -> Result<Vec<f32>, String>` e propagando o erro, em vez de engolir com eprintln so-em-debug — assim system_audio.rs/audio_capture.rs decidem parar a captura; e antes do `break`, opcionalmente re-inserir o `block` drenado de volta no inicio de `self.leftover` se quiser preservar estado. (b) Para o tail (claim 2), expor um `flush(&mut self) -> Vec<f32>` que zero-pad o leftover ate `chunk` e emite o ultimo bloco, chamado no caminho de stop da captura (apos o stop_flag). Ambos sao opcionais; o ganho real e marginal para STT em streaming continuo, entao so vale a pena se for feito junto com uma limpeza maior do modulo.

<details><summary>Justificativa da verificação</summary>

As duas observacoes sao factualmente corretas no codigo de apps/desktop/src-tauri/src/audio_resample.rs L42-76. (1) Em L50 `self.leftover.drain(..chunk).collect()` ja remove o bloco do buffer ANTES do processamento; nos erros de L52-56 (InterleavedOwned::new_from) e L69-72 (process_into_buffer) o `break` descarta esse `block` ja drenado e abandona o loop, deixando o resto do leftover preso ate a proxima chamada — perda silenciosa exceto por um eprintln so em debug. (2) Confirmei via grep que NAO existe metodo flush no MonoResampler (os unicos `flush` no projeto sao em stt_sidecar.rs/stt_sidecar_test.rs, sobre stdin, nao relacionados): frames residuais (<chunk, ate ~20ms) acumulados em `leftover` nunca sao processados quando a captura para (system_audio.rs L540 e audio_capture.rs L203 chamam process() por callback de stream, sem flush no stop). PORÉM o impacto pratico e quase nulo: (a) os caminhos de erro em L52-72 praticamente nao disparam — `new_from(block,1,chunk)` tem dimensoes corretas por construcao (block.len()==chunk, 1 canal) e process_into_buffer com buffers do tamanho certo nao falha em streaming normal; um erro real desses significaria audio ja quebrado, e perder 1 chunk e irrelevante perto disso; (b) o tail perdido e uma fracao de um unico frame ~20ms no fim de uma sessao de STT continuo — inaudivel/inconsequente pra transcricao. Por isso ajusto: os fatos sao reais mas e polish de prioridade muito baixa, nao um bug acionavel. Severidade low honesta (nenhum dado relevante de usuario realmente se perde no uso real).

</details>

### `apps/desktop/src-tauri/src/http_proxy.rs`  ·  2 achado(s)

#### 21. 🟡 LOW — Content-Type dedup usa match case-sensitive — header duplicado se renderer mandar 'content-type'

- **Local:** `L77-87 (clean_headers loop + Content-Type insert)`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O filtro de FORBIDDEN_HEADERS normaliza a chave com to_ascii_lowercase() (L79), mas o guard que evita sobrescrever o Content-Type usa comparacao exata `clean_headers.contains_key("Content-Type")` (L85). Se qualquer chamador enviar a chave em caixa diferente (ex: `content-type`), o map vai conter as duas variantes e o reqwest emitira dois headers Content-Type, o que pode quebrar o backend ou ser rejeitado. Hoje o unico caller (api-client.ts) manda 'Content-Type' capitalizado, entao o bug esta latente, mas a logica e fragil e contradiz o cuidado case-insensitive aplicado nos forbidden headers logo acima.

**Fix recomendado:** Tornar a montagem de clean_headers consistente em case. Opcao minima e segura: trocar o guard de dedup pra ser case-insensitive, sem mexer no resto — ex: `let has_content_type = clean_headers.keys().any(|k| k.eq_ignore_ascii_case("content-type"));` e usar `if !has_content_type {...}`. Caveat importante sobre o fix sugerido pelo auditor (lowercasing o map inteiro com k.to_ascii_lowercase() em L82): se for por esse caminho, e PRECISO tambem lowercasing as chaves injetadas — "Content-Type" (L86) e "Authorization" (L91) — senao reintroduz exatamente a mesma classe de inconsistencia (insere capitalizado num map agora lowercase). Como HeaderName do reqwest e case-insensitive, lowercasing nao muda nada semanticamente pro backend, entao a abordagem do map inteiro tambem e segura DESDE QUE inclua os 3 inserts. Risco cross-platform: nenhum (logica pura Rust, nao toca drag/transparencia/WebKitGTK). Prioridade baixa — pode agrupar com outros endurecimentos do proxy num PR de cleanup.

<details><summary>Justificativa da verificação</summary>

A inconsistencia existe e e verificavel em apps/desktop/src-tauri/src/http_proxy.rs. L79 normaliza a chave com to_ascii_lowercase() pro filtro de FORBIDDEN_HEADERS (case-insensitive), mas L82 insere a chave ORIGINAL no map e L85 faz o guard de dedup com contains_key("Content-Type") exato (case-sensitive). Confirmei tambem que reqwest 0.12 (Cargo.toml L18) usa RequestBuilder::header -> HeaderMap::append, e HeaderName e case-insensitive, entao se o map contiver tanto "content-type" quanto "Content-Type" o loop L106-107 emitiria DOIS valores de Content-Type pro backend. Mecanismo do dano confirmado. POReM: rebaixo a severidade de medium pra LOW porque o bug e estritamente LATENTE/inalcancavel hoje. Grep no frontend inteiro mostra que a unica ocorrencia de content-type e o canonico "Content-Type" em api-client.ts:38, e o unico caller de http_proxy e api-client.ts:54 — nenhum caller passa header em caixa diferente. O object spread em api-client.ts:37-39 so geraria chave duplicada se um caller futuro passasse headers:{"content-type":...}, o que nao ocorre. Alem disso "pode quebrar o backend" e especulativo — muitos servers toleram Content-Type duplicado. Logo: fragilidade real e contradicao de estilo defensivo, sem dano user-facing atual. Vale o hardening de um proxy de seguranca, mas nao e medium.

</details>

#### 22. 🟡 LOW — Proxy anexa body mesmo em GET se req.body for fornecido

- **Local:** `L110-117 (body enviado para qualquer metodo, incluindo GET)`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O body e anexado sem checar o metodo (L110-117). Para GET/DELETE com body, alguns servidores ignoram, outros rejeitam, e e semanticamente incorreto. Hoje o frontend provavelmente nao manda body em GET, mas o contrato permite e nao ha guarda.

**Fix recomendado:** Duas opcoes, ambas safe. (A) No Rust (http_proxy.rs L110): so anexar body para metodos que o aceitam — guardar com `let has_body_method = matches!(method.to_ascii_uppercase().as_str(), "POST" | "PUT" | "PATCH");` (PATCH nem existe no match de L98-104 hoje, entao na pratica POST/PUT) e envolver o bloco em `if has_body_method && !body.is_null()`. Cuidado: nao confiar em `req.body.is_some()` porque graças ao `body ?? null` do frontend ele e quase sempre `Some(Null)`; cheque `!body.is_null()`. (B) Mais barato e na fonte: em api-client.ts L46 trocar `body: body ?? null` por so incluir a chave quando ha body (`...(body !== undefined ? { body } : {})`), o que faz `req.body` virar `None` e o ramo nem disparar. A opcao B tambem corrige o `Content-Type: application/json` sendo carimbado em GETs sem body. Idealmente as duas: B limpa o caller, A torna o proxy robusto independ?ente do caller. Risco de quebra: nenhum — POST/PUT continuam mandando body normalmente; os unicos GETs afetados deixam de mandar `null`, que o backend ja ignorava. Nao mexe em nenhum padrao cross-platform (WebKitGTK/drag/etc).

<details><summary>Justificativa da verificação</summary>

O codigo citado existe e e exatamente como descrito: em http_proxy.rs L110-117 o body e anexado com `if let Some(body) = req.body` sem nenhuma checagem do metodo HTTP. O auditor erra num ponto factual a favor do achado: ele supoe que "o frontend provavelmente nao manda body em GET", mas o caller real (api-client.ts L46 `body: body ?? null`) SEMPRE inclui a chave `body` no payload, setando `null` quando ausente. Como o campo Rust e `Option<serde_json::Value>`, um JSON `null` explicito desserializa para `Some(Value::Null)` (e nao `None` — `None` so ocorre com a chave ausente). Logo o ramo L110 DISPARA em todo GET real. Confirmei chamadas GET reais sem body: meetings.ts:20 (`GET /meetings?...`) e meetings.ts:24 (`GET /meetings/{id}`). Resultado pratico: cada GET sai com body literal `null` (4 bytes de `serde_json::to_vec(Value::Null)`) mais `Content-Type: application/json` forcado em L85-87. POReM o dano concreto e baixo: contra o backend real (Spring Boot/Tomcat) o body de GET e simplesmente ignorado, sem erro. Nao ha SSRF nem vazamento — so incorrecao semantica HTTP. Por isso ajusto: o problema e real e o code-path realmente e exercitado (corrigindo a premissa errada do auditor), mas a severidade permanece low porque nao quebra nada no stack atual. Mantenho dentro de escopo (apps/desktop/**).

</details>

### `apps/desktop/src-tauri/src/lib.rs`  ·  2 achado(s)

#### 23. 🟡 LOW — Tres comandos Rust registrados no invoke_handler sem nenhum caller (IPC orfa)

- **Local:** `L94 (get_live_highlights_snapshot), L96-97 (get_overlay_position, set_overlay_position)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** get_live_highlights_snapshot, get_overlay_position e set_overlay_position estao registrados em generate_handler! mas nenhum invoke("...") no TS os chama (grep em todo apps/desktop/src retorna zero) e nao ha chamada interna no Rust. Sao superficie de IPC morta. Pior: get_live_highlights_snapshot foi claramente feito pra hidratar a overlay quando ela reabre no meio da gravacao (use-live-highlights.tsx so escuta o stream live-analysis e nao puxa snapshot no mount), entao alem de codigo morto e um gap latente — abrir a overlay depois de highlights ja emitidos mostra a lista vazia.

**Fix recomendado:** Remover os 3 comandos orfaos: deletar as fns get_live_highlights_snapshot, get_overlay_position e set_overlay_position em apps/desktop/src-tauri/src/live_analysis.rs (L258-264, L279-287, L289-298) e remover as 3 linhas correspondentes do generate_handler! em apps/desktop/src-tauri/src/lib.rs (L94, L96, L97). NAO mexer em clear_live_highlights (L95), que tem callers reais em use-recording.ts:180,204. Caveat: get_live_highlights_snapshot ja existe e funciona (le o LiveHighlightsState mantido em analyze_live, live_analysis.rs:142-144) — se no futuro a overlay passar a ser destruida/recriada em vez de show/hide, ai sim valeria chamar invoke('get_live_highlights_snapshot') no mount do LiveHighlightsProvider pra re-hidratar; hoje isso nao acontece, entao apenas remover. Rodar `cargo build` em apps/desktop/src-tauri pra confirmar que nada mais referencia as fns.

<details><summary>Justificativa da verificação</summary>

Core dead-code claim CONFIRMADO. Os tres comandos get_live_highlights_snapshot (live_analysis.rs:259), get_overlay_position (L280) e set_overlay_position (L290) estao registrados em lib.rs:94,96,97 mas grep em todo apps/desktop nao acha NENHUM caller: nem invoke() no TS (apenas analyze_live, toggle_overlay e clear_live_highlights sao chamados — clear_live_highlights tem callers em use-recording.ts:180,204, os outros tres tem zero), nem chamada interna no Rust (so aparecem na definicao + registro). Sao superficie de IPC morta de verdade. Nao existe persistencia de posicao de overlay em lugar nenhum (grep por overlayPosition/overlay_position no TS retorna vazio), entao get/set_overlay_position nao servem a ninguem.

PORÉM o sub-claim do auditor sobre "gap latente / abrir overlay depois de highlights ja emitidos mostra lista vazia" esta INCORRETO e infla a severidade. A janela overlay e definida estaticamente em tauri.conf.json (label "overlay", visible:false) e o toggle_overlay (live_analysis.rs:234-251) so faz window.show()/window.hide() — a webview nunca e destruida nem recarregada (grep por destroy/close/reload no overlay: zero). Logo o LiveHighlightsProvider (overlay-main.tsx) monta UMA vez no startup, antes de qualquer highlight, e acumula estado via o listener "live-analysis" (use-live-highlights.tsx:82-94) durante toda a sessao. Hide+show nao desmonta o provider, entao reabrir a overlay NAO mostra lista vazia. O snapshot so seria necessario se a webview fosse recriada — e nao e. Portanto e codigo morto puro, nao bug funcional.

Severidade rebaixada pra low: impacto real e apenas manutencao + superficie de comando IPC desnecessaria, sem quebra funcional nem impacto ao usuario. Fix e safe: deletar comandos nao-referenciados nao toca nos padroes cross-platform intencionais (startDragging, backgrounds solidos, decorations:false).

</details>

#### 24. 🟡 LOW — SecretStore registrado em .setup() enquanto todos os outros states usam .manage() no builder

- **Local:** `L62-79 (.manage(...) no builder vs SecretStore no .setup())`
- **Categoria:** professionalism · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** capture_state, sidecar_state, live_state, stealth_state e ApiBaseUrl sao registrados via `.manage(...)` direto no builder (L64-68), mas SecretStore e registrado dentro de `.setup(|app| { app.manage(...) })` (L76-79). SecretStore::new() e zero-cost (struct unit) e nao precisa do AppHandle, entao nao ha razao tecnica pra trata-lo diferente. A inconsistencia confunde e e facil alguem assumir que SecretStore depende de setup.

**Fix recomendado:** Mover o registro do SecretStore pra junto dos demais .manage() no builder e remover o closure .setup() vazio:

```rust
        .manage(stealth_state)
        .manage(http_proxy::ApiBaseUrl(base_url))
        .manage(secrets::SecretStore::new())
        .on_window_event(|window, event| { ... })
        // .setup(...) removido
```

Caveat: so remover o .setup() porque, neste estado atual, o closure nao faz mais nada alem do app.manage(SecretStore). Se houver intencao de adicionar futura inicializacao que dependa do AppHandle (ex: pre-aquecer a keychain, registrar listeners), pode-se manter o .setup() — mas mesmo nesse caso o SecretStore deveria ir pro .manage() do builder por consistencia, ja que nao depende do app handle. Fix seguro: nao altera comportamento (ambas as APIs registram o state antes do app.run()).

<details><summary>Justificativa da verificação</summary>

Achado factualmente correto. Em apps/desktop/src-tauri/src/lib.rs, capture_state, sidecar_state, live_state, stealth_state e http_proxy::ApiBaseUrl sao registrados via .manage(...) direto no builder (L64-68), mas secrets::SecretStore::new() e registrado dentro do closure .setup(|app| { app.manage(...) }) (L76-79). Em secrets.rs, SecretStore e um unit struct (L17 `pub struct SecretStore;`) e SecretStore::new() (L20-22) so retorna Self — zero-cost, sem alocacao, sem dependencia de AppHandle. Confirmado: nao ha razao tecnica pra registra-lo de forma diferente. .manage() (na build) e app.manage() (no setup) registram o state de forma equivalente antes do app rodar, entao nao ha diferenca de comportamento em runtime. E uma inconsistencia genuina de organizacao que pode levar um leitor futuro a assumir, erroneamente, que SecretStore precisa de inicializacao em setup-time. Porem e estritamente uma questao de legibilidade/consistencia: zero impacto funcional, sem bug, sem implicacao de seguranca ou cross-platform. Classificacao professionalism/low esta correta.

</details>

### `apps/desktop/src-tauri/src/live_analysis.rs`  ·  2 achado(s)

#### 25. 🟡 LOW — analyze_live duplica logica de auth/base_url/HTTP que ja existe no http_proxy

- **Local:** `L65-183 (analyze_live) vs http_proxy`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** analyze_live monta manualmente Authorization Bearer (L100-101), base_url (L77), Content-Type e tratamento de status — exatamente o que http_proxy ja centraliza (incl. SSRF guard e injecao de token a partir do SecretStore). Ter dois caminhos de saida HTTP com regras de seguranca diferentes (analyze_live NAO aplica o guard de SSRF nem o filtro de headers) e debito: a URL e fixa aqui entao o risco e baixo, mas a duplicacao significa que melhorias de timeout/retry/telemetria precisam ser feitas em dois lugares.

**Fix recomendado:** NAO rotear analyze_live pelo http_proxy diretamente: http_proxy retorna ProxyResponse {status, body} e nao faz emit de eventos Tauri (live-analysis, live-analysis-telemetry) nem atualiza LiveHighlightsState — sao responsabilidades especificas de analyze_live. O fix correto e extrair um helper HTTP comum (ex: client compartilhado + injecao de token), nao reuso literal do http_proxy. Ganho minimo e maior: trocar `reqwest::Client::new()` (L98) por um Client compartilhado via OnceLock (mesmo padrao de http_client() em http_proxy.rs L22-32) ou expor um getter publico desse client, eliminando criacao de client por chunk. Opcionalmente extrair um helper `fn bearer_token(secrets) -> Result<String>` para a leitura do access-token. Caveat: manter a logica de emit/state/telemetria intacta em analyze_live; nao introduzir o guard de SSRF aqui pois nao agrega (URL fixa) e adicionaria complexidade desnecessaria. Risco needs-care porque mexer no client compartilhado/timeout pode alterar comportamento de timeout (analyze_live usa 15s total via .timeout() na L104; o client do http_proxy usa 30s total + 5s connect — preservar o 15s especifico do live analysis ao usar client compartilhado, aplicando .timeout(15s) no request builder).

<details><summary>Justificativa da verificação</summary>

Confirmei a duplicacao. analyze_live (live_analysis.rs) replica logica que http_proxy.rs centraliza: fetch do access-token do SecretStore (live_analysis L72-75 vs http_proxy L89-93), montagem manual de Authorization Bearer (live_analysis L100-101 vs http_proxy L91), e Content-Type (live_analysis L102 vs http_proxy L85-87). O ponto mais concreto e load-bearing NAO esta no resumo do auditor: live_analysis.rs L98 faz `reqwest::Client::new()` a cada chamada, criando um client novo sem connection pooling e sem connect_timeout, enquanto http_proxy usa um Client compartilhado via OnceLock com pool_max_idle_per_host(4) e connect_timeout de 5s. Como analyze_live dispara por chunk de transcript (frequente, durante a reuniao), criar client novo a cada chunk e o custo real da duplicacao. Sobre SSRF: o auditor esta correto que aqui o risco e baixo — a URL e fixa (`{}/meetings/live-analyze` derivada de api_base_url(), nao controlada pelo renderer), entao o guard de origin do http_proxy nao se aplica de fato. Tambem confirmei que api_base_url() (lib.rs L27) e cacheado em OnceLock, entao a chamada repetida na L77 nao e problema de perf. Conclusao: e debito DRY real de manutencao (timeout/retry/telemetria/client em dois lugares), severidade low honesta e auto-reconhecida.

</details>

#### 26. 🟡 LOW — set_focus na overlay pode roubar foco do app em primeiro plano (Meet/Zoom)

- **Local:** `L227-256 (toggle_overlay) e capabilities/default.json`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** toggle_overlay chama `window.set_focus()` ao mostrar (L237). A overlay e alwaysOnTop + skipTaskbar e o objetivo do produto (ver dock/toggle_dock em windows.rs L33) e justamente NAO roubar foco do app em chamada. windows.rs::toggle_dock documenta e respeita isso ('Nao chamamos set_focus'), mas toggle_overlay faz o oposto sem justificar. Mostrar a overlay rouba o foco do Meet/Zoom em foreground, atrapalhando a reuniao que o usuario esta gravando.

**Fix recomendado:** Remover `let _ = window.set_focus();` da linha 237 de live_analysis.rs (no branch `if show`), alinhando toggle_overlay com a politica de nao-roubo-de-foco de toggle_dock. O bloco Windows de stealth-mode logo abaixo (linhas 240-248) NAO depende de set_focus e permanece intacto.

Isso e seguro porque o caminho onde o foco e intencional (clique do usuario em 'abrir overlay') ja chama focus_overlay_window separadamente em dock-bar.tsx:104; e o caminho automatico (start de gravacao em use-recording.ts:182) passa a NAO mais roubar foco do Meet/Zoom.

Opcionalmente, adicionar um comentario curto em toggle_overlay espelhando o de toggle_dock explicando o porque, e — se algum fluxo futuro precisar de foco junto com show — preferir chamar focus_overlay_window em vez de re-adicionar set_focus aqui. Nao tocar em capabilities/default.json: nao ha mudanca de permissao necessaria.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. apps/desktop/src-tauri/src/live_analysis.rs:237 chama window.set_focus() incondicionalmente ao mostrar a overlay. A overlay e alwaysOnTop:true + skipTaskbar:true (tauri.conf.json:36,39), e o dock irmao em windows.rs:33-34 documenta e respeita explicitamente a politica de NAO roubar foco do app em primeiro plano ('Nao chamamos set_focus — queremos que o dock fique visivel mas sem roubar o foco do app em primeiro plano (Meet/Zoom/Teams)'). toggle_overlay faz o oposto sem justificar.

O dano e concreto e ocorre no fluxo PRINCIPAL do produto: em use-recording.ts:182, ao INICIAR uma gravacao a overlay e mostrada automaticamente (invoke('toggle_overlay', { show: true })). Como o usuario tipicamente esta dentro de um Meet/Zoom quando aperta gravar, mostrar a overlay arranca o foco da janela da reuniao — exatamente o cenario que a politica do dock foi criada pra evitar.

A inconsistencia e ainda mais clara porque o codebase JA tem um comando separado pra 'mostrar + focar': windows.rs:55 focus_overlay_window (que chama set_focus). E no fluxo onde o foco E desejado (clique explicito do usuario em 'abrir overlay'), dock-bar.tsx:102-104 ja chama focus_overlay_window logo apos toggle_overlay. Ou seja, a separacao de responsabilidades ja existe e o set_focus dentro de toggle_overlay e redundante no caminho de clique e nocivo no caminho automatico.

Ajustes ao achado: (1) a referencia a capabilities/default.json e um falso alarme — set_focus nao exige capability extra aqui e nao precisa de mudanca; (2) a categoria e mais 'bug de comportamento (roubo de foco em reuniao ao vivo)' do que tech-debt puro, mas mantenho severidade low por ser irritacao de UX, nao perda de dados/crash. Confianca alta de que e real.

</details>

### `apps/desktop/src-tauri/src/stt_sidecar_test.rs`  ·  1 achado(s)

#### 27. 🟡 LOW — Integration test relies on system python3, fragile timing polls, and leaks the child process on assertion failure

- **Local:** `L8-105 (test_sidecar_fake_lifecycle)`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** The test hard-depends on a `python3` on PATH and the script at `../sidecar/tests/fake_sidecar.py`; on a machine/CI image without python3 it panics at the `.expect("failed to spawn fake sidecar")` instead of being skipped, turning an environment gap into a red test. It also uses busy-poll timeout loops (L52-61 `for _ in 0..50` with 200ms timeouts, L92-100) that are timing-sensitive and flaky under load. Most importantly, if `assert!(got_ready)` (L63) or `assert_eq!(got_final, 2)` (L104) fails, the function panics before `child.wait()` (L102) and before any `child.kill()`, leaving the fake sidecar process orphaned — the very zombie-process problem the production code (Drop impl, stop_recording drain) is designed to avoid.

**Fix recomendado:** Fix minimo de maior valor (test-only, nao toca producao nem padroes WebKitGTK):\n\n1) Garantir cleanup em TODO caminho de saida com uma linha: adicionar `.kill_on_drop(true)` ao builder do Command (entre L18 e L19). Isso cobre o panic de L63 e qualquer early-return, fechando definitivamente a janela de orfao sem precisar de scopeguard. Padrao tokio nativo, custo zero.\n\n2) (Opcional, baixa prioridade) Skip gracioso se python3 ausente: trocar `.expect(...)` por um match no `.spawn()` que faz `eprintln!(\"skipping: python3 unavailable\"); return;` quando o erro for NotFound. Evita teste vermelho em imagens sem interpretador.\n\n3) (Cosmetico, opcional) Unificar os tres loops (L52-61, L69-77, L92-100) no mesmo idioma baseado em deadline (`Instant::now() + timeout`) que ja e usado em L66-67, removendo a duplicacao de estilo. Nao e bug — so DRY.\n\nNAO ha necessidade de scopeguard nem feature/ignore flag: `kill_on_drop(true)` resolve o ponto critico com uma linha. A afirmacao do auditor de que L104 deixa processo orfao esta errada (vem depois do wait) e nao requer fix.

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo exato em /home/pollo/Dev/nora/apps/desktop/src-tauri/src/stt_sidecar_test.rs. As tres sub-afirmacoes tem graus diferentes de validade:\n\n1) DEPENDENCIA DE python3 (REAL): L14 `Command::new(\"python3\")` + L20 `.expect(\"failed to spawn fake sidecar\")`. Numa imagem sem python3 o teste panica em vez de pular, transformando gap de ambiente em teste vermelho. Confirmado que o script existe (apps/desktop/sidecar/tests/fake_sidecar.py). Valido mas baixo impacto: o projeto empacota um sidecar Python, entao python3 no CI e premissa razoavel.\n\n2) \"BUSY-POLL flaky\" (EXAGERADO): os loops L52-61/L69-77/L92-100 NAO sao spin-loops — fazem `await` em `tokio::time::timeout(... lines.next_line())`, cedendo o executor. Os budgets sao folgados (10s pra ready, 5s pra finals; o fake dorme so 1.5s no total). Flakiness real e improvavel. O problema verdadeiro e estilistico/DRY: tres idiomas de loop diferentes pra mesma coisa.\n\n3) PROCESSO ORFAO (TECNICAMENTE REAL, PRATICAMENTE EXAGERADO): so o `assert!(got_ready)` (L63) precede `child.wait()` (L102); o `assert_eq!(got_final, 2)` (L104) ja vem DEPOIS do wait, entao esse caminho esta ok — a afirmacao do auditor sobre L104 esta incorreta. Confirmei que NAO ha `kill_on_drop` no codigo e tokio default e `kill_on_drop=false`, entao um panic em L63 dropa o child sem kill explicito. POReM o fake le `for line in sys.stdin`; quando o `stdin` local e dropado no unwind, o python recebe EOF e sai do main() sozinho. O processo se auto-termina — nao e o \"zombie persistente\" que a producao (Drop em SidecarHandle L33, child.kill em L523) evita. O enquadramento alarmista esta exagerado.\n\nNet: item de tech-debt real e acionavel, mas baixa severidade e com partes 2 e 3 superdimensionadas pelo auditor. Escopo correto (apps/desktop), nao toca nlp-worker, nao colide com padroes intencionais cross-platform.

</details>


## 2. TypeScript — app

### `apps/desktop/src/App.tsx`  ·  1 achado(s)

#### 28. 🟡 LOW — Spinner de barras 'dotPulse' reimplementado inline em vez de usar NoraBars

- **Local:** `L70-90 (loading spinner) + login.tsx L148, overlay.tsx L270`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** O loading do App (L71-85) desenha 5 barras com [0.35,0.65,1,0.6,0.4] e animation dotPulse — essencialmente o mesmo soundwave que NoraBars ja faz (com active). As alturas ate diferem do brand oficial (HEIGHTS=[0.42,0.78,1,0.66,0.52] em nora-bars.tsx), entao o loading mostra um soundwave com proporcoes diferentes da marca. dotPulse e referenciado solto em 4 arquivos (App, login, overlay, nora-bars) com duracoes variadas.

**Fix recomendado:** Substituir o JSX inline do loading (App.tsx L71-85) por NoraBars, mas com override de cor para preservar a aparencia atual (ink, nao o vermelho default do active): <NoraBars active animate color="var(--ink)" size={28} />. O prop color sobrescreve o active red (nora-bars.tsx L55: fill = color || ...). Isso elimina a duplicacao, alinha as alturas a marca e mantem dotPulse perpetuo via active (o animate so toca a entrada uma vez e settla em 'done'). Caveats: (a) largura das barras muda 4->3px e ganha a animacao de entrada rise — diferenca visual minima/aceitavel; valide rapido o frame do loading. (b) NAO mexer em login.tsx L148 nem overlay.tsx L270 — sao dots, nao soundwaves; manter o keyframe dotPulse compartilhado. App.tsx ainda nao importa NoraBars, entao adicionar import { NoraBars } from \"@/components/brand/nora-bars\".

<details><summary>Justificativa da verificação</summary>

Confirmado o nucleo do achado: App.tsx L71-85 reimplementa um soundwave inline (5 barras + animation dotPulse) que e exatamente o que NoraBars faz com active=true (nora-bars.tsx L100-110, mesmo dotPulse 1.4s e mesmo stagger i*0.12s). As alturas inline [0.35,0.65,1,0.6,0.4] (width:4) divergem da single-source-of-truth da marca HEIGHTS=[0.42,0.78,1.0,0.66,0.52] (width:3, nora-bars.tsx L4), entao o loading mostra a onda com proporcoes off-brand. DRY + inconsistencia de marca reais. POReM o achado tem dois erros: (1) o framing de 'dotPulse solto em 4 arquivos' eh enganoso — login.tsx L148 e overlay.tsx L270 sao DOTS unicos (indicador de status / cursor de digitacao), nao soundwaves; eles legitimamente reutilizam apenas o keyframe @keyframes dotPulse (styles.css L125), que e um helper CSS compartilhado correto, e NAO deveriam virar NoraBars. So o caso do App eh substituivel. (2) O fix proposto <NoraBars active animate/> esta INCOMPLETO/incorreto: com active=true e sem color, NoraBars usa fill=var(--danger) (#c97766 terracota, nora-bars.tsx L55), enquanto o loading atual usa var(--ink) (#15171A, quase preto). Aplicar o fix literal trocaria o spinner de quase-preto para vermelho — regressao visual. Sem impacto funcional; puramente cosmetico, severity low correta.

</details>


## 2. TypeScript — components

### `apps/desktop/src/components/overlay.tsx`  ·  19 achado(s)

#### 29. 🔴 HIGH — Header da overlay usa data-tauri-drag-region — drag quebrado no WebKitGTK (Linux)

- **Local:** `L1306-1315 (overlay header div)`
- **Categoria:** cross-platform · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** O header da overlay declara `data-tauri-drag-region` + `cursor: "move"` (L1307, L1313) mas NAO tem handler de onMouseDown chamando startDragging(). O proprio codebase documenta explicitamente que `data-tauri-drag-region` / `-webkit-app-region: drag` NAO funcionam no WebKitGTK/Linux (titlebar.tsx L11-14) e por isso a janela main (titlebar.tsx L66-71) e o dock (dock-bar.tsx L121-127) usam getCurrentWebviewWindow().startDragging() no mousedown. A overlay roda com decorations:false, entao no Linux o usuario nao consegue arrastar a janela overlay pelo header — o cursor vira 'move' mas nada acontece. Isso e exatamente a inconsistencia que o briefing pediu pra avaliar.

**Fix recomendado:** Espelhar o padrao da titlebar.tsx (que e o de referencia, ja com filtragem de duplo-clique) em vez de pendurar o handler no header inteiro. Passos: (1) importar `getCurrentWebviewWindow` de '@tauri-apps/api/webviewWindow' no topo da overlay.tsx (atualmente ausente). (2) criar `const win = useMemo(() => getCurrentWebviewWindow(), [])` e `const onDragMouseDown = (e: React.MouseEvent) => { if (e.button !== 0) return; win.startDragging().catch((err) => console.warn('[overlay] startDragging failed:', err)); }`. (3) Remover `data-tauri-drag-region` do header (L1307). (4) CUIDADO com a estrutura: nao basta por o onMouseDown no header inteiro porque os <button> do canto direito (Descartar/Parar/config/minimizar/fechar) tem mousedown que BUBBLEA ate o header — startDragging dispararia ao clicar neles. O padrao seguro da titlebar e por o handler SO na regiao de arraste esquerda (o div flex-1) e deixar os controles num div irmao SEM handler. Aqui o header ja tem dois filhos: o div esquerdo (L1316 \"flex items-center gap-2.5 min-w-0\") e o div direito dos botoes (L1346 \"flex items-center gap-1.5\"). Aplicar onMouseDown={onDragMouseDown} no div esquerdo (L1316) e manter cursor:'move' nele (mover o cursor:'move' do header pra esse div). Alternativa minima aceitavel: por o handler no header mas adicionar onMouseDown={(e)=>e.stopPropagation()} no div de botoes. A primeira opcao e mais limpa e identica ao padrao validado. Manter o .catch com console.warn (nao engolir silenciosamente) como nos demais. Risco: medio — testar que clicar nos botoes nao inicia drag e que area vazia do header arrasta; em macOS/Windows o comportamento segue funcionando pois startDragging e cross-platform.

<details><summary>Justificativa da verificação</summary>

Confirmado com codigo exato. O header da overlay (apps/desktop/src/components/overlay.tsx L1306-1315) declara `data-tauri-drag-region` + `cursor: \"move\"` (L1307, L1313) mas NAO tem nenhum onMouseDown chamando startDragging(). Grep no arquivo inteiro retornou ZERO ocorrencias de getCurrentWebviewWindow/startDragging — a API nem sequer e importada na overlay.tsx (so estao importados invoke e emit, L2-3). A janela overlay roda com decorations:false (tauri.conf.json L34) e transparent/alwaysOnTop, ou seja nao ha titlebar nativa pra arrastar. O proprio codebase documenta explicitamente que data-tauri-drag-region / -webkit-app-region: drag NAO funcionam no WebKitGTK/Linux (titlebar.tsx L11-14) e por isso main (titlebar.tsx L66-71) e dock (dock-bar.tsx L122-128) usam getCurrentWebviewWindow().startDragging() no mousedown. Dano concreto: no Linux/WebKitGTK o usuario nao consegue arrastar a janela overlay pelo header — o cursor vira 'move' mas nada acontece. E exatamente a inconsistencia que o briefing pediu pra avaliar. Em macOS/Windows o data-tauri-drag-region funciona, entao o bug e especifico de Linux (o SO alvo primario do dev, branch fix/overlay-*).

</details>

#### 30. 🔴 HIGH — Overlay header usa data-tauri-drag-region (não funciona no WebKitGTK/Linux) em vez de startDragging()

- **Local:** `L1306-1315 (overlay header div)`
- **Categoria:** cross-platform · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** O header da overlay declara `data-tauri-drag-region` + `cursor: "move"` mas NÃO tem `onMouseDown` chamando `getCurrentWebviewWindow().startDragging()`. Em todo o resto do desktop (dock-bar.tsx L122-128, titlebar.tsx L66-71, resize-handles.tsx L51-57) o padrão consciente é usar startDragging()/startResizeDragging() exatamente porque `data-tauri-drag-region`/`-webkit-app-region: drag` NÃO funcionam no WebKitGTK (Linux). Logo, no Linux a overlay não pode ser arrastada pelo header — o cursor 'move' aparece mas o arraste é morto. É a inconsistência que o próprio comentário do titlebar.tsx (L11-16) avisa que quebra no Linux.

**Fix recomendado:** Importar `getCurrentWebviewWindow` de "@tauri-apps/api/webviewWindow" (ainda nao importado em overlay.tsx). No div do header (L1306) remover `data-tauri-drag-region` e adicionar onMouseDown seguindo o padrao validado em titlebar.tsx/dock-bar.tsx. CAVEAT IMPORTANTE (por isso needs-care): os botoes do header (Descartar/Parar e salvar/Configuracoes/Minimizar) estao DENTRO do header e usam onClick, nao onMouseDown — um mousedown neles vai borbulhar ate o header e disparar startDragging(), que engole o clique subsequente. Portanto NAO basta copiar cego o handler do dock; precisa guardar contra alvo dentro de botao. Sugestao: `onMouseDown={(e) => { if (e.button !== 0) return; if ((e.target as HTMLElement).closest("button")) return; e.preventDefault(); getCurrentWebviewWindow().startDragging().catch((err) => console.warn("[overlay] startDragging failed:", err)); }}`. Manter `cursor: "move"`. Preferir `console.warn` em vez de `.catch(()=>{})` pra ficar consistente com titlebar/dock (que logam o erro). Opcional: extrair o handler de drag num helper compartilhado (titlebar, dock e overlay repetem a mesma logica) — mas isso e DRY separado, nao bloqueia o fix.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo exato. overlay.tsx L1306-1314: o div do header da overlay usa `data-tauri-drag-region` + `cursor: "move"` e NAO tem nenhum onMouseDown chamando startDragging(). Verifiquei que `getCurrentWebviewWindow` nem esta importado no arquivo (imports L1-13) e que `startDragging`/`data-tauri-drag-region` so aparecem 1x no arquivo (o proprio L1307) — entao nao ha drag tratado em outro lugar. A janela "overlay" tem `decorations: false` (tauri.conf.json L34), logo o header custom e a UNICA forma de mover a janela. O proprio comentario do titlebar.tsx (L11-16) documenta explicitamente: "`-webkit-app-region: drag` / `data-tauri-drag-region` NAO funcionam no WebKitGTK (Linux)", e tanto titlebar.tsx (onDragMouseDown L66-71 -> win.startDragging()) quanto dock-bar.tsx (L120-128) seguem o padrao consciente de startDragging() no mousedown. Dano concreto: no Linux/WebKitGTK o cursor "move" aparece mas o arraste e morto — usuario nao consegue reposicionar a overlay pelo header. E exatamente a inconsistencia que o briefing pediu pra avaliar. Severidade high: bug funcional real numa janela sem decoracao nativa (sem outra forma de mover) na plataforma primaria de dev (Linux/Arch).

</details>

#### 31. 🔴 HIGH — Overlay header usa data-tauri-drag-region — janela inarrastavel no Linux (WebKitGTK)

- **Local:** `L1306-1314 (overlay Header div, data-tauri-drag-region)`
- **Categoria:** cross-platform · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** A janela overlay tem decorations:false (tauri.conf.json L34) e seu unico afford de arraste e o `data-tauri-drag-region` no header (L1307). O proprio codebase documenta em titlebar.tsx (L11-13) e dock-bar.tsx (L120) que `data-tauri-drag-region`/`-webkit-app-region: drag` NAO funcionam no WebKitGTK (Linux); por isso titlebar e dock usam getCurrentWebviewWindow().startDragging() no mousedown. overlay.tsx nao tem nenhum startDragging/onMouseDown (grep confirma zero ocorrencias no arquivo). Resultado: no Linux a overlay nao pode ser movida — bug real e inconsistencia exata que o brief pediu pra avaliar.

**Fix recomendado:** Trocar `data-tauri-drag-region` no Header da overlay (overlay.tsx L1307) por um handler de arraste seguindo o padrao ja validado em titlebar.tsx. NAO basta jogar onMouseDown no div inteiro do header, porque ele contem botoes interativos (L1346+: gravar/parar/minimizar). O padrao mais seguro (e o que titlebar.tsx L86-106 usa) e colocar o onMouseDown numa sub-regiao de arraste que NAO inclui os botoes — ex: envolver so a parte esquerda do header (NoraBars + label + duracao, L1316-1345) num div com `onMouseDown={onDragMouseDown}`, deixando o grupo de botoes (L1346) fora dessa regiao. O handler deve ser: `const onDragMouseDown = (e: React.MouseEvent) => { if (e.button !== 0 || e.detail > 1) return; getCurrentWebviewWindow().startDragging().catch((err) => console.warn("[overlay] startDragging failed:", err)); };` (e.detail>1 evita engolir duplo-clique; copia titlebar.tsx L66-71). Importar `getCurrentWebviewWindow` de "@tauri-apps/api/webviewWindow". Remover o `cursor: "move"` do div externo do header (L1313) e aplicar `cursor: "default"`/"move" so na sub-regiao de drag. Caveat: confirmar que os botoes do header continuam clicaveis (eles sao filhos separados, entao se ficarem fora da regiao de drag nao ha conflito). Opcional (nice-to-have, NAO bloqueante): extrair um hook `useDragHandle()` compartilhado por titlebar/dock/overlay, ja que o padrao startDragging esta triplicado — mas isso e refactor DRY separado, nao parte do fix do bug.

<details><summary>Justificativa da verificação</summary>

Confirmado com codigo exato. overlay.tsx L1306-1314: o Header da overlay usa `data-tauri-drag-region` (L1307) + `cursor: "move"` (L1313) como UNICA pega de arraste. tauri.conf.json L27-34: a janela "overlay" tem `decorations: false` (L34), entao nao ha titlebar nativa — a pega in-app e o unico jeito de mover a janela. grep confirma ZERO ocorrencias de startDragging/onMouseDown/getCurrentWebviewWindow em overlay.tsx (so a linha do data-tauri-drag-region). titlebar.tsx L11-13 e dock-bar.tsx L120-121 documentam explicitamente no proprio codebase que `-webkit-app-region: drag` / `data-tauri-drag-region` NAO funcionam no WebKitGTK (Linux), e ambos usam `getCurrentWebviewWindow().startDragging()` no mousedown. Dano concreto: no Linux a janela overlay fica inarrastavel. E exatamente a inconsistencia que o brief mandou avaliar. Severity high justificada: bug funcional real numa plataforma alvo (o ambiente do dev e Arch Linux), nao estilo.

</details>

#### 32. 🟠 MEDIUM — handleCancel nao seta stopping — janela de race pra double-emit de cancel/stop

- **Local:** `L1262-1269 (handleCancel)`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** handleStop seta setStopping(true) e desabilita os botoes; ja handleCancel NAO seta stopping nenhum e nao desabilita nada (so checa `if (stopping) return` no inicio). Como os botoes Descartar/Parar so usam `disabled={stopping}`, depois de clicar 'Descartar' o usuario pode clicar de novo (ou clicar 'Parar e salvar') antes do evento 'nora://cancel-recording' ser processado, emitindo cancel+stop concorrentes. O CloseConfirmDialog tambem chama handleCancel/handleStop direto sem trava. O comentario em handleStop ('Wait for status events to ripple back') mostra que o fluxo e assincrono e essa janela existe.

**Fix recomendado:** Setar setStopping(true) no inicio de handleCancel, espelhando handleStop, para desabilitar AMBOS os botoes durante a operacao em voo (e cobrir tambem o CloseConfirmDialog que chama os handlers direto). CAVEAT OBRIGATORIO (senao o fix introduz bug pior): o caminho de cancel NUNCA emite nora://save-result, que e o unico lugar que reseta stopping (overlay.tsx L1133). E a overlay e apenas ESCONDIDA, nao destruida — live_analysis.rs L249-250 (toggle_overlay show:false) faz window.hide(), entao o estado React `stopping` PERSISTE entre sessoes. Se setar stopping=true em handleCancel sem reset, na proxima gravacao os botoes montariam permanentemente desabilitados. Portanto o reset precisa entrar no effect de "nova sessao" ja existente (overlay.tsx L1143-1145: `if (isRecording && lines.length === 0) setSaveError(null)`) adicionando `setStopping(false)` ali, e/ou um reset quando isRecording transiciona para false. Com essa adicao, o fix fica safe.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. apps/desktop/src/components/overlay.tsx L1252-1269: handleStop faz setStopping(true) (desabilita os dois botoes via disabled={stopping} em L1351/L1381); handleCancel (L1262) so checa `if (stopping) return` e NUNCA seta stopping. Os botoes "Descartar"/"Parar e salvar" so estao montados enquanto isRecording===true (L1347) e so desabilitam por stopping. Como isRecording vem de useLiveTranscript() (L1043) e so vira false quando o status ripple-a de volta do Rust apos stopRecording (assincrono), existe uma janela real entre clicar "Descartar" e a UI atualizar.

Dano concreto verificado no fluxo: ao clicar "Descartar", cancel() (apps/desktop/src/hooks/use-active-recording.tsx L156-162) chama setIsFinishing(false) e persistMeta(null). Se o usuario clicar "Parar e salvar" nessa janela, handleStop -> stopAndSave() NAO e barrado pelo guard `if (isFinishing) return` (L116, isFinishing acabou de ser zerado por cancel), le metaRef.current ja null e emite save-result {ok:false, error:"Sem metadados da reuniao..."} -> toast de erro enganoso sobre uma gravacao que o usuario acabou de descartar. Double-click Descartar->Descartar e mais benigno (stop_recording em commands.rs L184 e idempotente, erros swallowed). A direcao inversa (Parar->Descartar) JA esta protegida porque handleStop seta stopping=true e handleCancel checa stopping. O CloseConfirmDialog (L1742-1758) chama handleStop/handleCancel direto sem trava propria, herdando a mesma janela. Severidade medium correta: confusao de UX, nao perda de dado.

</details>

#### 33. 🟠 MEDIUM — ID de FeedRow baseado em norm(texto truncado em 80) pode colidir e dropar deteccoes distintas no React key

- **Local:** `L344-365 buildFeed / L346 norm`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** buildFeed gera o id como `${kind}-${norm(text)}` onde norm = lowercase+trim+slice(0,80). Dois highlights distintos do mesmo kind cujos primeiros 80 chars normalizados coincidam (ex: duas tarefas longas com mesmo prefixo) produzem o MESMO id. Esse id e usado como React `key` no map de DetectionRow (L559-564). Keys duplicadas fazem o React renderizar so um e/ou reconciliar errado (estado/animacao trocados). O merge upstream (use-live-highlights) ja dedupa por overlap 0.7, mas itens 'diferentes o suficiente pra nao serem dup' ainda podem compartilhar o prefixo de 80 chars.

**Fix recomendado:** Preferível (mais robusto): carimbar um uid estável no merge, em use-live-highlights.tsx. Adicionar `uid?: string` em LiveHighlightItem/LiveTaskItem e, nos pushes de itens NOVOS em mergeItems (L241) e mergeTasks (L262), gerar `uid: crypto.randomUUID()` junto com `receivedAt`. Como existing é preservado por `[...existing]`, o uid persiste entre merges (só itens novos recebem). Em buildFeed (overlay.tsx:344-365) usar esse uid como id/key. Isso é safe: não altera dedup nem ordenação. NÃO usar índice do array como key — o feed é reordenado por receivedAt (overlay.tsx:364), então índice causaria reconciliação errada. Alternativa mais simples e também safe: em buildFeed remover o slice(0,80) e usar o texto completo (`norm = s => s.toLowerCase().trim()`); como a dedup garante que dois itens sobreviventes nunca têm texto normalizado idêntico, o texto completo é livre de colisão para keys, ao custo de keys mais longas. Ambas resolvem; o uid no merge é o mais à prova de futuro.

<details><summary>Justificativa da verificação</summary>

Confirmado e reproduzível. Em apps/desktop/src/components/overlay.tsx:346 o id é `${kind}-${norm(text)}` com norm = lowercase+trim+slice(0,80), e esse id é usado como React key em overlay.tsx:561 (`key={row.id}`) no map de DetectionRow. A dedup do merge (use-live-highlights.tsx isDuplicate/mergeItems/mergeTasks) NÃO impede a colisão: ela só barra itens idênticos, substring um do outro, ou com token-overlap >= 0.7. Construí duas detecções do mesmo kind que passam na dedup mas colidem na key — prefixo de 80 chars idêntico, caudas divergentes ("...cliente Acme Corporation..." vs "...cliente Beta Industries..."): token overlap = 0.619 (< 0.7), nenhuma é substring da outra, normNew !== normOld, logo isDuplicate(b,[a]) = false E norm(a) === norm(b) = true. Resultado: duas FeedRow distintas com a MESMA key. React com keys duplicadas renderiza só uma e/ou reconcilia errado (estado/animação `fresh` trocados), fazendo uma detecção real sumir do feed ao vivo durante a reunião. Dano concreto: perda silenciosa de informação na UI. Não é nitpick. Severidade medium: é bug de correção/UX user-facing, mas o gatilho exige um formato de texto específico (mesmos 80 chars iniciais com cauda divergente), o que é plausível mas não comum em highlights, que tendem a ser concisos.

</details>

#### 34. 🟠 MEDIUM — CloseConfirmDialog da overlay usa keyframes paletteFadeIn/paletteSlideIn que só existem na janela main

- **Local:** `L1792, L1806 (CloseConfirmDialog) + def em apps/desktop/src/components/new-meeting-modal.tsx L344-350`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** CloseConfirmDialog (renderiza na janela 'overlay' via overlay-main.tsx) referencia `animation: paletteFadeIn ...` e `paletteSlideIn ...`. Esses @keyframes NÃO estão em styles.css — só são definidos no `<style>` inline de new-meeting-modal.tsx, que só monta na janela 'main' (App.tsx). Como webviews Tauri são documentos separados, na janela overlay esses keyframes não existem e a animação de entrada do diálogo silenciosamente não roda (aparece sem transição/'pisca'). overlayDrawerIn (L1024) e notifIn (L278) estão corretamente definidos inline nos próprios componentes da overlay, evidenciando que paletteFadeIn/SlideIn ficaram órfãos.

**Fix recomendado:** Move `@keyframes paletteFadeIn` and `@keyframes paletteSlideIn` from the inline `<style>` in new-meeting-modal.tsx (L344-350) into src/styles.css, alongside the existing shared keyframes (nora-spin, panelIn, revealUp, dotPulse). styles.css is imported by all three entrypoints (main.tsx, overlay-main.tsx, dock-main.tsx), so both the main-window modal and the overlay-window CloseConfirmDialog will get the definitions. Then delete the now-redundant inline `<style>` block in new-meeting-modal.tsx. Caveat: the two consumers use slightly different durations (modal 180/220ms at L103/L120 vs dialog 160/200ms at L1792/L1806) but identical keyframe names and identical from/to steps, so a single shared definition serves both — keep the durations in each call site's `animation` shorthand, only the @keyframes blocks are shared. This is safe: pure animation definitions, no DOM/style side effects, no impact on the intentional WebKitGTK drag/transparency patterns.

<details><summary>Justificativa da verificação</summary>

Verified all four core claims. overlay.tsx L1792/L1806 reference `paletteFadeIn`/`paletteSlideIn`, whose ONLY definition is the inline `<style>` in new-meeting-modal.tsx L344-350. grep confirms those keyframes are absent from styles.css (which holds nora-spin, panelIn, revealUp, dotPulse, etc.). new-meeting-modal.tsx is imported solely by App.tsx (the "main" window); it is never imported by overlay-main.tsx, which mounts OverlayPage into overlay-root. CloseConfirmDialog (overlay.tsx L1760) is rendered exclusively inside OverlayPage (L1743). Since Tauri webviews are separate documents, the modal's inline keyframes never load in the overlay webview, so the dialog's `animation` properties reference undefined @keyframes and are ignored per CSS spec — the dialog pops in with no entrance transition. The contrast in the finding is accurate: overlay.tsx self-defines `overlayDrawerIn` inline (L1024) and overlay-notifications.tsx self-defines `notifIn` (L278), proving these two palette keyframes are genuinely orphaned in the overlay window. The damage is cosmetic only (missing fade/slide-in, abrupt appearance), not a crash or functional break, so medium is the honest ceiling and arguably low; kept at medium since it's a real cross-window CSS scoping bug.

</details>

#### 35. 🟡 LOW — formatDuration duplicado (e com comportamentos divergentes) entre overlay e dock

- **Local:** `L70-76 (overlay) e dock-bar.tsx L16-20`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** formatDuration existe em overlay.tsx (L70) e em dock-bar.tsx (L16) com implementacoes DIFERENTES: a do overlay trata horas (h:mm:ss para >1h), a do dock so faz mm:ss (sem horas — uma gravacao de 90min mostra '90:00' no dock e '1:30:00' no overlay). Alem da duplicacao DRY, isso gera inconsistencia visual entre as duas janelas pra mesma sessao.

**Fix recomendado:** Criar src/lib/format.ts exportando a versao hours-aware de formatDuration (a do overlay, L70-76). Importar nos dois lugares: overlay.tsx (remover L70-76) e dock-bar.tsx (remover L16-20). Resultado: dock passa a mostrar "1:30:00" igual a overlay para gravacoes >60min — essa e a propria correcao da inconsistencia, comportamento desejado e seguro (gravacoes <60min permanecem identicas: ambas ja faziam mm:ss). NAO unir relTime com formatDuration: sao semanticas diferentes (relTime e offset por-mensagem, sempre mm:ss; formatDuration e duracao total da sessao). Se quiser consolidar, mover relTime para o mesmo src/lib/format.ts como export separado, preservando sua logica mm:ss atual — caveat: relTime recebe ms e formatDuration recebe segundos, manter assinaturas distintas pra nao introduzir bug de unidade.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. Existem DUAS funcoes formatDuration com implementacoes divergentes: overlay.tsx L70-76 trata horas (`h > 0 ? h:mm:ss : mm:ss`) e dock-bar.tsx L16-20 faz so mm:ss. Verifiquei que ambas recebem o MESMO dado: tempo decorrido da sessao de gravacao em segundos (dock-bar.tsx L96-97 calcula `Math.floor((Date.now() - startedAt) / 1000)`; overlay consome `duration` vindo de use-recording.ts/use-live-transcript.tsx, tambem segundos). Usos: dock-bar L222 e overlay L1342. Logo, uma gravacao de 90min de fato mostra "90:00" no dock e "1:30:00" na overlay — inconsistencia visual real entre as duas janelas pra mesma sessao, alem da duplicacao DRY. Nao existe util compartilhado (src/lib/ nao tem format.ts). Severity low esta correta: e cosmetico e so aparece acima de 60min de gravacao; sem crash/perda de dado. AJUSTE importante: a parte do fix que sugere unificar relTime (overlay L78-82) com formatDuration esta over-reached — relTime e outro caso de uso (offset de timestamp por mensagem dentro da sessao, usado em L137 `relTime(group.startTs - startedAt)`, intencionalmente sempre mm:ss tipo timestamp de chat). Fundi-lo no formatDuration mudaria a semantica (bolhas de chat passariam a exibir h:mm:ss em reunioes longas) — isso e mudanca de comportamento, nao dedup. relTime pode ir pro mesmo arquivo como funcao SEPARADA, nunca mesclado.

</details>

#### 36. 🟡 LOW — DOCK_STORAGE_KEY e helpers de load/save da pref de dock duplicados em 3+ arquivos

- **Local:** `L18, L54-68; dock-bar.tsx L8,L110; use-active-recording.tsx L18,L102`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** A string "nora.dock.visible" esta hardcoded em overlay.tsx (L18), dock-bar.tsx (L8) e use-active-recording.tsx (L18), e a logica de loadDockPref/saveDockPref (overlay L54-68) reaparece como localStorage.getItem/setItem cru nos outros arquivos. Chave de storage repetida em multiplos lugares e exatamente o tipo de fragilidade que um typo silencioso quebra (o try/catch engole o erro). Idem SPEAKER_OVERRIDES_KEY/HIGHLIGHTS_STORAGE_KEY que so vivem no overlay mas seguem o mesmo padrao copy-paste de try/catch load/save.

**Fix recomendado:** Criar src/lib/dock-pref.ts (ou um src/lib/storage.ts com STORAGE_KEYS + helpers tipados) exportando: a constante DOCK_STORAGE_KEY = "nora.dock.visible" e os helpers loadDockPref()/saveDockPref(v) — movendo a implementacao atual de overlay.tsx:54-68 sem alterar a semantica (default true, "1"/"0", try/catch silencioso preservados pra robustez quando localStorage indisponivel). Substituir: overlay.tsx (importar e remover L18,54-68), dock-bar.tsx (importar DOCK_STORAGE_KEY ou usar saveDockPref(false) em vez do localStorage.setItem cru em L109-113), use-active-recording.tsx (substituir o IIFE de L100-107 por loadDockPref()). PRESERVAR o emit "nora://dock-visibility-changed" no dock-bar.tsx — ele e necessario pra sincronizar webviews e nao deve ser absorvido no helper. SPEAKER_OVERRIDES_KEY e HIGHLIGHTS_STORAGE_KEY podem ficar como estao (single-file) ou ser movidos junto por consistencia, mas sao opcionais e fora do nucleo do achado. Refactor mecanico, zero mudanca de comportamento; validar com `npm run typecheck`.

<details><summary>Justificativa da verificação</summary>

Confirmado parcialmente. A string "nora.dock.visible" esta de fato hardcoded em 3 arquivos: overlay.tsx:18, dock-bar.tsx:8 e use-active-recording.tsx:18 (verificado por grep). A logica de pref do dock vive como helpers nomeados loadDockPref/saveDockPref em overlay.tsx:54-68 (default true), mas reaparece copy-paste como localStorage cru nos outros: dock-bar.tsx:109-113 (so write) e use-active-recording.tsx:100-107 (read com o MESMO default `v == null ? true : v === "1"` re-implementado inline). O default `true` acoplado em dois lugares (overlay:57 e use-active-recording:103) e o ponto real de fragilidade — se um arquivo divergir no default ou na string, o toggle "Mostrar dock" fica dessincronizado entre webviews silenciosamente, porque os try/catch engolem qualquer erro e localStorage nao dispara `storage` event entre webviews Tauri (a propria base de codigo compensa isso com emit "nora://dock-visibility-changed"). Isso e divergencia/manutencao real, nao bug atual — severidade low esta correta. AJUSTE no escopo do achado: SPEAKER_OVERRIDES_KEY (overlay:17) e HIGHLIGHTS_STORAGE_KEY (overlay:19) NAO sao duplicados — grep confirma que cada um aparece em UM unico arquivo (overlay.tsx). Eles seguem o mesmo padrao try/catch load/save, mas como helpers locais de arquivo unico nao representam risco de divergencia cross-file; centraliza-los e polimento opcional, nao reducao de duplicacao. A premissa do auditor de "duplicados em 3+ arquivos" so se aplica ao DOCK_STORAGE_KEY.

</details>

#### 37. 🟡 LOW — Cores rgba/hex de status hardcoded e repetidas em vez de tokens CSS var

- **Local:** `L1539, L1786, L1816, L1908, L1397 e similares; overlay-notifications.tsx L51,L73,L85`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** Valores como rgba(201, 119, 102, ...) (danger) aparecem hardcoded em multiplos pontos do overlay (L1539, L1540, L1816, L1908) enquanto existe a var --danger-ink usada em outros lugares — mistura inconsistente de token vs literal pra mesma cor. Em overlay-notifications.tsx os accents 'action' (#3f8a5e / rgba(98,181,133,...) L51) e 'task' (#a37528 / rgba(212,160,76,...) L73) sao hex/rgba crus fora do sistema de tokens OKLCH editorial do projeto (ADR 0013). `background: "#000"` no hover do botao Parar (L1397) tambem e magico (mais escuro que --ink sem token). Dificulta tema/dark-mode futuro e foge da paleta.

**Fix recomendado:** Endereçar o gap de variantes com alpha de forma consistente com os tokens JA existentes (nao inventar --warning-*). Opcao A (recomendada, baixo risco visual): adicionar tokens de canais rgb em styles.css — ex. --danger-rgb: 201 119 102; --success-rgb: 98 181 133; --warn-rgb: 212 160 76; — e referenciar como background: rgb(var(--danger-rgb) / 0.10) nos pontos atuais (overlay.tsx L1539/1540/1816/1908, overlay-notifications.tsx L51/74/85, e os demais arquivos para nao deixar correcao pela metade). Opcao B: usar color-mix(in srgb, var(--danger) 10%, transparent) — mais legivel mas menos suportado em WebKitGTK antigo; preferir A por seguranca cross-platform. Para os accents solidos crus, trocar #3f8a5e -> var(--success-ink), #a37528 -> var(--warn-ink). Caveats: (1) validar visualmente que rgb(var(--*-rgb)/alpha) produz o MESMO pixel que os literais atuais — sao elementos internos (banners/dialogos), nao o background da janela overlay, entao nao colidem com a restricao WebKitGTK de transparencia de JANELA; (2) e refactor amplo (~7 arquivos), idealmente PR proprio de baixa prioridade, nao misturar com fix funcional; (3) L1397 #000 do hover pode virar token opcional (ex. --ink-strong) mas e omissivel dado o impacto desprezivel.

<details><summary>Justificativa da verificação</summary>

Confirmei nos arquivos: as cores citadas sao literais das MESMAS cores ja definidas como tokens em styles.css. Mapeamento exato: rgba(201,119,102,*)=#c97766=--danger; #3f8a5e=--success-ink, rgba(98,181,133,*)=#62b585=--success; #a37528=--warn-ink, rgba(212,160,76,*)=#d4a04c=--warn (styles.css L23-28). A inconsistencia token-vs-literal e demonstravel DENTRO do mesmo componente: overlay.tsx L1546 usa stroke=\"var(--danger-ink)\" enquanto L1539/1540 do MESMO banner de erro usa rgba(201,119,102,*) cru; mesmo padrao no modal de descarte L1816/1908 (var(--danger-ink) no texto, rgba literal no bg/border). overlay-notifications.tsx L50-51 (action) e L73-74 (task) usam hex/rgba crus enquanto os outros accents do mesmo objeto (decision L40-41, observation L61-62, warn L84) usam var(). Logo a mistura inconsistente e real e prejudica tema/dark-mode futuro (ADR 0013 fala em paleta editorial OKLCH). PORÉM ajusto a severidade e o fix: (1) e cosmetico, sem bug de runtime nem impacto de UX — low e honesto; (2) o problema NAO e exclusivo do overlay — esta espalhado por login.tsx, meeting-detail.tsx, settings.tsx, meetings.tsx, dock-bar.tsx, new-meeting-modal.tsx (grep confirma ~25 ocorrencias), entao tratar so o overlay seria correcao parcial; (3) o fix proposto pelo auditor erra nomes: o projeto ja tem --warn/--warn-ink (nao --warning), --success/--success-ink, --danger/--danger-ink — criar --warning-* e redundante/divergente. O gap REAL e que faltam variantes com alpha (os backgrounds rgba(...,0.10/0.16/0.30) nao tem token equivalente, pois var() de hex solido nao adiciona opacidade). L1397 background:\"#000\" no hover do botao Parar e magic value menor (preto puro proposital, micro-interacao) — baixissimo impacto, nao vale tratar isolado.

</details>

#### 38. 🟡 LOW — formatDuration duplicado (e divergente) entre overlay.tsx e dock-bar.tsx

- **Local:** `L70-76 (formatDuration) + apps/desktop/src/components/dock-bar.tsx L16-20`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** Existem duas implementações de formatDuration: overlay.tsx (suporta horas: h:mm:ss) e dock-bar.tsx (só mm:ss, estoura visualmente após 99min). Lógica de formatação de tempo de gravação espalhada e inconsistente — o dock pode mostrar '125:30' enquanto a overlay mostra '2:05:30' pra mesma sessão.

**Fix recomendado:** Extrair `formatDuration(seconds: number)` na versão hours-aware da overlay (L70-76) para um util compartilhado, ex. apps/desktop/src/lib/format.ts, e importar nos dois componentes, removendo as duas cópias locais. Isso padroniza o dock no formato `h:mm:ss`, corrigindo o overflow visual após 60min (única mudança de output: durações ≥1h passam a exibir horas — comportamento desejado). Fix é safe: funções puras, sem dependência de Tauri/WebKitGTK/SO, sem impacto nos padrões intencionais de drag/background. CAVEAT opcional: `relTime` (overlay L78-82) pode ir pro mesmo módulo por organização, mas mantenha-o como função SEPARADA (assinatura em `ms`, semântica distinta) — não tente fundi-lo com `formatDuration`.

<details><summary>Justificativa da verificação</summary>

Confirmei as duas implementações: overlay.tsx L70-76 `formatDuration` trata horas (`h > 0 ? h:mm:ss : mm:ss`) e dock-bar.tsx L16-20 só faz `mm:ss` (`Math.floor(seconds/60)` + segundos). Ambas formatam o MESMO valor: nos dois arquivos `duration` é calculado identicamente como segundos decorridos da sessão (`Math.floor((Date.now() - startedAt) / 1000)` — dock-bar L96-97 e use-live-transcript.tsx L170-171, consumido pela overlay L1040). Usos: dock-bar L222 e overlay L1342. Logo, para uma gravação de 2h05m30s a overlay mostra `2:05:30` e o dock mostra `125:30` para a sessão idêntica — duplicação DRY real + divergência observável (reuniões >60min são plausíveis no produto). Não é crash nem bug de dados, então a severidade low do auditor é honesta. Uma ressalva: `relTime` (overlay L78-82) NÃO é duplicata de `formatDuration` — recebe `ms` (não segundos), só faz `mm:ss`, e serve a outro propósito (timestamp de linha do transcript relativo ao início, L137). A sugestão de movê-lo pro mesmo módulo é co-localização opcional, não dedup; fica fora do core do fix.

</details>

#### 39. 🟡 LOW — Chave de storage "nora.dock.visible" + lógica load/save do dock duplicadas em 3 arquivos

- **Local:** `L17-18 + apps/desktop/src/components/dock-bar.tsx L8 + apps/desktop/src/hooks/use-active-recording.tsx L18`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** A string 'nora.dock.visible' está hardcoded como DOCK_STORAGE_KEY em 3 lugares (overlay.tsx L18, dock-bar.tsx L8, use-active-recording.tsx L18) e o padrão de leitura `v == null ? true : v === '1'` aparece tanto em overlay.tsx loadDockPref (L54-61) quanto inline em use-active-recording.tsx start (L100-107). Qualquer mudança no formato (ex.: migrar pra outra chave/valor) precisa tocar 3 arquivos e é fácil divergir. Vale também pra 'nora.overlay.speaker-overrides', 'nora.overlay.highlights-visible', 'nora.active-recording.meta', 'nora-pending-meetings'.

**Fix recomendado:** Criar apps/desktop/src/lib/dock-prefs.ts (ou um prefs.ts genérico) centralizando SÓ o que é realmente compartilhado entre janelas: `export const DOCK_STORAGE_KEY = "nora.dock.visible";` mais `getDockVisible(): boolean` (com o try/catch + default `true`) e `setDockVisible(v: boolean)`. Importar nos 3 consumidores: overlay.tsx (substituir loadDockPref/saveDockPref), use-active-recording.tsx (substituir a IIFE inline em start por getDockVisible()), e dock-bar.tsx (substituir o setItem(...,"0") por setDockVisible(false)). Isso elimina o risco de chave/default divergente entre os 3 webviews, que é o problema real. Risco do fix: baixo — é extração pura, sem tocar nos padrões intencionais (startDragging, backgrounds sólidos, decorations:false) e sem mudar valores/comportamento; só garantir que o default permaneça `true` igual ao atual. NÃO incluir 'speaker-overrides', 'highlights-visible', 'active-recording.meta' e 'pending-meetings' nessa primeira passada como "correção de duplicação", pois cada uma vive num único arquivo — movê-las é refactor opcional de organização, não fix de DRY com risco de divergência. Se quiser unificar tudo num módulo de storage por consistência, tudo bem, mas trate como tarefa separada de menor prioridade.

<details><summary>Justificativa da verificação</summary>

Confirmado o núcleo do achado. A string "nora.dock.visible" está hardcoded em 3 arquivos: overlay.tsx:18, dock-bar.tsx:8 e use-active-recording.tsx:18. O padrão de leitura com default `v == null ? true : v === "1"` está duplicado em 2 lugares: overlay.tsx loadDockPref (L54-61) e inline numa IIFE em use-active-recording.tsx start (L100-107). dock-bar.tsx apenas ESCREVE "0" (L110), não lê — então a duplicação do padrão de leitura é 2x, não 3x (o achado descreveu isso corretamente). Dano concreto e real: a preferência do dock cruza 3 webviews Tauri independentes; se alguém mudar o default (hoje `true`) ou o nome da chave em uma janela e esquecer outra, o toggle "Mostrar dock" no overlay e a lógica de abrir o dock no start passam a discordar silenciosamente — sem erro, só comportamento inconsistente entre janelas. Isso justifica DRY pra essa chave compartilhada especificamente.

PORÉM o achado exagera no escopo: 'nora.overlay.speaker-overrides' e 'nora.overlay.highlights-visible' vivem SÓ em overlay.tsx (com seus próprios helpers loadOverrides/saveOverrides e loadHighlightsPref/saveHighlightsPref); 'nora.active-recording.meta' só em use-active-recording.tsx; 'nora-pending-meetings' só em pending-meetings.ts. Essas chaves NÃO estão duplicadas entre arquivos, logo não têm o risco de divergência entre janelas — extraí-las é nice-to-have de organização, não correção de duplicação. Por isso rebaixo a severidade de medium pra low: o único caso com risco real de divergência cross-window é a chave/default do dock, e o impacto máximo é um toggle visualmente fora de sincronia, não perda de dados nem bug funcional grave.

</details>

#### 40. 🟡 LOW — Ícones SVG de decisão/próximo passo/observação/tarefa duplicados entre KIND_META e ICONS (mesma janela)

- **Local:** `L296-333 (KIND_META) + apps/desktop/src/components/overlay-notifications.tsx L26-95 (ICONS)`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Os 4 glifos SVG (check polyline '20 6 9 17 4 12' = decisão, arrow '5 12 19 12'+'12 5 19 12 12 19' = próximo passo, info-circle = observação, checklist = tarefa) estão definidos byte-a-byte iguais em KIND_META (overlay.tsx) e em ICONS (overlay-notifications.tsx). Ambos componentes vivem na MESMA janela overlay. Além disso o info-circle e o warning-triangle se repetem ainda no banner de save-error (overlay.tsx L1543-1552) e no CloseConfirmDialog (L1820-1824). Manutenção do ícone exige editar 2-4 cópias.

**Fix recomendado:** Extrair os 4 glifos VERDADEIRAMENTE identicos pra um modulo compartilhado seguindo a convencao existente do dir brand/, ex. apps/desktop/src/components/brand/feed-icons.tsx, exportando DecisionIcon, NextStepIcon, ObservationIcon, TaskIcon (cada um o SVG 12x12 strokeWidth=2 exato de hoje). KIND_META (overlay.tsx) e ICONS (overlay-notifications.tsx) passam a referenciar o mesmo JSX — elimina 4 copias verbatim. CAVEAT 1: o icone do save-error banner (L1543-1552) NAO deve ser unificado com ObservationIcon — e geometria de path diferente (variante de erro), nao a mesma. CAVEAT 2: o triangulo warn (notifications L88-91) e o do CloseConfirmDialog (L1820-1824) compartilham so a path data; se for dedupar, exportar WarnIcon que aceite props size/strokeWidth (default 12/2) pra cobrir os dois call-sites (12/2 e 16/1.7) sem alterar o visual renderizado — isso e secundario e de menor confianca. Manter width/height/viewBox/stroke* exatamente como estao em cada call-site pra nao mudar pixels.

<details><summary>Justificativa da verificação</summary>

Confirmei a duplicacao central: os 4 glifos SVG sao byte-a-byte identicos entre KIND_META (overlay.tsx L296-333) e ICONS (overlay-notifications.tsx L26-95) — mesmas paths E mesmos atributos do wrapper (width=12 height=12 viewBox=0 0 24 24 strokeWidth=2): decision (polyline 20 6 9 17 4 12), nextStep (line 5 12 19 12 + polyline 12 5 19 12 12 19), observation (circle r=10 + 2 lines), task (polyline 9 11 12 14 22 4 + path checklist). Sao 4 copias verbatim na MESMA janela overlay — manutencao do glifo exige editar 2 lugares. Porem o achado superdimensiona os extras: (1) o icone do save-error banner (L1543-1552) NAO e duplicata do observation/info-circle — tem geometria de path DIFERENTE (line de cima pra meio + ponto embaixo = variante de erro) alem de width=15/stroke=var(--danger-ink)/strokeWidth=1.7; e um glifo distinto, nao repeticao. (2) O triangulo do CloseConfirmDialog (L1820-1824) compartilha so o path geometry com a variante warn (mesmo d=M10.29 3.86... + 2 lines), mas difere em width=16/strokeWidth=1.7 vs 12/2 — so a path data repete, nao o elemento inteiro. Sem bug funcional, sem risco cross-platform (JSX puramente presentacional, sem drag/resize/transparencia). Ja existe src/components/brand/ com componentes visuais reusaveis (avatar, logo, bars, orb), entao o local de extracao proposto segue convencao existente.

</details>

#### 41. 🟡 LOW — Padrão load/save localStorage com try/catch repetido para cada preferência

- **Local:** `L17-19, L21-37, L39-68 (loaders) + padrão repetido nos outros stores localStorage`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** loadHighlightsPref/saveHighlightsPref, loadOverrides/saveOverrides, loadDockPref/saveDockPref (overlay.tsx L21-68) são seis funções com o mesmo esqueleto try { localStorage.getItem/setItem } catch { default/ignore }. O mesmo esqueleto reaparece em use-active-recording.tsx (META_STORAGE_KEY, L64-84) e pending-meetings.ts. É boilerplate copiado.

**Fix recomendado:** Criar src/lib/prefs.ts com (1) constantes de chave compartilhadas (PREF_KEYS.dockVisible = "nora.dock.visible", etc.) eliminando os 3 DOCK_STORAGE_KEY duplicados, e (2) um helper generico tipado `createLocalStore<T>(key, defaultValue, { parse, serialize })` retornando { get(): T, set(v: T): void } que encapsula o try/catch. Trocar as 6 funcoes do overlay.tsx por 3 stores (highlights: bool, overrides: SpeakerMap, dock: bool) e reusar o store de dock no use-active-recording.tsx e dock-bar.tsx. Caveats que NAO podem ser perdidos no refator: (a) os defaults divergem por chave — highlights default false (L26), dock default true (L57); o helper precisa receber default por instancia, nao um global. (b) Nao mudar o formato serializado ("1"/"0" para bools, JSON para SpeakerMap/meta) senao quebra prefs ja persistidas de usuarios existentes. (c) Manter o catch silencioso no set (localStorage pode falhar/estar indisponivel) — comportamento intencional, nao adicionar throw. (d) O persistMeta tem semantica extra (removeItem quando m===null, L80) que um get/set puro nao cobre — modelar como store que aceita null e faz removeItem, ou deixar meta fora do refator pra nao arriscar. Isto NAO toca em nenhum padrao cross-platform intencional (startDragging, backgrounds solidos, decorations:false), entao seguro nesse aspecto; o "needs-care" e so por causa de defaults/serializacao/null-semantics.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. Em apps/desktop/src/components/overlay.tsx as seis funcoes loadHighlightsPref/saveHighlightsPref (L21-37), loadOverrides/saveOverrides (L39-53) e loadDockPref/saveDockPref (L54-68) compartilham o mesmo esqueleto `try { localStorage.getItem/setItem } catch { default/ignore }`. O mesmo padrao reaparece inline no use-active-recording.tsx: o initializer de useState (L64-71) e o persistMeta (L76-84). pending-meetings.ts (L23/L41/L46) tambem le/grava localStorage com JSON.parse/stringify, embora ali sem try/catch (caso a parte). Alem disso, a chave literal "nora.dock.visible" (DOCK_STORAGE_KEY) esta hardcoded em 3 arquivos: use-active-recording.tsx:18, dock-bar.tsx:8 e overlay.tsx:18 — confirmando o risco de divergencia que justifica centralizar. Nao existe lib/prefs.ts ainda (so api-client/auth/meetings/pending-meetings/recording-types/secrets/types em src/lib). Severidade low correta: e refator de qualidade, nao bug — o comportamento atual funciona.

</details>

#### 42. 🟡 LOW — Erros de persistencia e de invoke/emit silenciados sem qualquer feedback

- **Local:** `L1116-1119 toggleHighlights / L1176-1183 toggleDock / L31-37 saveHighlightsPref`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** Padrao recorrente de .catch(() => {}) e try/catch vazio engolindo falhas: invoke('toggle_dock') e emit('nora://dock-visibility-changed') (L1179, L1182), emit('nora://rename-speaker') (L1173), invoke('toggle_overlay') (L1276, L1284), e os save*Pref (L33-36, L48-52, L64-67) que ignoram QuotaExceeded/SecurityError. Se toggle_dock falhar, o estado React (dockVisible) e a pref ja foram atualizados mas a janela nao mudou — UI fica dessincronizada do mundo real sem nenhum log. Pelo menos um console.warn ajudaria a depurar no Linux.

**Fix recomendado:** Adicionar log (console.warn) dentro de cada catch silencioso, mantendo o fluxo intacto: L1179 invoke('toggle_dock').catch((e)=>console.warn('[overlay] toggle_dock failed:', e)); L1182 emit dock-visibility-changed; L1173 emit rename-speaker; L1276 e L1284 invoke('toggle_overlay'). Nos save*Pref (L34-36, L50-52, L65-67) trocar o comentario '// ignore' por um console.warn('[overlay] saveXPref failed:', e) capturando o erro no catch. NAO mexer nos catches de leitura (load*) que retornam defaults — sao corretos. Sem necessidade de toast (decisao de UX), so logging para depuracao. Risco safe: mudanca puramente aditiva de logging, nenhum fluxo de controle alterado, nenhum padrao cross-platform intencional (drag/startResizeDragging, backgrounds solidos, decorations:false) eh tocado. Opcional, fora deste achado mas mesma raiz: como o desync de toggle_dock so importa se o invoke falhar, poderia-se mover setDockVisible/saveDockPref para depois do await do invoke — mas isso eh mudanca de comportamento (needs-care) e nao necessaria dado que invoke quase nunca falha; o log basta para low.

<details><summary>Justificativa da verificação</summary>

Codigo confirmado nos locais exatos. toggleDock (L1176-1183) atualiza setDockVisible(next) e saveDockPref(next) ANTES de invoke('toggle_dock',{show:next}).catch(()=>{}) (L1179) e emit('nora://dock-visibility-changed').catch(()=>{}) (L1182). Se o invoke falhar, o estado React e a pref ja foram mudados mas a janela OS nao — desync real e silenciosa. Mesmo padrao em emit('nora://rename-speaker') (L1173), invoke('toggle_overlay') (L1276 e L1284) e nos save*Pref (saveHighlightsPref L34-36, saveOverrides L50-52, saveDockPref L65-67) que engolem QuotaExceeded/SecurityError. O argumento de consistencia eh valido: o MESMO arquivo ja loga em L621 (.catch((e)=>console.error('[overlay] list_audio_devices:', e))) e L654, alem de handleStop/handleCancel (L1258/L1267), entao os silenciosos sao a excecao inconsistente. Severidade low estah honesta: esses comandos Tauri (show/hide) e emits praticamente nunca falham na pratica; o dano concreto eh so dificuldade de diagnostico no Linux/WebKitGTK, nao bug user-facing em operacao normal. Nao eh nitpick puro porque ha desync de estado plausivel sem rastro nenhum. Ressalva ao achado: os catches de LEITURA (loadHighlightsPref L27, loadOverrides L43, loadDockPref L58) retornam defaults legitimos e NAO devem ser tocados — o achado corretamente nao os incluiu no titulo.

</details>

#### 43. 🟡 LOW — Botão 'Aplicar e reiniciar' some o estado applying via setTimeout fixo de 600ms em vez de aguardar o resultado real

- **Local:** `L644-659 (AudioConfigSection.apply)`
- **Categoria:** error-handling · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** apply() emite 'nora://restart-recording' e no finally faz `setTimeout(() => setApplying(false), 600)`. É um palpite de quanto tempo o restart leva (stop + 120ms + start no listener de use-active-recording.tsx L249-255). Se o restart demorar mais que 600ms (device ocupado, sidecar lento), o spinner some antes do áudio voltar, dando falsa sensação de pronto; se o componente desmontar antes dos 600ms, o setState ocorre fora da árvore (warning). Não há feedback de erro se o restart falhar (o erro é só console.error em use-active-recording.tsx L257).

**Fix recomendado:** Resolver `applying` por evento real em vez de timer cego, MAS mantendo um fallback (sem ele ha risco de spinner travado pra sempre): clear `applying` num useEffect que observa a mudanca de currentMic/currentSysAudio para os valores requisitados (ja chegam via 'recording-status' em useLiveTranscript), com timeout de seguranca (~3-5s) como teto — porque se o device requisitado for igual ao atual, nenhum evento com valor diferente chega e o spinner penduraria. Guardar o id do setTimeout num ref e limpa-lo no unmount/quando resolver. Cuidado cross-window: o restart roda na janela main (use-active-recording.tsx) e o apply na overlay — nao da pra await direto; depender do 'recording-status' que ja cruza as janelas e o caminho certo. Opcionalmente propagar falha do restart (use-active-recording.tsx L256-257) via um evento dedicado (ex 'nora://restart-failed') pra overlay exibir um aviso, em vez de so console.error. Manter as decisoes intencionais (eventos Tauri, backgrounds solidos) intactas. Severidade low justifica fix de baixa prioridade — o comportamento atual nao corrompe estado, so e impreciso.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. overlay.tsx L644-659 (AudioConfigSection.apply) faz `setTimeout(() => setApplying(false), 600)` no finally — um palpite fixo, desacoplado da conclusao real do restart. O restart em use-active-recording.tsx L248-255 faz stop_recording + 120ms + startRecording, e em caso de falha so loga (L256-257), sem feedback ao usuario. Os danos reais sao modestos: (a) se device ocupado/sidecar lento o restart passa de 600ms e o spinner some antes do audio voltar = falsa sensacao de pronto (UX impreciso, real); (b) se o restart falhar, o usuario nao ve nada (erro engolido em outra janela). NAO confirmo o sub-claim de 'setState fora da arvore (warning)' como dano relevante: o id do timeout nao e limpo no unmount, mas em React 18 setState em componente desmontado e no-op silencioso (o warning foi removido) — fica so um timer pendente por ate 600ms, irrelevante. Tambem nao e um hard bug: as props currentMic/currentSysAudio vem de useLiveTranscript() que atualiza via evento 'recording-status', entao o painel re-sincroniza e `dirty` recomputa de qualquer forma; o estado eventualmente converge. Por isso: low, ajustado.

</details>

#### 44. 🟡 LOW — Dois useEffect separados com a MESMA condicao de guarda (isRecording && lines.length===0)

- **Local:** `L1142-1145 e L1122-1127 (dois useEffect com mesma guarda) e L143-150 trigger logic`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Ha dois useEffect distintos disparando na mesma transicao: L1122-1127 (reset de overrides) e L1143-1145 (clear de saveError), ambos com deps [isRecording, lines.length] e a mesma guarda `if (isRecording && lines.length === 0)`. Alem de redundante, depender de `lines.length === 0` como proxy de 'nova sessao' e fragil: o reset roda em QUALQUER render onde isRecording e true e lines esta vazio (ex: durante a janela entre recording-status=true e a chegada da primeira linha pode rodar mais de uma vez se lines voltar a 0). Funciona hoje mas e logica acoplada a um sinal indireto.

**Fix recomendado:** Consolidar os dois efeitos em um só, já que guarda e deps são idênticas e os corpos idempotentes (zero risco de regressão):

```tsx
// Reset overrides + clear save-error when a fresh recording session begins
useEffect(() => {
  if (isRecording && lines.length === 0) {
    setOverrides({});
    saveOverrides({});
    setSaveError(null);
  }
}, [isRecording, lines.length]);
```

Caveat: NÃO trocar o gatilho para `startedAt` sem cuidado — `startedAt` não reseta a um sentinela entre sessões (use-live-transcript.tsx:119/147), então "nova sessão" via `startedAt` exigiria um `useRef` rastreando o valor anterior; ganho marginal sobre a guarda atual (que já funciona porque o reset de `lines` é atômico com `isRecording:true`). A consolidação dos dois efeitos já entrega o valor real. Baixa prioridade.

<details><summary>Justificativa da verificação</summary>

Confirmei os dois useEffect duplicados em apps/desktop/src/components/overlay.tsx:1122-1127 (reset de overrides) e 1143-1145 (clear de saveError) — ambos com a MESMA guarda `if (isRecording && lines.length === 0)` e as MESMAS deps `[isRecording, lines.length]`. A duplicação é real e poderiam ser um único efeito. PORÉM a categorização "logic-error" e o risco de "rodar mais de uma vez" estão exagerados: (1) a citação "L143-150 trigger logic" está ERRADA — L143-150 é renderização de Avatar/ChatBubble, sem relação. (2) Em use-live-transcript.tsx:113-124, ao iniciar gravação o estado reseta `lines: []` e `isRecording: true` atomicamente, e dentro de uma sessão `lines` só cresce (nunca volta a 0 até o próximo start atômico). Então a guarda dispara essencialmente uma vez por sessão. (3) Mesmo que disparasse repetidamente, os corpos são IDEMPOTENTES (`setOverrides({})`/`saveOverrides({})` e `setSaveError(null)` produzem o mesmo resultado; React faz bail-out em estado idêntico) — logo não há bug observável, race, nem re-render extra. É portanto débito de DRY/limpeza, não erro de lógica. Sobre o "proxy frágil": `startedAt` NÃO é trigger mais limpo de graça — ele recebe um novo `Date.now()` a cada start mas permanece setado após o stop, então derivar "nova sessão" dele exigiria um ref extra rastreando o valor anterior. A guarda atual com `lines.length===0`, dado o reset atômico, é adequada.

</details>

#### 45. 🟡 LOW — Arquivo monolitico de ~1925 linhas com 8+ componentes e estilos inline massivos

- **Local:** `L1034-1925 (OverlayPage e arquivo inteiro)`
- **Categoria:** professionalism · **Risco do fix:** risky · **Veredito:** adjusted (severidade ajustada)

**Problema:** overlay.tsx concentra OverlayPage, ChatBubble, PartialBubble, DetectionRow, HighlightsColumn, AudioConfigSection, ConfigDrawer e CloseConfirmDialog num unico arquivo de quase 2k linhas, quase tudo com style inline verboso (objetos de 15-20 props repetidos). OverlayPage sozinho tem ~15 hooks/handlers. Isso prejudica navegabilidade, reuso e review. Os padroes de botao (hover via onMouseEnter/onMouseLeave manipulando style — repetido ~7x: config, minimizar, fechar, collapse, etc.) sao candidatos obvios a um <IconButton> compartilhado.

**Fix recomendado:** Não acionar agora — é cleanup opcional, não débito que justifique tocar um arquivo de overlay sensível a cross-platform. Se um dia for feito: (1) extrair só os 3 botões de ícone do header (config L1433, minimizar L1465, fechar L1491), que são idênticos (24x24, hover rgba(0,0,0,0.05)→var(--ink)), num <IconButton> com props icon/active/onClick/title; NÃO tente unificar collapse (L515, var(--chip)/22px), mostrar-detecções (L1707, var(--sidebar)/22x56/absolute) nem os botões de texto Descartar/Parar — divergem demais. (2) Mover ChatBubble/PartialBubble/DetectionRow/HighlightsColumn/ConfigDrawer/CloseConfirmDialog pra overlay/*.tsx só se trouxer reuso real, mantendo os styles inline (decisão intencional WebKitGTK). Refactor obrigatoriamente sem mudança de comportamento, validando drag/scroll/minimize na overlay no Linux antes de mergear.

<details><summary>Justificativa da verificação</summary>

Os fatos batem: overlay.tsx tem mesmo 1925 linhas e concentra 8 componentes (ChatBubble L129, PartialBubble L228, DetectionRow L367, HighlightsColumn L450, AudioConfigSection L603, ConfigDrawer L827, OverlayPage L1034, CloseConfirmDialog L1760) mais o map KIND_META L296, quase tudo com style inline verboso. O padrao de hover via onMouseEnter/onMouseLeave mutando e.currentTarget.style aparece 7x (L528, L1366, L1396, L1447, L1478, L1504, L1724), confirmando a duplicacao apontada.

PORÉM a severidade/enquadramento sao inflados e o achado não é acionável agora. É puramente professionalism/manutenibilidade: nenhum bug, race, type-safety ou seguranca — o comportamento está correto. O brief manda rejeitar nitpick subjetivo de estilo e reservar atenção pra risco real. Além disso, o fix proposto NÃO é tão clean quanto o achado sugere: os botões de ícone divergem entre si (header config/min/fechar usam hover rgba(0,0,0,0.05) e 24x24 — L1433/1465/1491; o collapse usa var(--chip) e 22x22 — L515; o mostrar-detecções usa var(--sidebar), 22x56, absolute + border — L1707; e Descartar/Parar L1349/1379 são botões de texto com hover totalmente diferente). Um <IconButton> só cobriria limpo os 3 do header; forçar o resto exigiria props pra cada valor variável, adicionando complexidade. Refatorar/quebrar arquivo de overlay que funciona sob restrições WebKitGTK (styles inline e hovers manuais existem justamente por isso) é churn de regressão real por ganho funcional zero. Por isso: achado real, mas ajustado pra low e tratado como cleanup oportunista, não item a executar.

</details>

#### 46. 🟡 LOW — console.log de debug deixados no caminho quente de analise (cross-ref do hook usado pelo overlay)

- **Local:** `L147-149, L156-160 (live-trigger console.log) e use-live-transcript referencias`
- **Categoria:** professionalism · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Embora fora dos 3 arquivos da fatia, o overlay depende de useLiveHighlights/useLiveAnalysisTrigger; e relevante notar que o trigger loga em console.log a cada skip/fire/chunk-curto (use-live-highlights.tsx L147, L156, L160). Dentro da fatia, o padrao de logging e console.error em catches (ok), mas o ecossistema do overlay tem console.log de debug nao-gated que vaza no console de producao da janela. Mantenho como nota de profissionalismo do conjunto; os arquivos da fatia em si nao tem console.log esquecido.

**Fix recomendado:** Localizacao correta: apps/desktop/src/hooks/use-live-highlights.tsx L147, L156, L160 (nao overlay.tsx). Gatear os 3 console.log atras de import.meta.env.DEV ou remove-los. Fix trivial e seguro — sao logs puros sem efeito colateral; nada de comportamento muda. Caveats: (a) o achado esta declaradamente fora do escopo da fatia (apps/desktop/src/components/overlay.tsx, dock-bar.tsx) e a propria overlay nao tem console.log esquecido, entao trata-lo como achado da fatia overlay e impreciso; (b) se for endereçar, vale fazer de forma consistente com os outros ~6 console.log de debug do desktop/src (use-recording.ts, api-client.ts, auth.ts) num unico passe, em vez de so esses 3; (c) sem vazamento de PII — os logs nao imprimem texto de transcricao, so metricas numericas. Severidade low e correta.

<details><summary>Justificativa da verificação</summary>

Os console.log existem de fato, mas NAO no arquivo/linhas citados. O titulo aponta overlay.tsx L147-149 e L156-160 — essas linhas sao JSX do ChatBubble (avatar, label do falante, timestamp), sem nenhum console.log. Os logs reais estao em apps/desktop/src/hooks/use-live-highlights.tsx L147/L156/L160 (skipped / chunk too short / firing), no triggerAnalysis. Eles disparam no caminho quente durante a gravacao (a cada avaliacao de chunk). Pontos que rebaixam o achado: (1) o proprio auditor admite que esta fora dos 3 arquivos da fatia e que os arquivos da fatia nao tem console.log esquecido — ou seja, ele mesmo o classifica como nota tangencial, nao achado acionavel da fatia; (2) a localizacao no titulo esta misatribuida ao overlay.tsx; (3) nao ha vazamento de PII/secret — os logs imprimem so contagens (newLines, newChars, timeSinceLastAnalysis, chunkLen, length), nunca o conteudo do transcript; (4) e um padrao difuso do codebase, nao isolado: existem 9 console.log de debug no desktop/src (auth.ts L119, api-client.ts L50/L60, use-recording.ts L68/L105/L108/L315, alem desses 3). Real e de baixo valor profissional, mas mal-rotulado e nao especifico da fatia.

</details>

#### 47. 🟡 LOW — Uso de JSX.Element global (namespace legado) em vez de React.JSX.Element/ReactElement

- **Local:** `L296 (KIND_META) e overlay-notifications.tsx L22 (VariantStyle.icon)`
- **Categoria:** type-safety · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Ambos arquivos tipam icons como `JSX.Element` usando o namespace global JSX. Com React 18 + jsx: react-jsx (tsconfig confirmado) e tipos modernos do @types/react, o namespace global JSX foi descontinuado em favor de React.JSX. Em uma futura bump de @types/react (v19) isso passa a exigir React.JSX.Element ou ReactElement e quebra o typecheck. Nao e bug em runtime, mas e debito de tipo.

**Fix recomendado:** Trocar `JSX.Element` por `React.ReactElement` (mais preciso que `React.ReactNode`, ja que cada campo guarda UM elemento SVG concreto, nao conteudo arbitrario tipo children) — ou `React.JSX.Element` se quiser manter semantica identica. Garantir que `React` esteja importado nos arquivos que usarem a forma `React.X`. CRITICO: aplicar nos TRES arquivos, nao dois — o auditor esqueceu sidebar.tsx:9. Locais: overlay.tsx:296, overlay-notifications.tsx:22, sidebar.tsx:9. Caveat: nao usar `React.ReactNode` como o auditor sugeriu primariamente — alarga demais o tipo (aceitaria string/number/null), divergindo da intencao de "um icone SVG". Como e debito puramente latente (sem impacto ate um bump v19 inexistente), tratar como cleanup oportunista, nao urgente.

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo exato: `JSX.Element` (namespace global) e usado em overlay.tsx:296 (KIND_META) e overlay-notifications.tsx:22 (VariantStyle.icon). O auditor PERDEU uma terceira ocorrencia: sidebar.tsx:9 (`icon: JSX.Element`). tsconfig confirma `jsx: "react-jsx"` + `strict: true`. Porem react e @types/react estao PINADOS em ^18.3.x — o namespace global JSX existe e e valido nessa versao, entao NAO ha bug nem em runtime nem no typecheck atual; o proprio achado admite isso. O dano e (a) debito de tipo latente que so se materializa num bump major pra @types/react v19 (que removeu o namespace global JSX em favor de React.JSX) — bump que nao esta agendado, e (b) inconsistencia real: o resto do codebase ja usa React.ReactNode/ReactNode (App.tsx, settings.tsx, titlebar.tsx, hooks etc.). E o angulo de consistencia que o tira de "nitpick puro" pra "low real e acionavel". Severity low esta honesta. Risco do fix e nulo: trocar a anotacao de tipo de um campo nao muda comportamento em runtime nem toca nos padroes intencionais cross-platform (drag/resize/backgrounds solidos).

</details>

### `apps/desktop/src/components/brand/nora-bars.tsx`  ·  2 achado(s)

#### 48. 🟠 MEDIUM — NoraLogo e NoraBars duplicam constantes + maquina de animacao inteira

- **Local:** `nora-bars.tsx L4-6,L36-116 vs nora-logo.tsx L3-5,L20-91`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** HEIGHTS/BAR_W/BAR_GAP estao declarados identicos nos dois arquivos, e toda a maquina de fases (useState 'init'|'animating'|'done', os setTimeout 60ms/1100ms, o cleanup, o calculo de totalW e o render das barras com transition cubic-bezier) esta copiada quase byte-a-byte. O proprio comentario de nora-bars.tsx L3 diz 'single source of truth pro soundwave da marca' e L25 diz que NoraLogo 'compartilha as mesmas heights' — mas nao compartilha: cada um redeclara. Mudar a estetica do soundwave exige editar dois lugares e eles vao divergir.

**Fix recomendado:** Extrair a fonte unica. O fix do auditor ('NoraLogo renderiza <NoraBars> + span de texto') esta direcionalmente certo mas subestima 2 acoplamentos que precisam ser preservados: (1) NoraBars suporta active/color/activeColor (pulse dotPulse) que NoraLogo nao usa — prop-forwarding deve ser explicito, e size default difere (NoraBars=16, NoraLogo=20); (2) o reveal do texto em nora-logo L93-109 depende de phase==='done' com timing casado ao fim da animacao das barras (delay 380ms), e a phase machine e privada do NoraBars. Por isso NoraLogo nao consegue simplesmente 'so adicionar texto' sem saber quando as barras terminaram. Abordagem segura: (a) extrair HEIGHTS/BAR_W/BAR_GAP + a phase machine para um modulo compartilhado, ex. `useBarPhase(animate, animKey)` retornando phase, e um helper `barTotalW()`/constantes; ambos componentes consomem. Ou (b) NoraLogo renderiza <NoraBars animate animKey> e recebe o fim via callback opcional `onPhaseDone` exposto por NoraBars, usando-o para revelar o texto. A opcao (a) (hook + constantes compartilhadas) e a mais limpa e mantem o reveal de texto intacto sem duplicar o timer. Verificar nada quebra nos 5 call-sites: login.tsx:297, sidebar.tsx:93 (NoraLogo animate), overlay.tsx:550/1317 e dock-bar.tsx:199 (NoraBars com size/active/animate). Rodar `npm run typecheck` no apps/desktop apos.

<details><summary>Justificativa da verificação</summary>

Confirmado. apps/desktop/src/components/brand/nora-bars.tsx L4-6 e apps/desktop/src/components/brand/nora-logo.tsx L3-5 declaram HEIGHTS/BAR_W/BAR_GAP byte-a-byte iguais. A maquina de fases (useState 'init'|'animating'|'done' em nora-bars L36-38 / nora-logo L20-22, setTimeout 60ms+1100ms com cleanup em L40-52 / L24-36, totalW em L54 / L38) e o render das barras (HEIGHTS.map com transition `cubic-bezier(.22,.8,.36,1)` stagger `i*70ms`, nora-bars L67-113 / nora-logo L46-91) sao copia quase exata. O comentario em nora-bars L3 ('single source of truth') e L25-26 ('internamente compartilha as mesmas heights') e factualmente falso — nao ha compartilhamento, cada arquivo redeclara tudo. Dano concreto: mudar a estetica do soundwave (heights, largura, timing, easing) exige editar dois arquivos e eles vao divergir silenciosamente. Nao e estilo subjetivo: e duplicacao de logica + comentario mentiroso. Severidade medium (debito real, sem bug em runtime). NAO toca padroes intencionais cross-platform (sem startDragging, sem data-tauri-drag-region, sem transparencia).

</details>

#### 49. 🟠 MEDIUM — NoraBars e NoraLogo duplicam HEIGHTS/BAR_W/BAR_GAP e TODA a máquina de animação por fase

- **Local:** `L3-5 + L40-113 vs apps/desktop/src/components/brand/nora-logo.tsx L3-5 + L24-91`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** O comentário em nora-bars.tsx L3 afirma 'Same heights as NoraLogo — single source of truth pro soundwave', mas na prática HEIGHTS=[0.42,0.78,1.0,0.66,0.52], BAR_W=3, BAR_GAP=2.5 estão copiados nos dois arquivos, junto com a state machine idêntica (phase init/animating/done, os mesmos setTimeout 60ms/1100ms, a mesma transition `transform 480ms cubic-bezier(.22,.8,.36,1) ${i*70}ms`). Se as alturas/timing mudarem, é trivial atualizar um e esquecer o outro — quebrando a 'single source of truth' prometida.

**Fix recomendado:** Extrair as constantes compartilhadas (HEIGHTS, BAR_W, BAR_GAP) para um modulo base, ou simplesmente importa-las de nora-bars.tsx no nora-logo.tsx (export const). Melhor ainda: fazer NoraLogo renderizar NoraBars internamente + o texto (NoraLogo = NoraBars + texto), eliminando a state machine duplicada. CAVEATS ao compor: (1) preservar defaults divergentes — NoraBars default size=16, NoraLogo default size=20; nao herdar o 16 sem querer. (2) NoraLogo usa cor fixa var(--ink) sempre; NoraBars suporta color/active/activeColor/dotPulse — ao reusar, passar apenas size/animate/animKey e deixar cor no default ink (nao acionar active no logo). (3) O texto do NoraLogo depende de phase==="done" para a transicao de opacidade/translateX (L101-104); se a state machine for movida para NoraBars, a fase precisa ser exposta (ex: callback onPhase ou prop) ou o NoraLogo manter seu proprio timer espelhado — caso contrario a animacao de entrada do texto quebra. Dado esse acoplamento da fase com o texto, a opcao de menor risco e extrair so as constantes (e opcionalmente um hook useBarsPhase compartilhado) em vez de forcar NoraLogo a consumir NoraBars como caixa-preta. Nenhum padrao intencional cross-platform e afetado (nao mexe em drag/transparencia/decorations).

<details><summary>Justificativa da verificação</summary>

Duplicacao real e literal confirmada nos dois arquivos. nora-bars.tsx L4-6 e nora-logo.tsx L3-5 repetem identicamente HEIGHTS=[0.42,0.78,1.0,0.66,0.52], BAR_W=3, BAR_GAP=2.5. A maquina de animacao tambem e copiada: mesmo useState<"init"|"animating"|"done"> (bars L36-38 / logo L20-22), mesmo useEffect com setTimeout 60ms + 1100ms e deps [animate, animKey] (bars L40-52 / logo L24-36), mesmo calculo de totalW (bars L54 / logo L38) e a mesma string de transition `transform 480ms cubic-bezier(.22,.8,.36,1) ${i*70}ms, opacity 380ms ease ${i*70}ms` (bars L94 / logo L80). O dano e concreto e agravado pelo proprio codigo: o comentario em nora-bars.tsx L3 ("Same heights as NoraLogo — single source of truth pro soundwave") e a docstring L25-26 ("internamente compartilha as mesmas heights") PROMETEM fonte unica, mas o codigo copia. Como NoraBars e usado em overlay/dock (overlay.tsx L550/L1317, dock-bar.tsx L199) e NoraLogo em login/sidebar (login.tsx L297, sidebar.tsx L93), mexer no timing/alturas de um lado dessincroniza silenciosamente a marca entre janelas. Nao e bug funcional (por isso medium, nao high) — e drift visual/manutencao. As diferencas entre os arquivos (NoraBars tem active/color/activeColor/dotPulse; NoraLogo tem o texto) sao exatamente o delta "NoraLogo = NoraBars + texto" que o fix descreve, nao algo que invalide o achado.

</details>

### `apps/desktop/src/components/brand/shader-orb.tsx`  ·  2 achado(s)

#### 50. 🟠 MEDIUM — ShaderOrb vaza recursos WebGL e roda RAF infinito sem pausar fora de foco

- **Local:** `L99-163 (useEffect / cleanup L162)`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O cleanup do useEffect (L162) so chama cancelAnimationFrame, mas nunca libera program/shaders/buffer nem chama WEBGL_lose_context. A cada mudanca de prop (size/speed/intensity/hueShift dispara o effect — L163) ou remount, um novo contexto WebGL e alocado e o antigo fica preso; navegadores limitam contextos WebGL (~16) e ao estourar os orbs param de renderizar. Alem disso o loop roda requestAnimationFrame indefinidamente mesmo com a janela em background ou o orb fora de tela, e ha multiplos ShaderOrb simultaneos (meetings, chat, modal, overlay, meeting-detail) cada um com fbm de 5 oitavas + preserveDrawingBuffer:true (L109, que impede otimizacoes e aumenta uso de memoria) — dreno de GPU/bateria.

**Fix recomendado:** No retorno do useEffect, capturar e liberar todos os recursos antes/depois do cancelAnimationFrame: `return () => { cancelAnimationFrame(raf); gl.deleteBuffer(buf); gl.deleteProgram(prog); gl.deleteShader(vs); gl.deleteShader(fs); gl.getExtension("WEBGL_lose_context")?.loseContext(); };` — para isso, guardar refs aos dois shaders compilados (hoje `compile()` retorna o shader mas o resultado nao e armazenado em variavel; armazenar `const vs = compile(...)` / `const fs = compile(...)` e usar em attachShader). Essa parte e a load-bearing e e segura: so executa no unmount/re-run, e o codigo ja recria tudo do zero a cada effect. Secundario (opcional, baixo risco): (1) pausar o loop quando `document.hidden` via listener `visibilitychange` (e cancelar/registrar o listener no mesmo cleanup) para poupar bateria — cuidado para nao deixar o orb congelado se a janela Tauri reportar hidden incorretamente no WebKitGTK, entao testar no Linux antes de mergear; (2) remover `preserveDrawingBuffer: true` em L109 — seguro pois nenhum consumidor le o framebuffer. Caveat: a unica mudanca obrigatoria e o cleanup de recursos WebGL; o resto e otimizacao e deve ser validado no WebKitGTK por causa dos padroes cross-platform intencionais do projeto.

<details><summary>Justificativa da verificação</summary>

Confirmado no arquivo. O cleanup do useEffect em shader-orb.tsx L162 e literalmente `() => cancelAnimationFrame(raf)` — nao libera program, shaders nem buffer, e nunca chama WEBGL_lose_context. As deps em L163 sao [size, speed, intensity, hueShift], entao toda mudanca de prop reroda o effect e aloca um NOVO contexto WebGL via canvas.getContext (L106) sem soltar o antigo. Verifiquei que isso de fato acontece em runtime: meeting-detail.tsx L412 passa speed={isProcessing?1.6:1} e intensity={isReady?...:isProcessing?...}; overlay.tsx L1627 passa speed={isRecording?1.4:0.6} e intensity={isRecording?...}. Ou seja, cada transicao do ciclo de gravacao (recording start/stop, processing->ready) vaza um contexto. Como WebGL limita ~16 contextos por pagina (WebKitGTK inclusive) e ha multiplos ShaderOrb na app (meetings, chat, modal, overlay, meeting-detail), o dreno acumula e ao estourar o limite os orbs param de renderizar — dano concreto, nao teorico. As alegacoes secundarias sao reais mas menores: o RAF nunca checa document.hidden/visibilidade (desperdicio de GPU/bateria, nao bug de correcao — e browsers ja throttlam RAF em janela oculta); preserveDrawingBuffer:true em L109 e desnecessario (confirmei: nenhum readPixels/toDataURL nesses canvases) e inibe otimizacoes. Severidade medium e honesta: nao quebra na primeira render, degrada com o uso.

</details>

#### 51. 🟡 LOW — ShaderOrb não libera recursos WebGL (shaders/program/buffer) no cleanup; cancela só o rAF

- **Local:** `L99-163 (useEffect WebGL)`
- **Categoria:** tech-debt · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** O cleanup do useEffect só faz cancelAnimationFrame. Os shaders compilados, o program, o buffer e o próprio contexto WebGL não são deletados. Como o efeito re-roda a cada mudança de size/speed/intensity/hueShift (deps L163), cada re-render relevante cria novos recursos GL no mesmo canvas sem liberar os antigos. ShaderOrb é montado em vários lugares (overlay bolhas 'minha fala' por grupo, status dots da lista, chat, modal) e re-renderizado com props animadas — pode acumular contextos GL e bater no limite de WebGL contexts do WebKitGTK ('too many active WebGL contexts'), derrubando orbs.

**Fix recomendado:** Adicionar ao cleanup do useEffect (L162), antes/junto do cancelAnimationFrame: capturar referencias aos dois shaders compilados, ao prog e ao buf, e no retorno chamar gl.deleteProgram(prog), gl.deleteShader(vs), gl.deleteShader(fs), gl.deleteBuffer(buf). Isso eh seguro. NAO incluir gl.getExtension('WEBGL_lose_context')?.loseContext() no cleanup de re-render: forcar context-loss a cada troca de speed/intensity recria o contexto e no WebKitGTK pode deixar o canvas em branco / disparar a mesma instabilidade de transparencia/context-lost que o time evita de proposito (backgrounds solidos) — so faria sentido no unmount real, mas mesmo assim eh desnecessario. Fix preferivel (elimina o leak na raiz): separar criacao do contexto/program/buffer de um effect com deps [] (ou [size], ja que size redimensiona o backing store e e estatico em todos os call sites) e ler speed/intensity/hueShift via refs dentro do frame(), deixando o loop sem deps de props — assim o effect nao re-roda quando isRecording/isProcessing alternam.

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo: shader-orb.tsx L162 retorna apenas `() => cancelAnimationFrame(raf)`. O effect cria recursos GL (L114 createShader x2, L123 createProgram, L129 createBuffer) e nenhum eh deletado no cleanup. As deps L163 incluem speed/intensity/hueShift, e ha call sites que passam essas props a partir de estado que alterna durante a sessao: overlay.tsx L1629-1630 (speed/intensity ligados a isRecording), meeting-detail.tsx L414-415 (isProcessing/isReady), chat.tsx (orb grande). Cada toggle re-roda o effect e cria novos shaders/program/buffer no mesmo canvas sem liberar os antigos => leak real de objetos GL. POReM o diagnostico do auditor exagera o dano: canvas.getContext('webgl') retorna o MESMO contexto em chamadas repetidas para o mesmo <canvas>, entao um unico ShaderOrb montado NAO acumula contextos WebGL. O erro 'too many active WebGL contexts' vem de muitas INSTANCIAS montadas simultaneamente (preocupacao distinta), nao do cleanup faltando. O leak por re-run eh pequeno e limitado (poucos objetos por troca de prop), por isso severidade low, nao um bug que 'derruba orbs'.

</details>

### `apps/desktop/src/components/dock-bar.tsx`  ·  5 achado(s)

#### 52. 🟠 MEDIUM — Chave de storage e nomes de evento Tauri hardcoded em multiplos arquivos

- **Local:** `L8 (DOCK_STORAGE_KEY) e L117 ("nora://dock-visibility-changed")`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** DOCK_STORAGE_KEY = "nora.dock.visible" esta redeclarado literalmente em 3 arquivos: dock-bar.tsx L8, overlay.tsx L18 e hooks/use-active-recording.tsx L18. O evento "nora://dock-visibility-changed" e emitido em dock-bar.tsx L117 e overlay.tsx L1182 e escutado em overlay.tsx L1149 — string crua repetida. Um typo em qualquer copia quebra silenciosamente a sincronia entre janelas (o dock esconde mas o toggle do overlay nao atualiza) sem erro de compilacao.

**Fix recomendado:** Criar apps/desktop/src/lib/desktop-events.ts exportando: (1) STORAGE_KEYS.DOCK_VISIBLE = "nora.dock.visible"; (2) EVENTS.DOCK_VISIBILITY_CHANGED = "nora://dock-visibility-changed"; (3) um tipo DockVisibilityPayload = { visible: boolean } pra usar em emit/listen. Importar nos 3 call sites do DOCK_STORAGE_KEY e nos 3 do evento. CAVEAT critico: preservar os VALORES das strings byte-a-byte — "nora.dock.visible" ja esta persistido em localStorage de usuarios e "nora://dock-visibility-changed" e o nome do canal usado por janelas em runtime; alterar o literal orfanaria prefs salvas e quebraria a comunicacao entre janelas abertas. Escopo opcionalmente maior (e recomendado pelo mesmo motivo de fragilidade): os outros eventos nora:// (save-result, stop-and-save, cancel-recording, retry-save, rename-speaker, restart-recording) sofrem do mesmo problema e poderiam ser centralizados no mesmo modulo EVENTS. Refactor puro, sem tocar nos padroes intencionais cross-platform (startDragging, backgrounds solidos, decorations:false).

<details><summary>Justificativa da verificação</summary>

Verifiquei os 3 arquivos. Fatos todos confirmados: DOCK_STORAGE_KEY = "nora.dock.visible" esta redeclarado literalmente em dock-bar.tsx:8, overlay.tsx:18 e use-active-recording.tsx:18 (string identica). O evento Tauri "nora://dock-visibility-changed" aparece cru em 3 call sites: emit em dock-bar.tsx:117 e overlay.tsx:1182, e listen em overlay.tsx:1149. O payload { visible: boolean } tambem e tipado inline e duplicado. Dano concreto e real: nomes de canal de evento e chaves de localStorage no Tauri sao strings em runtime sem checagem de tipo — um typo em qualquer copia (ex: "nora.dock.visble") quebra silenciosamente a sincronia/persistencia entre webviews (dock esconde mas o toggle do overlay nao atualiza, e o toast "Dock escondido" nao dispara) SEM erro de compilacao. Nao e nitpick de estilo: e fragilidade de correcao com modo de falha silencioso. Nao existe um lib/desktop-events.ts hoje, mas a pasta apps/desktop/src/lib/ ja existe com outros modulos, entao o fix encaixa no padrao do repo.

</details>

#### 53. 🟠 MEDIUM — formatDuration do dock estoura em gravacoes > 1h (e diverge do overlay)

- **Local:** `L16-20 (dock) vs apps/desktop/src/components/overlay.tsx L70-76`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** Existem DUAS implementacoes divergentes de formatDuration. A do dock-bar (L16-20) so calcula minutos/segundos: para 3700s ela renderiza '61:40' em vez de '1:01:40'. A do overlay (overlay.tsx L70-76) ja trata horas corretamente. O dock mostra o MESMO timer de gravacao do overlay, entao numa reuniao longa o dock fica mostrando '83:20' enquanto o overlay mostra '1:23:20' — inconsistencia visivel e contagem de minutos sem teto. Os 'magic' 60/3600 tambem ficam espalhados.

**Fix recomendado:** Extrair uma única formatDuration (a versão com horas, da overlay) para src/lib/format.ts (o diretório src/lib/ já existe e segue esse padrão de utils) e importar em dock-bar.tsx e overlay.tsx, removendo as duas cópias locais. Implementação: `export function formatDuration(seconds: number): string { const h = Math.floor(seconds / 3600); const m = Math.floor((seconds % 3600) / 60); const s = seconds % 60; const pad = (n: number) => String(n).padStart(2, "0"); return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`; }`. Fix é safe: função pura de formatação, sem qualquer interação com WebKitGTK/drag/transparência. Caveat: tecnicamente isto MUDA o formato exibido no dock para gravações > 1h (passa a incluir o segmento de horas), mas essa mudança é justamente a correção desejada e alinha dock com overlay. Opcional (fora do escopo do achado): considerar unificar relTime (overlay L78-82) na mesma lógica, mas relTime usa ms e também trunca minutos — não obrigatório para este fix. Após editar rodar `npm run typecheck` no apps/desktop.

<details><summary>Justificativa da verificação</summary>

Confirmei o código exato. dock-bar.tsx L16-20 implementa formatDuration apenas com minutos/segundos: `const m = Math.floor(seconds / 60); const s = seconds % 60; return mm:ss`. Para 3700s isso produz "61:40" (minutos sem teto), não "1:01:40". A overlay.tsx L70-76 trata horas corretamente (h = floor(seconds/3600), m = floor((seconds%3600)/60)). Ambas as janelas exibem a MESMA gravação: cada uma deriva `duration` de elapsed-seconds desde `startedAt` definido no evento Tauri "recording-status" (dock L96-97: `Math.floor((Date.now() - startedAt)/1000)`; overlay idem em L1040), e ambas renderizam `formatDuration(duration)` no badge de gravação (dock L222, overlay L1342). Logo, numa reunião > 1h o dock mostra ex. "83:20" enquanto o overlay mostra "1:23:20" — inconsistência visível e contagem de minutos sem limite, exatamente como descrito. O dano é real porém só se manifesta acima de 60 min de gravação; sem crash nem perda de dados, por isso medium. Nuance menor: cada janela seta seu próprio startedAt no evento, então pode haver leve dessincronia de segundos entre as duas, mas isso é independente e não muda o achado do truncamento de horas.

</details>

#### 54. 🟡 LOW — Handlers de hover via mutacao de style inline repetidos em ~5 lugares

- **Local:** `DockButton L22-59; titlebar.tsx ControlButton L18-59; sidebar.tsx L86-91,L120-127,L170-178,L188-195`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O padrao onMouseEnter/onMouseLeave que muda manualmente e.currentTarget.style.background/color esta reimplementado em DockButton (dock-bar L47-54), ControlButton (titlebar L45-54), nos nav links da sidebar (L120-127), no link de settings (L170-178) e no botao de logout (L188-195). Cada um repete as mesmas cores cruas ('rgba(0,0,0,0.04)', var(--chip), var(--ink), var(--muted)) com pequenas variacoes. Estilizar via JS no mousedown/enter, alem de verboso, perde estados :focus-visible (acessibilidade de teclado) que CSS daria de graca.

**Fix recomendado:** Criar classes utilitarias de botao no styles.css usando os tokens existentes, com :hover e :focus-visible, e trocar os handlers inline por classes. Ex.: (1) `.nora-icon-btn { background: transparent; color: var(--muted); transition: background .12s, color .12s; } .nora-icon-btn:hover, .nora-icon-btn:focus-visible { background: var(--chip); color: var(--ink); }` para DockButton/logout/settings/nav; (2) uma variante `.nora-icon-btn--danger:hover { background: var(--danger); color: #fff; }` para o botao de fechar do ControlButton. Manter o estado "ativo" da sidebar (var(--accent-soft)/var(--accent-ink)) como esta — ou expressa-lo via classe `.is-active` para nao reintroduzir a logica condicional no JS. Alternativa: um componente IconButton compartilhado se preferirem encapsular tamanho+icone. Caveats: (a) a sidebar mistura cores cruas levemente diferentes (0.03 vs 0.04) — padronizar numa so cor de hover e aceitavel e desejavel; (b) NAO mexer no onMouseDown/startDragging nem nos backgrounds solidos da overlay; (c) nao usar -webkit-app-region. Como e baixa prioridade, pode ser feito incrementalmente comecando por DockButton+ControlButton (os dois componentes ja extraidos), sem precisar refatorar os 10 arquivos de uma vez.

<details><summary>Justificativa da verificação</summary>

Confirmado nos arquivos. O padrao onMouseEnter/onMouseLeave que muta e.currentTarget.style.background/color esta de fato reimplementado em pelo menos 5 lugares com as mesmas cores cruas: DockButton (dock-bar.tsx L47-54: var(--chip)/var(--ink) -> transparent/var(--muted)), ControlButton (titlebar.tsx L45-54: rgba(0,0,0,0.06) ou var(--danger)+#fff), nav links da sidebar (sidebar.tsx L120-127: rgba(0,0,0,0.03)), link de settings (L170-178: rgba(0,0,0,0.04)) e botao de logout (L188-195: rgba(0,0,0,0.04)+var(--ink)). Na verdade a duplicacao e ainda mais ampla — grep encontra currentTarget.style.background em 10 arquivos. O ponto de acessibilidade tambem procede: como o realce so existe via eventos de mouse, esses botoes nao ganham :focus-visible (foco por teclado nao fica visualmente distinto), algo que uma classe CSS daria de graca. Todos os botoes ja tem className "transition-colors", entao ja estao prontos pra transicao via CSS. Ajusto a severidade pra low/debito de manutencao (nao e bug funcional) e confirmo que NAO conflita com os padroes intencionais: o hover via JS aqui e incidental, nao uma decisao cross-platform como o startDragging() (esse sim deve continuar). Importante: ao migrar pra CSS, NAO usar -webkit-app-region (que ja existe em styles.css L76 e nao funciona no WebKitGTK) — basta :hover/:focus-visible normais, que funcionam cross-platform.

</details>

#### 55. 🟡 LOW — invoke() de comandos Rust com .catch(() => {}) engolindo falhas sem feedback

- **Local:** `L100-104,L114 (invoke(...).catch(() => {}))`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** handleOpenMain (focus_main_window), handleOpenOverlay (toggle_overlay/focus_overlay_window) e handleHide (toggle_dock) usam .catch(() => {}) silencioso. Se o comando Rust falhar (janela nao existe, permissao de capability negada), o usuario clica e nada acontece, sem log nem aviso — dificil de diagnosticar em campo. Note que startDragging (L127) ja faz console.warn corretamente, entao o tratamento e inconsistente dentro do mesmo arquivo.

**Fix recomendado:** Padronizar apenas os catches de acoes iniciadas pelo usuario para logar como o startDragging ja faz, sem mudar comportamento: L100 invoke('focus_main_window').catch((err) => console.warn('[dock] focus_main_window failed:', err)); L103-104 idem para toggle_overlay e focus_overlay_window; L114 idem para toggle_dock; opcionalmente L117 para o emit. Manter L87 como esta (poll silencioso de mount e aceitavel). Nao adicionar pushNotification no dock — esse mecanismo so existe no webview da overlay; se feedback visual for desejado no futuro, teria de ser via emit pra overlay/main, o que e escopo maior e desnecessario pra este achado low.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. dock-bar.tsx L100 (focus_main_window), L103-104 (toggle_overlay/focus_overlay_window) e L114 (toggle_dock) usam .catch(() => {}) silencioso em invoke() de comandos Rust disparados por clique do usuario. A inconsistencia citada e real: na mesma file, L127 (startDragging) ja faz .catch((err) => console.warn('[dock] startDragging failed:', err)). Dano concreto e legitimo embora pequeno: se um comando falhar (janela inexistente, capability negada), o usuario clica num botao do dock e nada acontece, sem nenhum trace — diagnostico em campo fica cego. Severidade low esta correta: nao e bug de logica nem race, e os comandos costumam existir; e debito de error-handling/observabilidade. Ajusto o fix porque (a) a parte 'idealmente pushNotification' e impraticavel aqui: pushNotification vive no estado do webview da overlay.tsx (L1055), nao esta disponivel no webview do dock, e o dock e uma janela de 60px sem superficie pra toast — exigiria plumbing cross-window desproporcional pro problema; (b) L87 (.catch(() => {}) no pull inicial de get_recording_status no mount) NAO deve entrar no fix: e um best-effort poll silencioso legitimo, nao uma acao iniciada pelo usuario. O fix nao tem risco cross-platform (so adiciona log, nao mexe em startDragging/startResizeDragging nem em transparencia/decorations).

</details>

#### 56. 🟡 LOW — forceTick: useState usado so como gatilho de re-render para um relogio

- **Local:** `L64,L90-94`
- **Categoria:** professionalism · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** const [, forceTick] = useState(0) (L64) cria estado descartavel cujo unico proposito e forcar re-render a cada 500ms (L92) pra recalcular `duration` (L96-97) a partir de Date.now(). E um anti-pattern conhecido ('useForceUpdate'): o valor nunca e lido, o nome 'forceTick' nao comunica intencao, e mistura responsabilidade de relogio com estado de componente. O mesmo padrao de timer provavelmente existe no overlay.

**Fix recomendado:** Extrair um hook reutilizavel que centraliza o relogio e elimina os 3 force-render. Em src/hooks/, criar `useNow(active: boolean, intervalMs = 500): number` que retorna `Date.now()` via estado, com `setInterval(() => setNow(Date.now()), intervalMs)` ativo so quando `active` e cleanup no return; usar `now` (nao um tick descartavel) como o valor de estado real. Opcionalmente expor `useElapsedSeconds(startedAt, active)` que deriva `startedAt == null ? 0 : Math.floor((now - startedAt) / 1000)`. Em dock-bar.tsx: remover L64 (`const [, forceTick]`) e o useEffect L90-94, e calcular `const duration = useElapsedSeconds(startedAt, isRecording)`. Em use-live-transcript.tsx: remover `tick`/`setTick` (L163), o useEffect L164-168 e o hack `void tick` (L172-173), substituindo por `useElapsedSeconds(state.startedAt, state.isRecording)`. Bonus (mesma passada): unificar `formatDuration` num util compartilhado usando a versao do overlay (que trata horas), ja que a do dock quebraria gravacoes >59min. Caveats: (1) manter a condicao de gating original (overlay/hook checa `state.startedAt == null` alem de `isRecording`; preservar pra nao ligar timer sem startedAt); (2) usar updater funcional/`Date.now()` direto no setInterval pra evitar stale closure; (3) confirmar `npm run typecheck`/lint apos remover o `void tick` (era o silenciador do eslint — o lint deve ficar limpo justamente porque o valor passa a ser lido de verdade).

<details><summary>Justificativa da verificação</summary>

Confirmado no arquivo. dock-bar.tsx L64 `const [, forceTick] = useState(0)` cria estado cujo valor nunca e lido — so o setter e usado em L92 (`setInterval(() => forceTick((t) => t + 1), 500)`) pra forcar re-render a cada 500ms e recalcular `duration` em L96-97 (`Math.floor((Date.now() - startedAt) / 1000)`). E o anti-pattern classico "useForceUpdate", e o nome `forceTick` nao comunica a intencao real (atualizar relogio). Nao e bug: o relogio funciona; e divida tecnica/profissionalismo, dai a severidade `low` correta. O ponto forte do achado: a duplicacao e MAIOR do que o auditor afirmou ("provavelmente existe no overlay"). O padrao identico esta em src/hooks/use-live-transcript.tsx L162-173, que alimenta o overlay (OverlayPage destructura `duration` de useLiveTranscript em L1039-1040). La inclusive ha um hack pior: `const [tick, setTick] = useState(0)` + setInterval igual + `void tick` com comentario "referencia tick pra forcar re-render do timer (eslint silenciador)" — ou seja, codigo so pra calar o eslint. Logica de `duration` e o intervalo de 500ms sao identicos nos dois lugares (violacao DRY real). `formatDuration` tambem esta duplicado (dock-bar L16-20 vs overlay.tsx L70-75) com implementacoes divergentes (overlay trata horas, dock nao). Fix nao toca em nenhum padrao intencional cross-platform (startDragging, backgrounds solidos, decorations:false), entao e seguro.

</details>

### `apps/desktop/src/components/sidebar.tsx`  ·  1 achado(s)

#### 57. 🟡 LOW — NoraLogo linka para #/chat mas 'Conversar' tambem — e default da rota e meetings, gerando estado ativo ambiguo

- **Local:** `L40-43 (NAV_ITEMS) e L55-59 (isActive)`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** isActive('#/meetings') retorna true quando route === '' (L56). Mas o default real do app (App.tsx renderPage L59-65) cai em MeetingsPage para qualquer rota nao reconhecida, inclusive '#/' ou hashes desconhecidos — enquanto a sidebar so marca 'Reuniões' ativo para '' ou '#/meetings...'. Para uma hash como '#/' (nao vazia, nao iniciando com #/meetings) o App mostra Meetings mas a sidebar nao destaca nenhum item. Divergencia entre a logica de roteamento do App e a de highlight da sidebar.

**Fix recomendado:** Como nao ha caminho real que gere um hash 'orfao' enquanto autenticado, o refactor proposto (centralizar resolucao de rota compartilhada entre App.tsx e sidebar.tsx) eh overkill e adiciona risco no router por um caso inalcançavel. Se quiser fechar a inconsistencia de forma minima e segura, espelhar o fallback do renderPage no isActive: trocar L56 por `if (hash === '#/meetings') return !route.startsWith('#/chat') && !route.startsWith('#/settings');`. Isso faz 'Reunioes' ficar ativo para QUALQUER rota que o App resolve como MeetingsPage (incluindo `''`, `'#/'` e desconhecidos), alinhando exatamente os dois. Caveat: como a sidebar so eh montada quando autenticado, e `#/login` so ocorre deslogado, esse fix nao afeta o caso de login; e `#/meetings/{id}` continua destacando Reunioes (comportamento atual). Risco baixo. Dado o impacto nulo na pratica, aceitavel tambem WONTFIX/deixar como esta.

<details><summary>Justificativa da verificação</summary>

A divergencia existe no codigo, mas o impacto real eh praticamente nulo, entao ajusto a descricao/severidade do achado.\n\nFatos confirmados:\n- App.tsx renderPage (L59-65): qualquer hash nao reconhecido cai no fallback `return <MeetingsPage />` (default). Cobre `''`, `'#/'`, `'#/login'`, hashes desconhecidos.\n- sidebar.tsx isActive (L55-58): `isActive('#/meetings')` retorna true so para `route === ''` ou `route.startsWith('#/meetings')`. Logo, para um hash como `'#/'` (nao vazio, nao começa com `#/meetings`), o App mostra MeetingsPage mas a sidebar nao destaca nenhum item. A divergencia descrita eh tecnicamente correta.\n\nMas o dano concreto nao se materializa em nenhum fluxo real:\n1. Hash vazio `''` (load inicial): JA tratado — `isActive` faz `route === ''` retornar true para Reunioes. Sem divergencia.\n2. `#/login` (use-auth.tsx L60, em auth-expired): App cai no fallback Meetings, MAS `authenticated` vira false, entao renderiza LoginPage e a Sidebar nem eh montada. Sem divergencia visivel.\n3. `#/` ou hash arbitrario: NENHUM caminho do app produz isso. Todas as navegacoes geram `#/meetings`, `#/meetings/{id}`, `#/chat`, `#/settings` ou `#/login`. So seria alcancavel editando o hash manualmente — e um webview Tauri nao tem barra de endereço, entao o usuario nao consegue digitar `#/`.\n\nO titulo do achado tambem exagera a 'ambiguidade': NoraLogo (L73) e 'Conversar' (L41) apontarem ambos para `#/chat` eh intencional (logo eh atalho pro chat) e isActive destaca 'Conversar' corretamente em `#/chat`. Nao ha estado ativo ambiguo de fato. Conclusao: inconsistencia latente de robustez, sem defeito observavel — severity low.

</details>


## 2. TypeScript — pages

### `apps/desktop/src/pages/chat.tsx`  ·  2 achado(s)

#### 58. 🟡 LOW — Lista de mensagens usa índice do array como React key

- **Local:** `L366-380 (messages.map key={i})`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** As mensagens são renderizadas com `key={i}` (índice). Como mensagens só são anexadas ao fim e a lista pode ser limpa (setMessages([])), na prática hoje não há reordenação, então o bug é latente. Porém cada ChatMessage já carrega um `ts: Date.now()` que seria uma key mais estável; usar índice é antipadrão que pode causar reconciliação incorreta (estado/foco/animação preso à posição) se no futuro houver inserção/edição/remoção no meio (ex.: streaming com substituição da bolha 'thinking').

**Fix recomendado:** Adicionar um id monotônico estável por mensagem em vez de usar índice (e em vez do `ts`, que pode colidir). Ex.: estender a interface com `id: number` e usar um contador via useRef:
- `interface ChatMessage { id: number; role: "user"|"assistant"; content: string; ts: number }`
- `const idRef = useRef(0); const nextId = () => ++idRef.current;`
- nos três appends (L184/190/192) incluir `id: nextId()`
- render: `key={m.id}` em L368.
Mudança safe — puramente de reconciliação, não altera comportamento visível (lista append-only + clear-all). CAVEAT: NÃO usar `key={m.ts}` como sugerido pelo auditor, pois Date.now() pode repetir e gerar keys duplicadas. A observação sobre login.tsx:159 (TRANSCRIPT_LINES com key={i}) é dispensável: lista 100% estática nunca reordenada — não vale a pena tocar.

<details><summary>Justificativa da verificação</summary>

Confirmado em apps/desktop/src/pages/chat.tsx:366-380: `messages.map((m, i) => <MessageBubble key={i} .../>)` usa o índice do array como key. O antipadrão existe de fato. PORÉM o dano concreto descrito pelo auditor está superdimensionado: (1) a lista é estritamente append-only (setMessages((prev)=>[...prev, ...]) em L185/190/192) ou limpa por completo (setMessages([]) em L228) — nunca há inserção/remoção/reordenação no meio, e com key=índice o React reconcilia append-only corretamente (itens existentes mantêm o mesmo índice). (2) MessageBubble é puramente apresentacional (role/content/avatar), sem estado interno, foco de input ou animação atrelada à identidade — logo NÃO há foco/estado/animação que possa ficar "preso à posição". (3) O cenário de "streaming com substituição da bolha thinking" citado não bate com o código: a bolha thinking (L381-386) é um irmão condicional via `busy`, fora do array `messages`, não é spliced no meio. Ou seja: hoje impacto = zero; é um antipadrão latente legítimo de baixa severidade, mas a justificativa de dano atual está incorreta. Ressalva sobre o fix proposto: usar `key={m.ts}` é arriscado porque dois Date.now() consecutivos (ex.: erro síncrono raro, ou append rápido) podem colidir e gerar keys duplicadas — pior que índice. O melhor fix é um id incremental por mensagem.

</details>

#### 59. 🟡 LOW — ChatPage é um placeholder com respostas hardcoded (fakeAssistantReply) sem backend

- **Local:** `L18-30 (fakeAssistantReply) e toda a ChatPage`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** A página 'Conversar' (item de nav primário no sidebar) inteira roda em cima de fakeAssistantReply, que retorna strings fixas após um setTimeout aleatório, com TODO implícito ('Quando expor /v1/chat no backend, substituir aqui'). É funcionalidade de fachada exposta como item de menu de topo — risco de parecer recurso pronto numa demo/pitch e de virar débito esquecido. Não é bug, mas é débito técnico declarado que merece rastreio.

**Fix recomendado:** Não trocar o placeholder agora (manter é decisão de produto válida pro pitch). Como a honestidade na UI já está coberta pelos avisos "em construção" (L24/27/29/197/352), o fix se reduz a rastreabilidade: (a) transformar o comentário solto de L18-19 num marcador rastreável — `// TODO(US/issue #NNN): plugar /v1/chat (PIIShield -> LLM) e remover fakeAssistantReply` — vinculado a uma issue/US real no backlog; e opcionalmente (b) se quiser reduzir risco de "parecer pronto" antes do endpoint existir, esconder o item "Conversar" do NAV_ITEMS (sidebar.tsx L41) e o link do logo pra #/chat (L73-75) atrás de uma flag de build/env até `/v1/chat` estar no ar. Ambas as mudanças são seguras: (a) é só texto de comentário; (b) é gating de nav que não afeta drag/resize nem os padrões WebKitGTK, e mantém a rota acessível por hash se preciso pra demo. Severidade baixa — priorizar só (a) é suficiente pra fechar o achado.

<details><summary>Justificativa da verificação</summary>

Confirmado factualmente. apps/desktop/src/pages/chat.tsx L20-30: `fakeAssistantReply` retorna 3 strings hardcoded após `setTimeout(1100 + Math.random()*600)`, e o comentário L18-19 declara explicitamente que é placeholder até existir `/v1/chat` no backend — toda a ChatPage roda em cima disso (L189). É débito técnico real e auto-declarado, não bug (o próprio auditor admite "não é bug", severidade low — honesto). O agravante verificado: "Conversar" é o PRIMEIRO item de nav no sidebar (sidebar.tsx L41) E o próprio logo aponta pra #/chat (sidebar.tsx L73-75), ou seja, é o destino mais proeminente/default do app, o que aumenta o risco de "parecer pronto" num pitch/demo. Por outro lado, parte da preocupação do auditor já está resolvida: a UI tem avisos visíveis de "em construção" em vários pontos (L24, L27, L29, L352) e até a mensagem de erro do catch (L197) admite que está em integração — então o usuário não é enganado. O gap real e acionável que sobra é puramente rastreabilidade: existe só um comentário solto no código, sem TODO/issue/flag ligando ao endpoint, fácil de virar débito esquecido. Não há nada divergente do escopo (é apps/desktop) e nada que conflite com os padrões intencionais cross-platform.

</details>

### `apps/desktop/src/pages/login.tsx`  ·  4 achado(s)

#### 60. 🟡 LOW — Redirecionamento por hash e guard `if (authenticated) return null` são inertes — Router não roteia o login por hash

- **Local:** `L249-253`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** LoginPage faz `window.location.hash = "#/meetings"` num useEffect e tem `if (authenticated) return null`. Mas App.tsx (Router) NÃO seleciona LoginPage por hash: ele renderiza `<LoginPage />` puramente com base em `!authenticated` (L91-92). Quando o login conclui, `setAuthUser(user)` muda o estado do AuthProvider, o Router re-renderiza e troca para o conteúdo do app independentemente do hash. Logo a escrita no hash não dispara nenhuma navegação útil (e o guard nunca é atingido em prática, pois o Router já deixou de montar LoginPage). É código morto que dá falsa impressão de controlar a navegação e pode confundir manutenção futura.

**Fix recomendado:** Remover o `useEffect` de redirecionamento (L249-251) e o `if (authenticated) return null` (L253) de LoginPage, deixando a decisão de montar/desmontar a página inteiramente com o Router. O fix é safe: comportamento idêntico (após login o usuário já cai em MeetingsPage via fallback default de renderPage). Único detalhe a verificar: depois do fluxo `auth-expired`, o hook seta `window.location.hash = "#/login"` (use-auth L60). Sem a escrita do hash em LoginPage, esse `#/login` permanece após re-login, mas como `renderPage()` não tem rota `#/login` ele cai no fallback `<MeetingsPage/>` (App.tsx L64) — mesmo resultado. Se quiser higienizar a URL de forma honesta, faça isso no Router (ex.: normalizar/limpar o hash quando ele não casa com nenhuma rota), não na página. Não há risco cross-platform (puro JS de roteamento por hash, sem relação com WebKitGTK/drag/transparência).

<details><summary>Justificativa da verificação</summary>

Confirmado lendo apps/desktop/src/pages/login.tsx (L249-253), App.tsx (L59-107) e hooks/use-auth.tsx. O Router em App.tsx escolhe a tela exclusivamente por `loading`/`!authenticated` (L91-92): `renderPage()` (que consulta o hash/route) só é chamado dentro do ramo `else` que exige `authenticated === true` (L94-106). Quando o login conclui, `setAuthUser(user)` -> `setUser(u)` -> `authenticated = !!user` vira true (use-auth L40-44, L74), o Router re-renderiza e deixa de montar `<LoginPage/>`, montando o conteúdo do app. Como LoginPage e Router consomem o MESMO `authenticated` do contexto, no commit em que `authenticated` vira true o Router já desmonta LoginPage — efeitos de um componente sendo desmontado não disparam, então o body do useEffect L249-250 (`if (authenticated) window.location.hash = "#/meetings"`) nunca executa com authenticated=true, e o guard `if (authenticated) return null` (L253) é inalcançável na prática (dentro de LoginPage authenticated é sempre false enquanto ela está montada). Logo a escrita no hash não dispara navegação (o usuário cairia em MeetingsPage de qualquer forma, pois é o fallback default em renderPage L64). É código inerte que aparenta controlar navegação. Ajustei para "adjusted" porque a justificativa do auditor de que "o guard nunca é atingido" está correta, mas vale registrar a única utilidade residual marginal: limpar o hash deixado como `#/login` pelo handler `auth-expired` (use-auth L60) — porém isso é cosmético, pois `#/login` não é uma rota e cai no mesmo fallback MeetingsPage.

</details>

#### 61. 🟡 LOW — Botões 'Continuar com Microsoft' e 'Esqueci minha senha' não fazem nada (sem onClick)

- **Local:** `L348-373 (botão Microsoft) e L444-450 ('Esqueci minha senha')`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** O botão de SSO Microsoft e o de 'Esqueci minha senha' são type='button' sem nenhum onClick — clicar não dispara ação alguma. São CTAs mortos numa tela de login de produto, o que numa demo/pitch quebra a confiança (usuário clica e nada acontece).

**Fix recomendado:** Como não há backend de Microsoft OIDC / reset de senha em escopo no desktop, evitar affordance falsa. Duas opções, ambas safe (os botões não têm comportamento a quebrar): (1) Converter ambos em texto não-clicável seguindo a convenção já usada no arquivo para "Falar com vendas"/"SSO · SAML" (spans decorativos), removendo cursor:pointer e os handlers de hover; ou (2) manter como button mas adicionar disabled + title="Em breve" e baixar a opacidade, sinalizando indisponibilidade. Se forem mesmo entrar na v1, aí sim wire os handlers reais (Microsoft via Token Broker do ADR 0009 / fluxo de reset). Recomendo a opção (1) por consistência com o restante do rodapé do form. Nenhum dos padrões cross-platform intencionais (startDragging/startResizeDragging, backgrounds sólidos, decorations:false) é afetado por essa mudança.

<details><summary>Justificativa da verificação</summary>

Confirmado abrindo apps/desktop/src/pages/login.tsx. O botão "Continuar com Microsoft" (L348-373) é type="button" e só tem onMouseEnter/onMouseLeave (hover) — sem onClick. O botão "Esqueci minha senha" (L444-450) também é type="button" sem onClick. Verifiquei via grep que não há onClick, event delegation, nem handler de form que os cubra: o onSubmit={handleSubmit} (L318) atende apenas o submit "Entrar" (L522-546). Como o botão Microsoft é type="button", ele nem dispara o submit do form — ou seja, clicar em qualquer um dos dois não faz absolutamente nada. Não existe suporte a Microsoft OIDC nem fluxo de reset de senha em nenhum lugar de apps/desktop (grep recursivo só encontra esses literais no próprio login.tsx). São affordances clicáveis (cursor pointer, efeito hover) que prometem interatividade inexistente — em demo/pitch FIAP o usuário clica e nada acontece. Severidade low está correta: é lacuna de UX/polish, não bug funcional nem de segurança. Não confundir com "Falar com vendas" (L554) e "SSO · SAML" (L556), que são <span> de texto puro (decorativos, sem affordance de clique) — esses estão OK e mostram que o arquivo já tem a convenção de placeholder não-clicável.

</details>

#### 62. 🟡 LOW — Botões/links interativos sem handler — afetam usabilidade e dão falsa sensação de funcionalidade

- **Local:** `L347-373 (Microsoft), L444-450 (Esqueci minha senha), L552-555 (Falar com vendas)`
- **Categoria:** gambiarra · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Três controles parecem acionáveis mas não fazem nada: o botão "Continuar com Microsoft" (sem onClick, apenas hovers), "Esqueci minha senha" (<button type="button"> sem onClick) e "Falar com vendas" (texto estilizado como link sem ação). O de Microsoft é o mais grave: é o CTA primário de SSO no topo do formulário e clicar nele não dá nenhum feedback nem erro — o usuário fica sem entender por que nada acontece.

**Fix recomendado:** Mínimo seguro (recomendado agora): nos dois <button> (Microsoft L348-373 e "Esqueci minha senha" L444-450) adicionar `disabled` + `title="Em breve"` (e cursor:"not-allowed"/opacity reduzida) pra parar de simular interação; converter "Falar com vendas" (L552-555) de <span> clicável-aparente para texto plano OU um <a href> real. Melhor (quando houver fluxo): adicionar onClick com feedback — o app já tem @tauri-apps/plugin-shell ^2 (package.json L15) e tauri-plugin-shell 2 (Cargo.toml L17), então "Falar com vendas" pode chamar `open("mailto:vendas@...")` do plugin-shell (NÃO existe "plugin-opener" instalado — corrigir a sugestão original do auditor que citou "plugin opener"); "Esqueci minha senha" deve apontar pro fluxo de reset quando o backend expuser; Microsoft SSO só habilitar quando o broker OIDC/SAML existir. Caveat: confirmar que a capability do plugin-shell permite `open` antes de usar mailto. Fix é safe: não toca nos padrões intencionais de drag/resize/transparência cross-platform; mexe só em controles estáticos sem comportamento.

<details><summary>Justificativa da verificação</summary>

Confirmado nos três locais de /home/pollo/Dev/nora/apps/desktop/src/pages/login.tsx. (1) Botão "Continuar com Microsoft" (L348-373): tem type="button" + MicrosoftIcon, mas só onMouseEnter/onMouseLeave — nenhum onClick. Clicar não faz nada e não dá feedback. (2) "Esqueci minha senha" (L444-450): <button type="button" className="hover:underline"> sem onClick. (3) "Falar com vendas" (L552-555): nem é botão — é um <span style={{color:"var(--ink)"}}> dentro de outro span, texto estilizado pra parecer link, sem ação. O dano é real porque a página É usada (App.tsx L92 renderiza <LoginPage/>) e tem fluxo de auth funcional de verdade (login via /auth/login em lib/auth.ts L47, handleSubmit com setError/loading reais), então o usuário razoavelmente espera que esses controles também funcionem. Como type="button", o botão Microsoft NÃO submete o form acidentalmente (sem dano colateral). PORÉM ajusto severity de medium pra LOW: não há quebra funcional, erro de runtime, risco de segurança nem perda de dado — são placeholders de feature não implementada num form cujo caminho principal (email/senha) funciona. Medium superestima; é débito de UX/dead-UI, categoria "gambiarra"/placeholder está correta.

</details>

#### 63. 🟡 LOW — Extração de mensagem de erro com cast inseguro e fallback para JSON.stringify do erro cru

- **Local:** `L262-268 (handleSubmit catch)`
- **Categoria:** type-safety · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** No catch, quando o erro não é `Error`, faz `(err as Record<string, unknown>)?.message?.toString()` — cast para Record arbitrário e chamada `.toString()` num valor `unknown` (TS aceita por causa do cast, mas em runtime `message` pode ser objeto/null). O fallback final `JSON.stringify(err)` pode jogar na UI um JSON cru de exceção (potencialmente vazando detalhes internos do backend/stack ao usuário final) em vez de uma mensagem amigável.

**Fix recomendado:** Extrair um helper compartilhado (ex. em apps/desktop/src/lib/) tipo `toUserMessage(err: unknown, fallback: string): string` e usa-lo nos 6 sitios para padronizar. Logica segura: se `err instanceof Error` use `err.message`; senao, se for objeto com campo `message` string nao-vazia (checar typeof, sem cast cru: `typeof err === 'object' && err !== null && 'message' in err && typeof (err as {message:unknown}).message === 'string'`) use esse message; caso contrario retorne o `fallback` generico ("Erro ao fazer login."). NUNCA usar `JSON.stringify(err)` na UI. Sempre `console.error(err)` antes para preservar o erro completo no log. CAVEAT importante: NAO substituir por mensagem fixa generica sempre — isso regrede o caso comum em que o backend manda uma mensagem util (ex. "Credenciais invalidas") que o usuario deve ver; preferir o `message` real quando presente, generico so no fallback. Opcional e mais limpo na raiz: api-client.ts:86 poderia lancar um Error real (`throw new Error(body?.message ?? \`HTTP ${status}\`)`) em vez do body cru, alinhando todos os call-sites ao padrao `instanceof Error` ja dominante — mas isso muda o contrato de erro do api-client e exige checar quem mais consome esse throw, entao avaliar separado.

<details><summary>Justificativa da verificação</summary>

Confirmado no local citado (apps/desktop/src/pages/login.tsx L262-268). O branch unsafe NAO e teorico: login() chama apiClient.request com auth:false, e em status>=400 o api-client (apps/desktop/src/lib/api-client.ts:86) faz `throw response.body || { message: ... }`, ou seja, joga o corpo cru do backend (tipado `unknown`) como erro. Logo `err` no catch quase nunca e um Error — cai sempre no branch `(err as Record<string, unknown>)?.message?.toString() || JSON.stringify(err)`. (1) O cast suprime o TS sobre um valor de runtime nao garantido; `?.toString()` nao quebra em null/undefined, mas um `message` objeto vira "[object Object]" — real, porem menor. (2) O ponto substantivo e o fallback `JSON.stringify(err)`: quando o corpo de erro nao tem `message` utilizavel, ele despeja o JSON cru do backend na caixa de erro do login (Spring por padrao retorna timestamp/status/error/path, e ate trace conforme config) — feio e levemente vazante. Consistencia: dos 6 sitios de tratamento de erro do desktop (meetings.ts:63, new-meeting-modal.tsx:81, use-recording.ts:268/321, use-active-recording.tsx:147/219), login e o UNICO que usa esse padrao; os outros usam `e instanceof Error ? e.message : String(e)`. Severidade fica LOW: nao ha crash, e cliente desktop (nao superficie publica) e o caso comum traz `message`. Ajusto o veredito porque a severity/categoria estao corretas mas o fix proposto precisa de um caveat.

</details>

### `apps/desktop/src/pages/meeting-detail.tsx`  ·  6 achado(s)

#### 64. 🟡 LOW — Checkbox de action item nao persiste — controle puramente cosmetico

- **Local:** `L78-83 (ActionRow checkbox)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O checkbox usa `defaultChecked={a.status === "DONE"}` e so tem `onClick={(e)=>e.stopPropagation()}` (impedir abrir o quote). Nao ha `onChange`, nao chama API, nao atualiza estado. O usuario marca/desmarca, o estado visual muda (uncontrolled), mas nada e salvo e o valor se perde no proximo fetch/poll. Isso comunica uma funcionalidade (concluir action item) que nao existe — engana o usuario.

**Fix recomendado:** Curto prazo (safe, recomendado): tornar o checkbox read-only/disabled pra nao prometer funcionalidade inexistente. Como o onClick=stopPropagation existe SO pra blindar o accordion do pai, mantenha-o. Trocar defaultChecked por checked={a.status === "DONE"} + readOnly + cursor:"default" + title="Status definido pela analise da NORA (somente leitura nesta versao)". Cuidado: nao remova o stopPropagation (sem ele, clicar no checkbox dispara setOpen do accordion). Opcao mais limpa: substituir o input por indicador visual nao-interativo (ja existe o badge de status colorido em L95 via statusColor), evitando o affordance de checkbox por completo. Longo prazo: so implementar toggle real quando houver endpoint de PATCH de status de action item no backend (nao existe hoje em meetings.ts); ai adicionar estado controlado + chamada API + rollback otimista em erro. Escopo: arquivo esta em apps/desktop/**, dentro do escopo.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. meeting-detail.tsx L78-83: input checkbox com defaultChecked={a.status === "DONE"} (uncontrolled) e UNICO handler onClick={(e)=>e.stopPropagation()}, cujo proposito real e impedir que o clique propague pro onClick do pai (L76, que faz setOpen(!open) do accordion de sourceQuote). Nao ha onChange, nao ha chamada de API, nao ha update de estado. Verifiquei src/lib/meetings.ts: a unica superficie exportada e listMeetings/getMeeting/reprocessMeeting/uploadTranscript — NAO existe endpoint pra atualizar status de action item. Logo o checkbox e puramente cosmetico: oferece um affordance ("concluir action item") que nao faz nada. AJUSTE na precisao tecnica do auditor: ele diz que "o valor se perde no proximo fetch/poll". Isso esta tecnicamente impreciso. Como o input e uncontrolled e o ActionRow e keyed por a.id (L576), o re-render do polling (a cada 4s) NAO remonta o componente, entao o DOM mantem o estado visual do checkbox durante a sessao. A perda acontece no remount (sair/voltar da tela) ou se a lista re-keyar. Mesmo assim o nucleo do achado se sustenta: nada e persistido e o controle finge funcionalidade. Tambem REBAIXO de medium pra low: e debito de UX honesto (affordance falso), nao bug funcional/risco — a tela funciona, so promete algo que nao entrega.

</details>

#### 65. 🟡 LOW — "Transcrição original" e "Exportar resumo" sao spans inertes apresentados como ações

- **Local:** `L631-647 (footer)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** No rodape ha `<span>Transcrição original</span>` e `<span>Exportar resumo</span>` posicionados e estilizados como itens acionaveis, mas sao apenas texto: nenhum onClick, href ou handler (confirmado por grep — so aparecem como spans). Sao affordances falsas: parecem botoes/links de export e abrir transcricao mas nao fazem nada.

**Fix recomendado:** Como sao placeholders de roadmap e nao tem comportamento algum hoje, o fix mais seguro e simplesmente remover os dois spans (L641-643) junto com o separador `·` (L642), deixando o rodape so com o status "PII Shield aplicado". Se a intencao e sinalizar features futuras, marcar como desabilitado (ex: title="em breve" + cursor:default, mantendo a opacity ja muted) — mas evitar adicionar cursor:pointer/underline, que iria justamente CRIAR a falsa affordance que hoje nao existe. Risco do fix: nenhum — os spans nao tem logica, nenhum comportamento quebra; nao toca nenhum padrao cross-platform (drag/resize/transparencia). Nao implementar export/abrir-transcricao agora a menos que esteja no escopo da fatia, pra nao incorrer em scope creep (ADR 0014).

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo: em apps/desktop/src/pages/meeting-detail.tsx L641 `<span>Transcrição original</span>` e L643 `<span>Exportar resumo</span>` sao spans inertes — sem onClick, href ou handler (grep confirma que ambas strings aparecem so 1x, exatamente nessas linhas, sem qualquer wiring). Logo a parte factual do achado e real: dois labels que sugerem features ("abrir transcricao" e "exportar resumo") que nao existem = debito/UI placeholder. PORÉM o framing de "falsas affordances que parecem botoes/links acionaveis" esta exagerado: o container (L631-648) e um rodape estilizado fontSize:11 + color:var(--muted), e os spans NAO tem cursor:pointer, hover, underline, borda nem background — sao visualmente identicos ao terceiro item inerte e legitimo "PII Shield aplicado" (L644-647, dot verde + status). Ou seja, nada convida ao clique; sao apenas labels de rodape sem acao. Por isso ajusto: e cleanup menor de placeholder, nao um bug de UX enganoso de severidade relevante. Severidade low procede.

</details>

#### 66. 🟡 LOW — Estilo de chip/pill (radius 999, padding, var(--chip)/var(--accent-soft)) repetido inline varias vezes

- **Local:** `L355-398 e L420-424 (chips/pills participantes+tags)`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O mesmo objeto de estilo de pill (fontSize ~11-12, padding "3px 9px", borderRadius 999, background var(--chip) ou var(--accent-soft)) e copiado para participantes, owner fallback e tags em meeting-detail.tsx, e tambem nos chips de tag em meetings.tsx (L155-169). Estilo de componente duplicado inline em vez de um <Chip variant>.

**Fix recomendado:** Extrair um componente leve em apps/desktop/src/components (ex: chip.tsx) com props variant ("neutral" usando var(--chip)/var(--ink) | "accent" usando var(--accent-soft)/var(--accent-ink)) e size ("sm" = fontSize 11 padding "1px 8px" | "md" = fontSize 12 padding "3px 9px"), todos com borderRadius 999. Reusar nas 3 ocorrencias de meeting-detail.tsx (participantes/owner = variant neutral size md; tags = variant accent size md) e na de meetings.tsx (variant neutral size sm — preservando o "1px 8px"/fontSize 11 atuais, NAO unificar para "3px 9px" senao muda o layout da lista). Caveat importante: manter os paddings/fontSizes exatos de cada chamada via a prop size para nao alterar o pixel-rendering atual; nao tentar abstrair os outros borderRadius:999 do codebase (orbs/avatars/botoes) sob o mesmo componente. Como e low/cosmetico, e legitimo deixar para um cleanup futuro em vez de bloquear PR.

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo citado. Em meeting-detail.tsx o objeto de estilo inline de pill aparece 3 vezes: L361-367 (participantes) e L374-380 (owner fallback) sao byte-a-byte identicos (fontSize 12, padding "3px 9px", borderRadius 999, background var(--chip), color var(--ink)); L388-394 (tags) e a variante accent (fontSize 11, background var(--accent-soft), color var(--accent-ink)). Em meetings.tsx L156-168 ha um 4o chip de tag, porem NAO identico: usa className "px-2" + padding "1px 8px" + fontSize 11 + var(--chip)/var(--ink). Grep confirma que NAO existe componente Chip/Badge/Pill no projeto. Entao a duplicacao e real. Porem rebaixo de "componente duplicado em 2 paginas com mesmo estilo" para uma duplicacao com 3 variantes de tamanho/cor diferentes — o auditor sugeriu so 2 variants ("neutral"|"accent") mas na pratica precisa cobrir tambem padding/fontSize divergentes entre as duas paginas. Impacto e puramente estetico/manutencao: zero bug funcional, zero risco runtime. Por isso severity low e fixRisk safe (mudanca so visual, sem mexer em drag/transparencia/WebKitGTK). Vale notar que borderRadius:999 sozinho aparece em ~12 lugares para coisas diferentes (orbs, avatars, botoes, dots) — NAO devem virar Chip; o escopo correto e so o padrao "label em pill".

</details>

#### 67. 🟡 LOW — Checkbox de action item é puramente cosmético — defaultChecked sem onChange/persistência

- **Local:** `L78-83 (ActionRow checkbox)`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O <input type=checkbox defaultChecked={a.status === 'DONE'} /> permite o usuário marcar/desmarcar uma action item, mas não há onChange nem chamada de API: a mudança não persiste e some no próximo refetch (o polling a cada 4s enquanto PENDING/PROCESSING, ou ao reabrir). Dá ilusão de poder concluir tarefas que na verdade não faz nada — confunde o usuário num fluxo de demo.

**Fix recomendado:** Curto prazo (safe, recomendado ate existir endpoint): tornar o checkbox nao-enganoso, refletindo status sem sugerir interacao. Em apps/desktop/src/pages/meeting-detail.tsx L78-83, trocar para controlado + desabilitado: `<input type="checkbox" checked={a.status === 'DONE'} disabled readOnly style={{ marginTop: 4, accentColor: 'var(--accent)' }} />` (pode remover o onClick stopPropagation; com disabled o clique nem propaga). Caveat: usar `checked` controlado exige `readOnly` para o React nao emitir warning de input controlado sem onChange. Medio prazo (needs-care): implementar a mutacao real — adicionar `updateActionItemStatus(meetingId, actionItemId, status)` em apps/desktop/src/lib/meetings.ts apontando para o endpoint PATCH do backend, com update otimista local (atualizar `meeting.analysis.actionItems[i].status`) e rollback no catch + feedback de erro, seguindo o padrao do handleReprocess. Nenhuma das opcoes toca nos padroes cross-platform intencionais (startDragging/startResizeDragging, backgrounds opacos, decorations:false).

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo: apps/desktop/src/pages/meeting-detail.tsx L78-83. O checkbox usa `defaultChecked={a.status === 'DONE'}` (uncontrolled) e seu unico handler e `onClick={(e) => e.stopPropagation()}`, que so impede o toggle de expandir o sourceQuote da linha — nao ha onChange nem chamada de API. Confirmei em apps/desktop/src/lib/meetings.ts que NAO existe endpoint de mutacao de action item (so listMeetings/getMeeting/reprocessMeeting/uploadTranscript), entao a marcacao e impossivel de persistir hoje. O ActionItem (types.ts L99-107) tem status OPEN|IN_PROGRESS|DONE|CANCELLED e o checkbox mapeia DONE->checked, mas marcar/desmarcar nao faz nada server-side: e um controle interativo que ilude o usuario num fluxo de demo. AJUSTE no raciocinio do auditor: a afirmacao de que a mudanca "some no proximo refetch / polling a cada 4s" e imprecisa. O polling (L237-244) so roda enquanto processingStatus e PENDING/PROCESSING; os action items so renderizam quando isReady (COMPLETED + analysis), momento em que o polling JA parou. Logo, vendo uma reuniao concluida, o polling NAO apaga o toggle a cada 4s — o estado e perdido apenas ao remontar/navegar (estado uncontrolled nao persistido). O problema central (controle nao-funcional e enganoso) permanece, mas o mecanismo especifico citado esta errado.

</details>

#### 68. 🟡 LOW — Optional chaining em meeting.tags inconsistente (?. vs acesso direto)

- **Local:** `L334 vs L385`
- **Categoria:** type-safety · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Em L334 o codigo trata tags como possivelmente undefined (`meeting.tags?.[0] || "Sem projeto"`), mas em L385 acessa direto `meeting.tags.slice(1)` sem guarda. O tipo MeetingDetail (via MeetingSummary em lib/types.ts) declara `tags: string[]` como obrigatorio. Ou o `?.` em L334 e defensividade morta (o campo nunca falta), ou o campo realmente pode vir ausente do backend — e nesse caso L385 lanca `Cannot read properties of undefined (reading 'slice')` e quebra a pagina inteira. A inconsistencia esconde qual das duas e a verdade.

**Fix recomendado:** Padronizar pra consistencia (mudanca cosmetica, sem efeito comportamental porque tags ja e sempre []+): remover o `?.` de L334 (`meeting.tags[0] || "Sem projeto"`) confiando no tipo/contrato do backend, ja que ambos garantem tags sempre presente. Alternativa igualmente valida e neutra: normalizar uma vez no topo do render (`const tags = meeting.tags;` ou `meeting.tags ?? []`) e usar a mesma variavel nos dois pontos (L334 e L385). NAO mexer no tipo pra tags?: string[] — isso iria CONTRA o contrato real (backend sempre envia) e adicionaria guardas desnecessarias. Caveat: como tags e provada non-null em runtime, este fix nao corrige bug nenhum; e so limpeza de defensividade morta — priorizar baixo.

<details><summary>Justificativa da verificação</summary>

A inconsistencia citada existe: meeting-detail.tsx L334 usa `meeting.tags?.[0] || "Sem projeto"` (optional chaining) e L385 usa `meeting.tags.slice(1)` (acesso direto). Confirmado por Read. PORE'M a tese central do auditor — que L385 lanca `Cannot read properties of undefined` e "quebra a pagina inteira", caracterizando severity medium — e FALSA sob o contrato real, e isso e o ponto que rebaixa o achado:\n\n1. Tipo: getMeeting() retorna Promise<MeetingDetail> (apps/desktop/src/lib/meetings.ts:23), e MeetingDetail extends MeetingSummary com `tags: string[]` OBRIGATORIO (apps/desktop/src/lib/types.ts:50).\n2. Backend: o dominio Meeting normaliza tags pra `List.of()` quando null no construtor (services/api/.../domain/meeting/Meeting.java:131-134), e o controller passa `m.tags()` direto (MeetingsController.java:254) — nunca null.\n3. Serializacao: MeetingDetailResponse nao tem @JsonInclude e nao ha config global Jackson NON_EMPTY/NON_NULL. Logo o JSON sempre traz `tags` (no minimo `[]`).\n\nConclusao: em runtime `meeting.tags` esta sempre definido (no minimo `[]`), `.slice(1)` nunca estoura, e a pagina nao quebra. O `?.` em L334 e defensividade morta contra um caso que tipo E backend dizem ser impossivel. E um code-smell / inconsistencia de estilo real, mas SEM falha concreta de runtime — portanto low, nao medium. Nao e nitpick puro (a divergencia ?./direto e legitimamente confusa e merece padronizar), mas a severidade e o impacto descrito foram inflados.

</details>

#### 69. 🟡 LOW — Checagem d.confidence !== undefined contradiz o tipo Decision

- **Local:** `L557 (Decision render)`
- **Categoria:** type-safety · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O render faz `d.confidence !== undefined && d.confidence < 0.85`, mas em lib/types.ts `Decision.confidence` e `number` (obrigatorio, nao opcional). Ou o guard e codigo defensivo morto (nunca undefined), ou o tipo esta mentindo e o backend as vezes omite confidence — caso em que outros consumidores que assumem number quebram. Divergencia entre camada de tipos e uso real.

**Fix recomendado:** Tornar o tipo honesto em vez de remover o guard. Em apps/desktop/src/lib/types.ts:96, mudar `confidence: number;` para `confidence?: number;`. Motivo: como getMeeting() nao valida o payload em runtime e confidence deriva de saida da LLM (plausivelmente omissivel), o opcional reflete a realidade e mantem o guard de meeting-detail.tsx:557 valido e justificado. NAO seguir a alternativa de remover o `!== undefined`: isso aposta que o backend sempre envia o campo, sem nenhuma validacao runtime que garanta — e o caminho mais arriscado. Caveat: idealmente confirmar com o contrato do backend (schema da analise NLP) se confidence e sempre emitido; se for garantido pelo schema strict JSON da LLM, a remocao do guard tambem seria valida, mas marcar opcional continua sendo o lado seguro sem custo. Escopo fora deste agente (services/nlp-worker), entao a mudanca acionavel no desktop e o `confidence?: number`.

<details><summary>Justificativa da verificação</summary>

Divergencia real e confirmada. Em apps/desktop/src/lib/types.ts:96 `Decision.confidence` e `number` (obrigatorio), mas apps/desktop/src/pages/meeting-detail.tsx:557 faz `d.confidence !== undefined && d.confidence < 0.85`. O guard contradiz o tipo. Crucialmente, getMeeting() (apps/desktop/src/lib/meetings.ts:23-25) faz apenas `apiClient.request<MeetingDetail>(...)` — um cast generico sobre o JSON cru, SEM validacao runtime. Entao o tipo `confidence: number` e uma asserção não verificada: nada garante em runtime que o campo venha do backend. O guard, portanto, NAO e dead code inofensivo — ele e load-bearing defensivo: se o backend omitir confidence, sem o guard a L559 faria Math.round(undefined * 100) = NaN, renderizando "· NaN%". POREM a afirmacao mais forte do auditor ("outros consumidores que assumem number quebram") e FALSA: os unicos consumidores de Decision.confidence sao as duas linhas L557/L559, ambas dentro do mesmo guard. O `confidence` em use-live-highlights.tsx:7 pertence a outra interface (LiveHighlightItem), nao a Decision. Logo o dano concreto e limitado a clareza de codigo / honestidade de tipo (leitor nao sabe se o guard e morto ou necessario), nao a um bug ativo nem a quebra em cascata. Por isso: real, mas severity low e framing ajustado.

</details>

### `apps/desktop/src/pages/meetings.tsx`  ·  3 achado(s)

#### 70. 🟡 LOW — Logica de focus border+shadow duplicada (existe focusOn/focusOff no modal)

- **Local:** `SearchBar L322-329 (onFocus/onBlur)`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O input de busca inlina onFocus/onBlur que setam `borderColor=var(--accent)` + `boxShadow=0 0 0 3px var(--accent-soft)` no focus e revertem no blur — exatamente as funcoes `focusOn`/`focusOff` ja definidas em new-meeting-modal.tsx (L24-31). A mesma micro-logica de focus ring esta copiada em pelo menos dois arquivos, com risco de divergencia visual.

**Fix recomendado:** Extrair focusOn/focusOff para um modulo compartilhado (ex: src/components/ui/field.ts) com assinatura React.FocusEvent<HTMLElement> (funciona pra <input> e <textarea>). Reusar nos 5 sites: meetings.tsx (SearchBar), new-meeting-modal.tsx (ja usa, so trocar o import), login.tsx (2 inputs) e overlay.tsx. Caveats: (1) cada site DEVE manter no style base a transition de border-color/box-shadow pra o ring animar — focusOn/focusOff so mexem em borderColor e boxShadow, nao na transition; (2) opcionalmente extrair tambem o inputCss base, mas note que os inputs divergem em padding/fontSize (meetings usa 9px/13, modal usa 10px/13.5), entao um inputCss unico exigiria props/overrides — manter so focusOn/focusOff e o ganho seguro e suficiente; (3) overlay e janela com restricoes WebKitGTK (bg solido), mas box-shadow num sub-elemento input e ok, sem impacto na transparencia da janela.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo exato. meetings.tsx:322-329 inlina onFocus/onBlur que setam borderColor=var(--accent) + boxShadow=0 0 0 3px var(--accent-soft) e revertem no blur — identico aos helpers focusOn/focusOff ja definidos em new-meeting-modal.tsx:24-31. A duplicacao e na verdade MAIOR do que o achado relata: os helpers existem mas sao module-local (nao exportados) e usados so no modal; ha pelo menos 4 outras copias inline da mesma micro-logica em login.tsx:420-427, login.tsx:470-477 e overlay.tsx:996-1004. DRY violation real, com risco concreto de divergencia visual (ex: alguem muda o ring pra 2px ou outra cor num lugar so). Severidade honesta = low: e logica puramente cosmetica de focus ring, nenhum bug de comportamento, pouca duplicacao por site. Ajusto o achado porque o escopo correto sao 5 sites, nao 2.

</details>

#### 71. 🟡 LOW — Banner de erro (caixa rgba 201,119,102) duplicado em 3 telas

- **Local:** `L515-530; meeting-detail.tsx (banner falha); new-meeting-modal.tsx L260-274`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O mesmo bloco de erro — `background rgba(201,119,102,0.10)`, `border rgba(201,119,102,0.25)`, radius, fontSize, color var(--danger-ink) — esta replicado em meetings.tsx, new-meeting-modal.tsx e variantes em meeting-detail.tsx. Constantes de cor de danger hardcoded como rgba literais espalhadas, divergindo de var(--danger). Mudar o estilo de erro exige editar varios arquivos.

**Fix recomendado:** Extrair um componente compartilhado, ex. apps/desktop/src/components/error-banner.tsx: function ErrorBanner({ children }) que renderiza a caixa com padding "10px 12px", borderRadius 8, fontSize 13, lineHeight 1.45, color var(--danger-ink). Usar nos 3 sites (meetings.tsx, new-meeting-modal.tsx, login.tsx), padronizando os valores divergentes (radius/fontSize). Para as cores, NAO usar var(--danger-soft) (token inexistente); em vez disso adicionar em styles.css os tokens reutilizaveis, ex. --danger-soft-bg: rgba(201,119,102,0.10) e --danger-soft-border: rgba(201,119,102,0.25) (ou derivar de #c97766), e referencia-los no componente e nos demais usos hardcoded (overlay-notifications.tsx L85, dock-bar.tsx L187/189, settings.tsx L36, login.tsx L65, etc.). Deixar o card de falha de meeting-detail.tsx fora do escopo do ErrorBanner (e outro componente) — no maximo trocar os literais rgba dele pelos novos tokens. Manter backgrounds solidos/alpha-sobre-opaco; nada de backdrop-filter para preservar compat WebKitGTK caso o componente seja usado fora da main window.

<details><summary>Justificativa da verificação</summary>

Confirmado, com ajuste. O "banner de erro simples" (caixa inline com mensagem) esta de fato triplicado, com os mesmos valores quase identicos: meetings.tsx L515-530 (bg rgba(201,119,102,0.10), border 1px rgba(201,119,102,0.25), color var(--danger-ink), lineHeight 1.45), new-meeting-modal.tsx L260-274 (mesmo bg/border, radius 9, fontSize 12.5, lineHeight 1.5) e login.tsx L506-520 (praticamente identico ao meetings, radius 8). Os literais rgba(201,119,102,...) sao exatamente o RGB de var(--danger) (#c97766 = rgb(201,119,102)), entao sao copias hardcoded do token, divergindo do uso de CSS var — ponto DRY/manutencao valido. Drift numerico real entre as copias (radius 8 vs 9, fontSize 13 vs 12.5) reforca que ja comecaram a divergir.

Dois ajustes ao achado: (1) meeting-detail.tsx L442-465 NAO e o mesmo banner — e um card de "Falha no processamento" com layout flex, titulo, descricao e botao de reprocessar (border 0.30, radius 12, conteudo rico). E um componente conceitualmente distinto, nao a mesma caixinha; rotula-lo como "variante" do mesmo banner e leve overreach. A duplicacao real do banner-simples e nas 3 telas (meetings, new-meeting-modal, login). (2) O fix proposto cita var(--danger-soft) que NAO existe em styles.css — la so ha --danger (#c97766) e --danger-ink (#a04c3e). O fix literal quebraria (token inexistente).

Sem impacto funcional — e puramente cosmetico/manutencao, por isso severidade low e honesta. Risco do fix baixo: sao paginas da janela main, nao a overlay, entao nao toca no padrao intencional de backgrounds solidos do WebKitGTK (e mesmo se um componente compartilhado fosse reusado na overlay, manteria o bg solido com alpha sobre canvas opaco).

</details>

#### 72. 🟡 LOW — Spinner inline (nora-spin) reimplementado em cada tela

- **Local:** `L340-350; meeting-detail.tsx L495-504; new-meeting-modal.tsx L315-323`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** O mesmo span de spinner (`border ... borderTopColor ... borderRadius 50% ... animation: nora-spin 0.9s linear infinite`) e copiado inline em meetings.tsx, meeting-detail.tsx, new-meeting-modal.tsx (e tambem overlay.tsx/chat.tsx fora desta fatia), variando so size/cor. Bastante boilerplate repetido.

**Fix recomendado:** Extrair um componente presentacional puro, ex. src/components/spinner.tsx: function Spinner({ size = 11, color = "var(--chip)", topColor = "var(--accent)", thickness = 1.5 }) que renderiza o span com border/borderTopColor/borderRadius/animation. Substituir os 7 spans inline passando os valores exatos de cada call site para preservar aparencia (size 9/10/11/12; cores que usam var(--accent)/var(--canvas) e rgba(253,253,252,0.4)/0.35; thickness 1.5/2). Caveats: (1) preservar exatamente os tokens de cor/tamanho de cada local — eles diferem de proposito; (2) onde o spinner fica dentro de um botao com pointerEvents:"none" no container pai (meetings.tsx) ou em flexbox com label (meeting-detail/new-meeting-modal), garantir que o componente nao introduza wrapper extra que mude layout — renderizar exatamente um span como antes. Risco baixo, sem mudanca de comportamento.

<details><summary>Justificativa da verificação</summary>

Confirmado nos 3 locais citados e em mais 4. O mesmo span de spinner inline (border + borderTopColor + borderRadius "50%" + animation "nora-spin 0.9s linear infinite") aparece em 7 lugares: meetings.tsx:341-350, meeting-detail.tsx:495-504, new-meeting-modal.tsx:315-324, overlay.tsx:510 / :723 / :1412, e chat.tsx:92. Variam apenas size (9/10/11/12px), cor da borda/topo e thickness (1.5px/2px). O keyframe nora-spin esta definido uma unica vez em styles.css:55, entao o que esta duplicado e so a marcacao do span — boilerplate de manutencao, nao bug. Nao existe componente Spinner ainda (src/components/ nao tem). Severidade honesta: low (cosmetico/manutenibilidade, sem impacto funcional). O auditor lista overlay.tsx/chat.tsx como "fora da fatia", mas ambos estao dentro de apps/desktop e fazem parte do mesmo refactor.

</details>

### `apps/desktop/src/pages/settings.tsx`  ·  5 achado(s)

#### 73. 🟡 LOW — Cor de perigo hardcoded `#a04c3e` em vez do token CSS `--danger-ink`

- **Local:** `L33-37 (Btn danger) e L35 especificamente`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** O variant `danger` do Btn usa `color: "#a04c3e"` literal. Esse exato hex já está definido como token em styles.css (`--danger-ink: #a04c3e`) e é referenciado via `var(--danger-ink)` em vários outros lugares (overlay.tsx, meetings.tsx, main.tsx, e até no próprio login.tsx L510). Hardcodar o hex duplica a constante de tema e quebra a fonte única de verdade: se a paleta mudar, este botão fica dessincronizado.

**Fix recomendado:** No settings.tsx L35 trocar `color: "#a04c3e",` por `color: "var(--danger-ink)",` (alinha com as linhas 30-32 do mesmo objeto que ja usam var()). Sem risco cross-platform: CSS custom properties funcionam no WebKitGTK e o codebase inteiro ja depende delas. A sugestao secundaria do auditor (login.tsx:65, e por extensao meeting-detail.tsx:55/139) e opcional/discutivel: ali o hex faz parte de objetos de paleta local de pills/badges (`pii: { bg, fg, dot }`, `HIGH: { bg, border, fg }`) onde varias cores literais coexistem — o proprio auditor hedge nesse ponto. Pode-se alinhar com `var(--danger-ink)` por consistencia, mas isso esta fora do file:line primario e nao deve bloquear; o ganho claro e inequivoco e settings.tsx:35.

<details><summary>Justificativa da verificação</summary>

Confirmado em /home/pollo/Dev/nora/apps/desktop/src/pages/settings.tsx:35: o variant `danger` do Btn usa `color: "#a04c3e"` literal. Esse exato hex e o token de tema definido em styles.css:28 (`--danger-ink: #a04c3e`) e ja e referenciado via `var(--danger-ink)` em ~18 locais (main.tsx:37/55, overlay.tsx, meetings.tsx, meeting-detail.tsx, login.tsx:510, dock-bar.tsx, new-meeting-modal.tsx, overlay-notifications.tsx). E uma violacao real de DRY / fonte unica de verdade: o proprio Btn ja usa `var(--canvas)`/`var(--ink)`/`var(--border)` nas linhas 30-32, entao hardcodar o hex so na cor `danger` e inconsistente e faz esse botao silenciosamente sair de sincronia se a paleta mudar. Severidade low: e debito de manutenibilidade/tema, nao bug funcional nem visual (o valor renderizado e identico hoje).

</details>

#### 74. 🟡 LOW — Falhas de stealth mode só vão pro console — sem feedback ao usuário

- **Local:** `L320-334 (useEffect) e L339-347 (handleStealthToggle)`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** Tanto a carga inicial (`get_stealth_mode`) quanto o toggle (`set_stealth_mode`) capturam erros apenas com `console.error`. Se `set_stealth_mode` falhar no Windows, o toggle não muda de estado (porque `setStealthMode(enabled)` só roda após o await bem-sucedido) e o usuário não recebe nenhuma indicação de que a ação falhou — o switch simplesmente não reage, parecendo um bug de UI.

**Fix recomendado:** Adicionar um estado de erro local na PrivacySection e renderiza-lo inline dentro do SectionWrap "Modo stealth" (NAO existe componente de toast no app; o padrao real e inline error via useState, como em login.tsx). Concretamente: const [stealthError, setStealthError] = useState(""); no catch de handleStealthToggle (L344-345) chamar setStealthError("Nao foi possivel alterar o modo stealth.") e no try limpar com setStealthError(""). Renderizar {stealthError && <div style={{ color: "var(--danger)"... }}>{stealthError}</div>} apos o ToggleRow. A falha da carga inicial (L328) e prioridade ainda menor — deixar o toggle no default false ja e fallback aceitavel, mas pode-se setar a mesma mensagem por consistencia. Manter como mensagem inline por secao, ja que nao ha toast compartilhado pra reusar.

<details><summary>Justificativa da verificação</summary>

Codigo confere exatamente. Em settings.tsx, handleStealthToggle (L339-347) so chama setStealthMode(enabled) APOS o await bem-sucedido de invoke("set_stealth_mode"); no catch (L344-345) ha apenas console.error. Logo, se a chamada nativa falhar (Windows-only, SetWindowDisplayAffinity), o switch nao reage e o usuario nao recebe feedback — parece bug de UI. Mesma coisa na carga inicial (L328-329). A afirmacao de inconsistencia com o resto do app procede: login.tsx (L246 useState, L506-518 render inline do erro), meetings.tsx (L374/L397) e meeting-detail.tsx (L195/L222) usam um padrao de estado de erro renderizado na UI; PrivacySection e a excecao que engole tudo no console. Severidade honestamente low: caminho de falha e raro e Windows-only, sem risco de dados/seguranca — e um gap de UX, nao bug de correcao. Fix e seguro: nao toca em nada cross-platform/drag/transparencia, apenas adiciona estado de erro local e renderiza inline.

</details>

#### 75. 🟡 LOW — Botão "Abrir docs" sem onClick — não abre nada

- **Local:** `L529 (Btn "Abrir docs")`
- **Categoria:** gambiarra · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O botão "Abrir docs" na seção Sobre não tem handler. Clicar não abre `docs/adr/` nem URL alguma. Mesmo problema de affordance falsa dos botões do login: parece acionável e é inerte.

**Fix recomendado:** Adicionar handler usando o plugin ja presente no projeto (shell, nao opener): `import { open } from "@tauri-apps/plugin-shell";` e em L529 trocar para `<Btn variant="ghost" onClick={() => open("https://github.com/<org>/nora/tree/main/docs/adr").catch(() => {})}>Abrir docs</Btn>` apontando para a URL do repo (NAO para o caminho local `docs/adr/`, que nao existe num app empacotado). A permissao `shell:allow-open` ja esta em capabilities/default.json, entao nenhuma mudanca de capability eh necessaria. Alternativa valida: remover o botao ate haver destino real. Fix nao toca em nenhum padrao cross-platform de drag/janela — risco safe.

<details><summary>Justificativa da verificação</summary>

Confirmado em apps/desktop/src/pages/settings.tsx:529. `<Btn variant="ghost">Abrir docs</Btn>` nao passa `onClick`. O componente Btn (L16-62) define `onClick?` como opcional e o renderiza no `<button onClick={onClick}>` (L44); sem handler o clique nao faz nada. Contraste com L307 (`<Btn variant="danger" onClick={logout}>`), que comprova que o padrao normal eh passar handler. Logo a afirmacao do auditor de affordance falsa esta correta: botao parece acionavel e eh inerte. Dano concreto: baixo (cosmetico/UX), nao funcional nem de seguranca — por isso severity low esta certa.\n\nAJUSTE no fix proposto: o auditor recomenda `@tauri-apps/plugin-opener`, que NAO esta instalado. O projeto ja usa `@tauri-apps/plugin-shell` (package.json:15 `"@tauri-apps/plugin-shell": "^2"`, Cargo.toml:17 `tauri-plugin-shell = "2"`) e a permissao `shell:allow-open` ja esta concedida em src-tauri/capabilities/default.json:14. Portanto o fix correto usa `import { open } from "@tauri-apps/plugin-shell"`, nao plugin-opener. A observacao do auditor de que nao ha uso de shell/opener em src/pages hoje eh verdadeira, mas o plugin+capability ja estao ligados, entao aplicar eh trivial e seguro. Caveat adicional: `docs/adr/` eh caminho de repo, nao URL/destino abrivel num build empacotado — o destino sensato eh a URL do repositorio GitHub, nao o caminho local.

</details>

#### 76. 🟡 LOW — `isSupported = isWindows` é alias redundante e o estado inicial assume não-Windows

- **Local:** `L320-347 (PrivacySection) e L336-337`
- **Categoria:** professionalism · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** `const isSupported = isWindows;` é uma indireção sem valor — duas variáveis para o mesmo booleano, sugerindo (falsamente) que há mais plataformas suportadas. Além disso, `stealthMode` inicia `false` e `platform` inicia `null`; enquanto a detecção assíncrona não retorna, o toggle aparece desabilitado com tag baseada em `platform === "macos"` que ainda é null — o ramo final do `stealthHelper` ("Detecção de plataforma em andamento…") cobre isso, mas a `tag` mostra "Indisponível" durante o carregamento mesmo em Windows (onde deveria ficar habilitado). Pequeno flash de estado incorreto.

**Fix recomendado:** Manter `isSupported`/`isWindows` como estão (rejeitar parte 1). Para a parte 2, tratar `platform === null` como estado de carregamento e suprimir o tag. Em settings.tsx:387, trocar:

  tag={!isSupported ? (platform === "macos" ? "Em breve" : "Indisponível") : undefined}

por:

  tag={platform === null ? undefined : !isSupported ? (platform === "macos" ? "Em breve" : "Indisponível") : undefined}

Durante o carregamento nenhum tag aparece (consistente com o helper "Detecção em andamento…"). Fix seguro: não toca em padrão cross-platform (drag/resize/transparência), só ajusta a condicional do tag. O toggle já fica `disabled` no carregamento (correto); só o label cosmético é corrigido.

<details><summary>Justificativa da verificação</summary>

Dois sub-achados fundidos num só, com validade diferente.

PARTE 1 (`isSupported = isWindows` alias) — REJEITADA. Em settings.tsx:336-337, `isSupported` é um nome semanticamente útil que desacopla a intenção ("feature suportada nesta plataforma", usada no guard de handleStealthToggle e no `disabled`) do fato cru (qual SO). Não causa bug nem confusão real. A alegação de que "sugere falsamente mais plataformas" é fraca, e o fix sugerido (expandir pra checagem multi-plataforma) ADICIONARIA escopo sem necessidade. Nitpick de estilo subjetivo.

PARTE 2 (flash de "Indisponível" durante o carregamento no Windows) — CONFIRMADA, é o achado real. Tracei o estado durante a janela assíncrona antes de `check_system_audio_prerequisites` resolver: platform=null -> isWindows=false -> isSupported=false. Em settings.tsx:387, `tag={!isSupported ? (platform === "macos" ? "Em breve" : "Indisponível") : undefined}` cai em "Indisponível". Confirmei que `tag` é renderizado como chip visível (ToggleRow L136-151). Resultado: numa máquina Windows, durante a detecção, o usuário vê o chip "Indisponível" CONTRADIZENDO o helper que diz "Detecção de plataforma em andamento…" (L370). Dano concreto: flash de label incorreto numa plataforma onde a feature É suportada. Severidade low honesta — é cosmético, na página de settings (não hot path), some assim que o invoke resolve.

</details>

#### 77. 🟡 LOW — Migração de secrets antigos roda silenciosamente toda vez que a seção Áudio abre

- **Local:** `L422-438 (AudioSection useEffect de migração)`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** AudioSection dispara uma 'migração' que deleta `azure-speech-key` e `azure-region` do keyring em todo mount da seção. Problemas: (1) é um efeito colateral destrutivo escondido dentro de um componente de UI de exibição, não um passo de migração versionado/idempotente-explícito; (2) roda repetidamente (a cada vez que o usuário entra em Áudio), mesmo muito depois das chaves já terem sumido; (3) `secret_has` antes de `secret_delete` é redundante — o `secret_delete` em Rust (secrets.rs L82) já é idempotente, então a checagem `has` só adiciona round-trips IPC; (4) a mensagem "Chaves antigas removidas com sucesso" aparece mesmo quando nunca havia chave alguma, o que é enganoso.

**Fix recomendado:** Como azure-speech-key/azure-region não estão mais em ALLOWED_KEYS (secrets.rs L5-9), TODO este bloco de migração é dead code que nunca consegue limpar nada — apenas gera console.error a cada mount e mostra uma mensagem de sucesso falsa. Duas opções:

OPÇÃO A (recomendada — mais simples, baixo risco): remover por completo o useEffect de migração (settings.tsx L423-438), o estado cleanupDone e o bloco condicional da mensagem (L467-471). Justificativa: a migração já está quebrada há tempo (validate_key bloqueia), então removê-la não regride nenhum comportamento funcional — só elimina o console.error recorrente e a mensagem enganosa. Risco: se ainda existirem instalações antigas com chaves stale no keychain, elas permaneceriam — mas elas JÁ permanecem hoje (o código atual não consegue removê-las), então não há perda real.

OPÇÃO B (se realmente quiser limpar chaves stale de installs antigos): re-adicionar temporariamente "azure-speech-key" e "azure-region" ao ALLOWED_KEYS em secrets.rs para destravar has/delete, e então tornar a migração de bootstrap único e idempotente: rodar uma vez no startup do app (não na seção de UI), gravar um flag em storage local (ex.: localStorage "migrations.azure-keys-removed") e pular se já feito; mostrar a mensagem de "removido" SÓ quando has retornou true antes do delete (usar o retorno do has para decidir o texto, em vez de sempre exibir). Manter o has só faz sentido nesta opção justamente para diferenciar "removi algo" de "nada a remover" — não é redundante para esse propósito (ao contrário do que o auditor disse).

Independentemente da opção, corrigir a mensagem para não afirmar sucesso de remoção quando nada foi removido, e não engolir o erro de migração de forma silenciosa para o usuário sem que isso signifique "sucesso".

<details><summary>Justificativa da verificação</summary>

O código citado existe (settings.tsx L422-438, AudioSection useEffect de migração) e o problema é real, MAS o auditor errou a causa-raiz e isso muda a severidade e o fix. O auditor afirma que a migração "deleta azure-speech-key/azure-region do keyring em todo mount" (efeito destrutivo). Isso é FALSO. Em secrets.rs, validate_key (L24-29) rejeita qualquer chave fora de ALLOWED_KEYS (L5-9 = só access-token, refresh-token, current-user). Verifiquei no git que azure-speech-key/azure-region JÁ estiveram no allowlist mas foram removidos quando o Token Broker substituiu o BYO key. Resultado runtime: secrets.has("azure-speech-key") -> secret_has -> store.has -> self.get -> validate_key -> retorna Err("chave não permitida"). Ou seja, a PRIMEIRA chamada (has) já dá throw, ANTES de qualquer operação de keyring. O try/catch engole o erro, loga console.error e seta cleanupDone(true) mesmo assim. Consequências REAIS: (1) a migração nunca deleta nada — os secrets.delete são código morto inalcançável; se algum usuário tiver chaves stale do install antigo no keychain do SO, este código jamais as limpa (validate_key bloqueia tanto has quanto delete); (2) todo mount da seção Áudio gera um console.error "[settings] secret migration:"; (3) a mensagem "Chaves antigas removidas com sucesso" aparece SEMPRE (cleanupDone é setado nos dois ramos, try e catch), o que é enganoso — confirma a observação do auditor sobre a mensagem. Os pontos do auditor sobre "roda toda vez", "mensagem enganosa" e "efeito colateral escondido em componente de UI" estão corretos. Mas o ponto sobre "has antes de delete é redundante porque delete é idempotente" e o fix "chamar secrets.delete direto sem o has" estão ERRADOS: delete também passa por validate_key e também daria throw, então remover o has não conserta nada. O fix proposto pelo auditor não funciona no estado atual do código.

</details>


## 3. TypeScript — hooks

### `apps/desktop/src/hooks/use-live-highlights.tsx`  ·  3 achado(s)

#### 78. 🔴 HIGH — isAnalyzing do contexto nunca vira true — spinner da overlay está morto

- **Local:** `L36 (isAnalyzing in context type), L56/L91 (only ever set false), L129-183 (useLiveAnalysisTrigger isAnalyzingRef)`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** O LiveHighlightsProvider expõe `isAnalyzing` mas só chama `setIsAnalyzing(false)` (no clear e no handler de 'live-analysis'). NUNCA chama `setIsAnalyzing(true)`. O estado real de 'analisando' vive em `isAnalyzingRef` dentro de `useLiveAnalysisTrigger`, que é um ref desconectado do contexto. overlay.tsx consome `isAnalyzing` em L498/L550/L552 (NoraBars active/animate + texto 'Analisando...') — então o indicador visual de análise ao vivo nunca acende. Feature visivelmente quebrada.

**Fix recomendado:** Usar evento Tauri (cross-webview), nao setter de contexto. 1) Em live_analysis.rs, no comando analyze_live, emitir um evento de inicio logo apos validar auth e antes do client.post (perto da L96 'let start = ...'): `let _ = app_handle.emit("live-analysis-start", ());` 2) Em use-live-highlights.tsx, dentro do useEffect do provider, adicionar attach(listen("live-analysis-start", () => { setIsAnalyzing(true); setError(null); })); 3) Garantir reset em TODOS os finais: o handler de 'live-analysis' ja faz setIsAnalyzing(false) (L91, caminho de sucesso), mas os caminhos de erro do Rust so emitem 'live-analysis-telemetry' com success:false. Portanto o listener de 'live-analysis-telemetry' (L96-98) deve tambem chamar setIsAnalyzing(false) quando event.payload.success === false (ou simplesmente sempre, ja que telemetria de sucesso chega junto com o evento live-analysis). Sem isso o spinner trava em caso de erro do worker. Alternativa mais simples e robusta: dar prioridade a uma unica fonte de verdade no provider derivada de eventos start/end, e eliminar a dependencia do isAnalyzingRef para fins de UI (manter o ref so para dedupe/guard de concorrencia no trigger). Cuidado para nao introduzir 'live-analysis-start' emitido tarde demais (depois do await) — emitir antes do .send() para o spinner aparecer durante a chamada de rede. Risco needs-care: e preciso cobrir os 3 caminhos de saida do Rust e nao quebrar o reset em clearHighlights; nada disso colide com os padroes intencionais cross-platform (apenas emit/listen, que e o canal correto entre janelas).

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. Em use-live-highlights.tsx, setIsAnalyzing so e chamado com false: L64 (clearHighlights) e L91 (handler de 'live-analysis'). Grep no projeto inteiro confirma que setIsAnalyzing(true) nunca e chamado em lugar nenhum. O estado real de analise vive em isAnalyzingRef dentro de useLiveAnalysisTrigger (L130, set true L162, false L179), um ref que (a) nao causa re-render e (b) nem sequer roda na mesma janela. overlay.tsx consome isAnalyzing do contexto em L498 (spinner girando), L550 (NoraBars active/animate) e L552 ('Analisando a conversa...') — todos congelados pois isAnalyzing fica eternamente false. Feature visualmente morta. Severidade high: indicador visivel de analise ao vivo nunca acende. IMPORTANTE — o fix do auditor esta parcialmente errado para esta arquitetura multi-window: a Opcao A (triggerAnalysis chama um setIsAnalyzing(true) exposto pelo provider) NAO funciona, porque useRecording/useLiveAnalysisTrigger rodam na janela 'main' (App.tsx, via useRecordingContext) enquanto o spinner esta na webview separada 'overlay' (overlay-main.tsx tem seu PROPRIO LiveHighlightsProvider). Sao arvores React independentes; chamar um setter de contexto na main nao atualiza o provider da overlay. So a Opcao B (evento Tauri) atravessa webviews. Detalhe adicional que o auditor nao notou: o Rust analyze_live (live_analysis.rs) so emite 'live-analysis' no caminho de sucesso (L146); nos caminhos de erro HTTP (L117) e de falha de request (L170) emite apenas 'live-analysis-telemetry' com success:false. O listener de telemetria no provider (L96-98) so le latencyMs e NAO mexe em isAnalyzing — entao se o fix apenas ligar isAnalyzing=true no inicio, em caso de erro o spinner ficaria preso em true para sempre.

</details>

#### 79. 🟡 LOW — Lógica de dedup por overlap de tokens duplicada entre isDuplicate e mergeTasks

- **Local:** `L208-228 (isDuplicate) e L247-265 (mergeTasks)`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** isDuplicate (highlights) e o callback inline de mergeTasks (L252-261) implementam quase a mesma heurística de similaridade (normalizeText, tokens em Set, overlap >= 0.7). mergeTasks usa Math.min como denominador e não faz o includes/substring check; isDuplicate faz. São duas versões da mesma ideia que podem divergir, e a de tasks é uma cópia parcial/inconsistente (sem o substring-match, com denominador diferente), o que faz tasks e highlights dedupliquem com critérios sutilmente diferentes.

**Fix recomendado:** Extrair uma função única `isSimilar(a: string, b: string, threshold = 0.7, opts?: { substring?: boolean }): boolean` que faça normalizeText, montagem dos Sets de tokens, checagem de igualdade, overlap = intersection / min(sizes) e (opcionalmente) o substring check. Usá-la em isDuplicate (com substring:true) e em mergeTasks (parametrizando o campo title vs text). CAVEAT 1: o achado original justifica o fix com 'denominador diferente', o que é incorreto — denominador e threshold já são iguais; a justificativa correta é apenas unificar o critério e fechar a divergência do substring check. CAVEAT 2: se o refactor LIGAR o substring check para tasks (para unificar 100%), isso aperta levemente a dedup de tasks — é uma mudança de comportamento observável, deliberada, não regressão. Para preservar o comportamento atual byte-a-byte, manter substring como opt-in (substring:true só em isDuplicate). Nenhuma relação com os padrões intencionais cross-platform (drag/background WebKitGTK), então fix é seguro do ponto de vista de plataforma.

<details><summary>Justificativa da verificação</summary>

A duplicação existe de fato: `isDuplicate` (L208-228) e o callback inline de `mergeTasks` (L252-261), ambos em /home/pollo/Dev/nora/apps/desktop/src/hooks/use-live-highlights.tsx, reimplementam a mesma heurística (normalizeText -> tokens em Set -> overlap >= 0.7). Confirmei via grep que tudo é local a este arquivo. PORÉM o auditor erra parte do raciocínio: ele afirma que o DENOMINADOR diverge ('mergeTasks usa Math.min e isDuplicate faz diferente'). Isso é falso. Em isDuplicate, o ternário das L222-224 escolhe o MENOR conjunto como base (newTokens.size <= oldTokens.size ? .../newTokens.size : .../oldTokens.size), o que é matematicamente igual a intersection / min(sizes) — exatamente o `Math.min(newTokens.size, oldTokens.size)` da L259. A contagem de interseção também é a mesma (interseção é simétrica). Ou seja, denominador e threshold são idênticos. A ÚNICA divergência real é o substring check: isDuplicate tem `normNew.includes(normOld) || normOld.includes(normNew)` (L219) e mergeTasks NÃO tem. Logo tasks deduplicam um pouco mais frouxo (não pegam título que é substring de outro, a menos que o overlap de tokens também chegue a 0.7). Impacto: cosmético no feed (possíveis tasks quase-duplicadas), sem crash/segurança/perda de dados — severidade low confirmada. Ambas as versões já guardam set/string vazios corretamente.

</details>

#### 80. 🟡 LOW — Cast duplo `as unknown as` no payload de live-analysis mascara tipo divergente

- **Local:** `L83-93 (listen 'live-analysis')`
- **Categoria:** type-safety · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** L83 declara `listen<LiveHighlights>('live-analysis', ...)` mas o payload real tem shape `{ highlights, chunkSeq }` — não LiveHighlights. Para contornar, L84 faz `event.payload as unknown as { highlights; chunkSeq }`. O parâmetro genérico do listen está mentindo (diz LiveHighlights, recebe wrapper), e o double-cast desliga toda checagem. Se o Rust mudar o shape do evento, o TS não acusa.

**Fix recomendado:** Trocar o generic e remover o double-cast: `listen<{ highlights: LiveHighlights; chunkSeq: number }>("live-analysis", (event) => { const { highlights: newHighlights, chunkSeq: seq } = event.payload; ... })`. Caveats: (1) o payload real tambem traz `processingMillis` (vindo de processing_millis); por structural typing do TS nao e obrigatorio lista-lo ja que o codigo so usa highlights/chunkSeq, mas se quiser refletir 1:1 o struct Rust pode-se incluir `processingMillis: number`. (2) Ideal extrair esse tipo para uma interface nomeada (ex: AnalyzeLiveResponse) co-localizada com LiveAnalysisTelemetry, espelhando o struct Rust, pra documentar o contrato IPC. Risco do fix: nenhum em runtime — e mudanca puramente de tipo, o destructuring e os setters continuam identicos.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. Em /home/pollo/Dev/nora/apps/desktop/src-tauri/src/live_analysis.rs L146-153 o Rust emite o evento "live-analysis" com o struct AnalyzeLiveResponse (L49-54), que tem #[serde(rename_all = "camelCase")] e os campos chunk_seq, highlights, processing_millis -> serializa como { chunkSeq, highlights, processingMillis }. Portanto o payload real e um wrapper, NAO um LiveHighlights. No TS (use-live-highlights.tsx L83) o generic e declarado como listen<LiveHighlights>, ou seja o tipo do generic mente sobre o shape recebido. Para contornar, L84-87 faz `event.payload as unknown as { highlights; chunkSeq }`. O double-cast `as unknown as` desliga toda checagem estrutural: se o Rust renomear/remover `highlights` ou `chunkSeq` (ou mudar tipos), o TS nao acusa nada. Dano concreto: perda de type-safety num boundary IPC Rust<->TS justamente onde divergencias sao silenciosas em runtime. E baixo (nao e bug funcional — hoje os nomes batem), mas e real. Nao confunde com padrao intencional: nao tem nada cross-platform/WebKitGTK aqui, e so um cast mal feito.

</details>

### `apps/desktop/src/hooks/use-active-recording.tsx`  ·  5 achado(s)

#### 81. 🟠 MEDIUM — setTimeout(120ms) mágico para 'esperar o transcript assentar' — race por timing arbitrário

- **Local:** `L122 (await sleep 120), L250 (await sleep 120)`
- **Categoria:** gambiarra · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** Em stopAndSave (L121-122) há `await new Promise((r) => setTimeout(r, 120))` com comentário 'Give the transcript context a tick to settle final partials', e em restart-recording (L250) outro `setTimeout(120)` entre stop e start. Esses 120ms são números mágicos sem garantia: se o último evento 'transcript' final chegar em 130ms, a última linha não entra no transcript salvo. É um sleep arbitrário compensando falta de sincronização real entre o stop do sidecar e o flush do estado React.

**Fix recomendado:** Fix menor, seguro e que de fato resolve a perda do final tardio em stopAndSave: fazer saveMeeting ler o transcript de um ref ao inves do closure. O ref ja existe (use-recording.ts L45-50: transcriptLinesRef sincronizado por effect). Trocar as leituras de `transcriptLines` em saveMeeting (L225 check de length, L235 montagem do transcript) por `transcriptLinesRef.current` e remover transcriptLines das deps do useCallback (L284) — assim o save sempre usa o estado mais recente ja commitado, e o sleep deixa de ser carregado de responsabilidade. Em paralelo, extrair o 120 para uma constante nomeada (ex.: TRANSCRIPT_SETTLE_MS) com comentario explicando a origem. A proposta do auditor (Rust emitir `recording-flushed` apos drenar os parciais finais, com timeout de seguranca) e a correcao deterministica ideal porem tem risco maior: exige drenar os finais no Rust antes de emitir (commands.rs/stt_sidecar.rs), tratar o sinal `stopped` ja existente, e cuidar do timeout — toca o lifecycle do sidecar e IPC cross-platform (WebKitGTK/Linux). Recomendo a abordagem do ref+constante primeiro (baixo risco); o evento deterministico fica como melhoria futura. Para L250 (restart), o sleep e teardown-do-processo, nao flush — tratar separadamente; se quiser determinismo ali, aguardar confirmacao de que o sidecar antigo morreu antes do novo start, mas isso e outro achado, nao o mesmo.

<details><summary>Justificativa da verificação</summary>

Confirmado, com correcao da mecanica. Os dois setTimeout(120) existem (use-active-recording.tsx L122 e L250) e sao numeros magicos sem garantia. O race que o auditor aponta e real e ate pior do que descrito: em stopAndSave (L115-130), `recording.saveMeeting(m.title)` roda sobre a referencia `recording` congelada pelo closure no momento da invocacao; saveMeeting (use-recording.ts L222-284) le `transcriptLines` do PROPRIO closure (uso direto na L235, deps na L284), nao de um ref. Quando um evento `transcript` final chega durante os 120ms, ele dispara addTranscriptLine -> re-render -> um saveMeeting NOVO, mas o stopAndSave ja em execucao continua chamando o saveMeeting ANTIGO com o snapshot ANTIGO de transcriptLines. Ou seja, o sleep nem captura confiavelmente o final tardio — ele depende, na pratica, do final ja ter assentado antes do clique em parar. Do lado Rust, stop_recording (commands.rs L184-205) so envia o oneshot de cancel e retorna sem drenar; o `transcript`/`stopped` final do sidecar (stt_sidecar.rs L488/L493) e assincrono — exatamente a falta de ponto de sincronizacao que o auditor cita. Resultado concreto: a ultima fala pode sumir silenciosamente da reuniao salva (perda de dados na cauda do transcript). AJUSTE: o auditor juntou os dois locais como se tivessem o mesmo proposito, mas servem coisas diferentes — L122 e wait de flush de estado antes de salvar; L250 e delay de teardown do processo do sidecar antes do restart, e startRecording chama clearTranscript() de qualquer jeito, entao L250 NAO tem relacao com salvar finals. Ambos sao magic numbers, mas a justificativa difere.

</details>

#### 82. 🟡 LOW — Lógica de save+emit('nora://save-result')+toggle_overlay duplicada entre stopAndSave e retry-save

- **Local:** `L186-226 (stopAndSave/retry-save) vs L115-154`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O listener 'nora://retry-save' (L192-226) reimplementa quase byte-a-byte o miolo de stopAndSave (L115-154): pega metaRef, valida m, setIsFinishing(true), saveMeeting(m.title), emite save-result ok/erro, fecha overlay no sucesso, setIsFinishing(false) no finally. Dois caminhos divergentes que precisam ser mantidos em sincronia (ex: o fix do emit acima teria que ser aplicado duas vezes). Risco de drift de comportamento entre 'parar e salvar' e 'tentar de novo'.

**Fix recomendado:** Extrair uma função `performSave(m: ActiveMeetingMeta)` que encapsula só o miolo compartilhado: `const result = await recording.saveMeeting(m.title); if (result.ok) { await emit("nora://save-result", { ok: true, meetingId: result.meetingId }).catch(()=>{}); invoke("toggle_overlay", { show: false }).catch(()=>{}); } else { await emit("nora://save-result", { ok: false, error: result.error }).catch(()=>{}); }` — incluindo o try/catch que emite o erro genérico. stopAndSave continua dono do `if (isFinishing) return`, `stopRecording()`, delay de 120ms, e do finally com `setIsFinishing(false)` + `toggle_dock({show:false})`. O listener retry-save continua dono do seu próprio `setIsFinishing(true)`/finally. Ambos chamam `performSave(m)` após validar `metaRef.current`. Caveats: (1) unificar a mensagem de meta ausente (escolher uma das duas) ou manter as duas conscientemente fora da função; (2) NÃO mover `toggle_dock`/`stopRecording`/`isFinishing` pra dentro de performSave, senão muda o comportamento dos dois caminhos. Risco baixo: a extração preserva exatamente o fluxo atual de cada caminho.

<details><summary>Justificativa da verificação</summary>

Confirmei a duplicação. O miolo de salvar está repetido entre stopAndSave (L130-148) e o listener nora://retry-save (L205-220): ambos fazem `recording.saveMeeting(m.title)`, emitem `nora://save-result` ok/erro, fecham overlay via `toggle_overlay({show:false})` no sucesso, tratam catch emitindo erro, e dão `setIsFinishing(false)` no finally. É código quase byte-a-byte que precisa ser mantido em sincronia. A prova de que o drift é real: os dois caminhos JÁ divergiram — (a) mensagem de meta ausente difere ("Sem metadados da reunião — abra uma nova pelo modal." em L126 vs "Sem metadados da reunião." em L199); (b) o finally do stopAndSave faz `toggle_dock({show:false})` (L152) mas o do retry NÃO (L221-223). Ou seja, qualquer correção futura no bloco de save (ex.: tratar o resultado do emit, ajustar payload) teria de ser aplicada duas vezes, e já há inconsistência. Ajusto a severidade de medium para low: é dívida de manutenção/DRY, não um bug ativo (no fluxo real, o retry só ocorre após um stopAndSave que falhou e já escondeu o dock, então a ausência de toggle_dock no retry não causa dano observável hoje). As partes legitimamente divergentes — stopRecording + delay de 120ms, guard `isFinishing`, e o toggle_dock — devem ficar FORA da função compartilhada.

</details>

#### 83. 🟡 LOW — await emit(...).catch(() => {}) — o await é no resultado de .catch, padrão confuso e engole erro

- **Local:** `L124, L132, L140, L148, L197, L207, L213, L220 (await emit(...).catch())`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Repetidamente: `await emit('nora://save-result', {...}).catch(() => {})`. emit() já retorna void/Promise<void>; encadear .catch(()=>{}) silencia qualquer falha de emissão (overlay nunca saberá o resultado do save) e o `await` está sobre o resultado do catch, não do emit, o que é semanticamente confuso. Se emit falhar (canal fechado), o usuário na overlay fica sem feedback de sucesso/erro e nada é logado.

**Fix recomendado:** Substituir o `.catch(() => {})` SOMENTE nos 8 emits de `nora://save-result` por um catch que loga, sem alterar o fluxo (sem re-throw, manter os `return`/control flow atuais):\n\n```ts\n.catch((err) => console.warn("[active-recording] emit save-result falhou", err));\n```\n\nUsar `console.warn`/`console.error` alinhado com a convencao ja existente em L257. NAO converter para `try { await emit } catch` com early-return diferente, pois o emit nessas posicoes nao deve abortar o restante do fluxo (ex.: em stopAndSave o `finally` ainda precisa rodar; em L124/L197 o `return` apos o emit ja e o comportamento desejado). Refatorar em try/catch que engole e re-loga e aceitavel, mas o caminho mais seguro/minimo e so trocar o corpo do `.catch`. Deixar os `.catch(() => {})` de `invoke()` (toggle_dock/toggle_overlay/stop_recording) como estao — sao fire-and-forget intencionais. Severidade low: a melhoria e de diagnosticabilidade, nao corrige um bug que dispara em uso normal.

<details><summary>Justificativa da verificação</summary>

Confirmei as 8 ocorrencias citadas (L124, L132, L140, L148, L197, L207, L213, L220) — todas sao `await emit("nora://save-result", {...}).catch(() => {})`. O nucleo do achado e VALIDO mas a justificativa tem um erro tecnico que precisa de ajuste.\n\nVALIDO (dano concreto): `nora://save-result` e o UNICO canal de feedback do save pro overlay. O unico listener fica em overlay.tsx L1130-1140 e e a unica coisa que chama `setStopping(false)` e seta `saveError`. Nao ha timeout/fallback no overlay. Logo, se o `emit` rejeitar (canal IPC quebrado), o `.catch(() => {})` engole a falha em silencio: o spinner "parando" do overlay fica travado, o usuario nao ve sucesso nem erro, e NADA e logado para diagnostico. Esse e exatamente o cenario que o auditor descreve. O arquivo ja tem a convencao `console.error("[active-recording] ...")` em L257, entao logar dentro do catch e idiomatico.\n\nERRADO (ajuste necessario no titulo/problema): a afirmacao "o `await` esta sobre o resultado do `.catch`, nao do `emit`, semanticamente confuso" e tecnicamente incorreta. `await emit(...).catch(...)` e uma promise chain normal: `.catch()` retorna uma nova promise que resolve quer o emit cumpra quer rejeite, e o `await` efetivamente espera o emit completar (so engolindo o erro). Nao ha bug semantico de "esperar a coisa errada" — apenas erro silenciado. Por isso o veredito e ADJUSTED e a severidade e LOW: nao e bug em operacao normal (emit raramente rejeita), e sim uma lacuna de observabilidade.\n\nFora de escopo de critica: o `.catch(() => {})` em chamadas `invoke()` fire-and-forget (toggle_dock/toggle_overlay, L109/138/152/160/161/249) e aceitavel e deve permanecer — a distincao do auditor (save-result e o UNICO canal) e legitima e correta.

</details>

#### 84. 🟡 LOW — useMemo do value depende do objeto recording inteiro — re-memoiza toda render, anulando o memo

- **Local:** `L263-288 (useMemo value com dep `recording`)`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O value do contexto é useMemo com deps `[recording, meta, isFinishing, start, stopAndSave, cancel]`. `recording` é o objeto retornado por useRecording, que é um objeto literal novo a cada render (não memoizado no return de useRecording, L342-365). Logo o useMemo recalcula em TODA render do provider, e o value muda de identidade sempre — anulando completamente o propósito do useMemo e forçando re-render de todos os consumidores do ActiveRecordingContext a cada tick (duration muda a cada 1s via setDuration).

**Fix recomendado:** Trocar a dep `recording` pelos campos efetivamente lidos no value (L265-280), tornando o memo funcional onde as fontes sao estaveis, sem mexer no hook central. Dep array refinado:\n\n[recording.isRecording, recording.duration, recording.transcriptLines, recording.partialText, recording.deviceName, recording.sampleRate, recording.speakerMap, recording.getSpeakerName, recording.renameSpeaker, recording.devices, recording.selectedDevice, recording.setSelectedDevice, recording.error, recording.isSaving, recording.saveError, recording.savedMeetingId, meta, isFinishing, start, stopAndSave, cancel]\n\nCaveats: (1) e behavior-preserving — apenas listar todos os campos `recording.*` ja melhora vs hoje, pois `getSpeakerName`/`renameSpeaker`/`setSelectedDevice` sao useCallback estaveis e os primitivos so disparam quando realmente mudam; transcriptLines/speakerMap/devices vem do contexto e ja tem identidade propria. (2) Alternativa mais completa (envolver o return de useRecording em useMemo) e mais thorough porem toca um hook central usado em mais lugares — maior risco, nao recomendado pra um achado low. (3) Como `duration` muda a cada 1s, o ganho liquido e modesto; vale como cleanup de overhead inutil, nao como otimizacao critica de performance.

<details><summary>Justificativa da verificação</summary>

Confirmado nos dois locais. use-recording.ts L342-365 retorna um objeto literal novo a cada render (NAO ha useMemo no return), logo `recording` muda de identidade toda render. Em use-active-recording.tsx L263-288 o useMemo do `value` lista `recording` como dep, entao o memo recomputa em TODA render do provider e `value` sempre muda de identidade — o useMemo nao oferece nenhum beneficio (puro overhead). Isso e real.\n\nPorem o auditor exagera o dano ao dizer que "forca re-render de todos os consumidores a cada tick de 1s". Nos renders causados pelo proprio setDuration (1s), `duration` e um valor realmente consumido que mudou, entao os consumidores re-renderizariam de qualquer jeito — o memo nunca poderia evitar esses. O prejuizo concreto e nos renders em que NENHUM valor consumido mudou (ex: re-render de pai, churn de estado nao exposto no value): ai um memo funcional manteria `value` estavel e pularia o re-render dos consumidores, mas o memo quebrado recria `value` e propaga re-render para todos. Logo: defeito real, impacto pequeno, severidade low honesta.

</details>

#### 85. 🟡 LOW — `await emit(...).catch(() => {})` — o catch e awaited, padrao confuso e silencia falha de emit

- **Local:** `L124-127, L132-135, L140-143, L197-200, L207-210, L213-216 (await emit(...).catch(...))`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Varios pontos fazem `await emit('nora://save-result', {...}).catch(() => {})`. Como o .catch() esta encadeado ANTES do await, o que e awaited e a promise ja resolvida pelo catch — entao a falha do emit (canal IPC quebrado) some silenciosamente, e o `await` nao protege contra nada util. Em L148/L220 o mesmo `.catch(() => {})` num caminho de erro engole a unica notificacao de falha que chegaria a overlay (o listener 'nora://save-result' em overlay.tsx L1130 nunca recebe o resultado), deixando o botao 'Salvar' da overlay potencialmente travado em stopping.

**Fix recomendado:** NÃO reestruturar o `await emit(...).catch(...)` — o idiom está correto. O fix mínimo e seguro é parar de engolir o erro silenciosamente: trocar `.catch(() => {})` por `.catch((e) => console.error("[active-recording] emit save-result falhou", e))` nos pontos de emit de save-result (L124-127, L132-135, L140-143, L148, L197-200, L207-210, L213-216, L220). Isso preserva o "best-effort emit" mas dá rastro quando o canal IPC quebrar. Opcionalmente, para o caso de o botão da overlay não travar mesmo se o emit falhar, considerar um fallback de timeout na overlay (resetar `stopping` após N segundos sem `save-result`) — mas isso é melhoria separada, não obrigatória para este achado. Caveat: a tese do auditor sobre a semântica de await/catch é incorreta; o valor real é só o logging.

<details><summary>Justificativa da verificação</summary>

O código citado existe exatamente como descrito (L124-127, L132-135, L140-143, L148, L197-200, L207-210, L213-216, L220). Mas a TESE PRINCIPAL do auditor está ERRADA mecanicamente: `await emit(...).catch(() => {})` parseia como `await (emit(...).catch(() => {}))`. Isso é o idiom JS padrão e idiomático para "aguardar e ignorar erro" — o `.catch()` está corretamente encadeado na promise do emit, e o `await` AINDA serializa o emit antes de prosseguir (não é "promise já resolvida sem sentido"). Não há nada confuso ou quebrado em `await x.catch(handler)`; o conselho do auditor de "não misturar await com .catch" é infundado e não deve ser seguido.

O NÚCLEO ACIONÁVEL, porém, é REAL: o `.catch(() => {})` engole silenciosamente falhas de emit. Confirmei o dano concreto no caminho de erro. Na overlay (overlay.tsx L1252-1261) clicar em Salvar faz `setStopping(true)` e emite `nora://stop-and-save`; o ÚNICO ponto que reseta `stopping` para false é receber `nora://save-result` (overlay.tsx L1133). Se `emit("nora://save-result", {ok:false,...})` no main (use-active-recording L148/L220) falhar, o `.catch(() => {})` engole a falha → a overlay nunca recebe o resultado → botão "Salvar" trava em `stopping` (spinner "Salvando…" eterno, botão disabled). Além disso não há NENHUM console.error, então se quebrar não fica rastro.

Severidade low porque: emit() é IPC local do Tauri, raramente falha na prática; o custo dominante é o diagnóstico perdido, não uma falha frequente. Risco do fix: safe — apenas trocar `() => {}` por logging não muda comportamento nem toca padrões cross-platform (drag/transparência não estão envolvidos aqui).

</details>

### `apps/desktop/src/hooks/use-auth.tsx`  ·  1 achado(s)

#### 86. 🟠 MEDIUM — bootstrapSession().then sem .catch — promise rejeitada deixa loading travado em true

- **Local:** `L26-38 (bootstrapSession effect)`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** L27 `bootstrapSession().then(...)` não tem `.catch`. Se bootstrapSession rejeitar (ex: storage corrompido, erro de IPC Tauri), `setLoading(false)` no .then nunca roda, a UI fica presa na tela de loading para sempre e a rejeição vira unhandledrejection. Os outros hooks da fatia capturam Promises de IPC com .catch; aqui o caminho crítico de auth não.

**Fix recomendado:** Em use-auth.tsx, trocar o bloco do effect de bootstrap por uma forma que SEMPRE libere o loading e nao engula o erro silenciosamente. Ex: `bootstrapSession().then((stored) => { if (stored) { apiClient.setCachedUser(stored); setUser(stored); } }).catch((err) => { console.error("[auth] bootstrapSession failed:", err); }).finally(() => setLoading(false));`. Usar `.finally(() => setLoading(false))` evita duplicar a chamada no .then e no .catch. Importante NAO usar `.catch(() => {})` mudo aqui (como sugerido) — diferente dos invokes best-effort dos outros hooks, este e o caminho critico de auth; logar o erro torna a falha de keyring diagnosticavel. Resultado em falha: loading vira false, user fica null, authenticated false → cai na tela de login em vez de travar. Risco baixo: nao afeta o happy path e nao mexe em comportamento de janela/drag.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. use-auth.tsx:27 faz `bootstrapSession().then(...)` sem `.catch`. bootstrapSession (lib/auth.ts:173-200) comeca com `await secrets.get("access-token")`, e secrets.get (lib/secrets.ts:7-8) e um `invoke("secret_get")` cru — uma Promise de IPC que PODE rejeitar (erro de IPC, ou erro no comando Rust). No Linux/WebKitGTK isso e realista: o keyring depende de um secret service via D-Bus/gnome-keyring; se indisponivel, o comando Rust erra e a Promise rejeita. Se rejeitar, o callback do .then nao roda, entao `setLoading(false)` (L32) nunca executa. loading fica preso em true e App.tsx:68-89 renderiza a tela "Carregando NORA" para sempre, sem recuperacao — dead-end total pro usuario — alem de virar unhandledrejection. A observacao do auditor de que os outros hooks da fatia capturam IPC com .catch confere: use-live-transcript, use-active-recording, use-recording, use-live-highlights e use-tauri-listener TODOS usam .catch em seus invoke(); so o caminho critico de auth nao. Ajusto a severidade de low para medium: e um travamento permanente da UI num modo de falha plausivel (keyring indisponivel), nao cosmetico. NAO toca nenhum padrao intencional cross-platform (drag/startResizeDragging/backgrounds solidos).

</details>

### `apps/desktop/src/hooks/use-recording.ts`  ·  9 achado(s)

#### 87. 🟠 MEDIUM — useEffect do retry-worker depende de loadDevices e pode recriar o setInterval / duplicar uploads

- **Local:** `L286-333 (useEffect deps [loadDevices])`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O effect que cria o `retryInterval` (30s) e o `inFlightIds` Set declara deps `[loadDevices]`. `loadDevices` é estável (useCallback []), então hoje roda uma vez — mas o `inFlightIds` é re-criado a cada execução do effect. Se loadDevices algum dia ganhar deps, o effect re-roda, cria um NOVO Set vazio e um NOVO interval; o cleanup mata o interval antigo mas o guard anti-duplicação (inFlightIds) reseta, reabrindo a janela de upload duplicado que o comentário em L296-300 diz estar prevenindo. O guard deveria viver fora do effect (useRef) pra sobreviver a re-runs. Acoplar o retry-worker ao deps de loadDevices é frágil e sem relação semântica.

**Fix recomendado:** Mover `inFlightIds` para `const inFlightIds = useRef<Set<string>>(new Set())` no corpo do hook, usando `inFlightIds.current` dentro do retry-worker, para o guard sobreviver a re-runs do effect. Separar em dois effects: (a) um para `loadDevices()` + setPendingCount inicial; (b) outro so para o retry-worker com deps `[]` (ele captura apenas funcoes de modulo getPendingMeetings/uploadTranscript/savePendingMeeting/removePendingMeeting e setters estaveis). Caveat importante: o cleanup atual L329-332 tambem faz `clearInterval(timerRef.current)` — esse clear pertence ao timer de gravacao (criado em startRecording L173 e ja limpo em stopRecording L199/L206), NAO ao retry-worker. Ao separar, NAO arraste esse clear para o effect do retry-worker; deixe-o no effect (a) de mount ou remova-o (e redundante com stopRecording). O fix nao toca nenhum padrao Tauri/cross-platform (startDragging, decorations, backgrounds solidos), entao e seguro.

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo em apps/desktop/src/hooks/use-recording.ts. O effect L286-333 realmente declara deps [loadDevices] e cria o `inFlightIds` Set (L301) + `retryInterval` (L302-327) dentro do effect. As afirmacoes tecnicas estao corretas: (1) o retry-worker (semanticamente sem relacao com carregar dispositivos de audio) esta acoplado a `loadDevices`; (2) `inFlightIds` vive dentro do closure do effect, entao se o effect re-rodar, um Set vazio novo e criado. Se uploads disparados pelo interval ANTIGO ainda estiverem in-flight (uploadTranscript tem backoff ~7.5s + timeout reqwest que pode passar de 30s, conforme o proprio comentario L297-298), eles guardam contra o Set ANTIGO, enquanto o interval NOVO usa o Set NOVO — reabrindo exatamente a janela de upload duplicado que o comentario diz prevenir. PORÉM: rebaixei de high para medium porque o bug e LATENTE, nao real hoje. `loadDevices` e useCallback com deps [] (L138-145) e confirmei via grep que nada o torna instavel — logo o effect roda exatamente uma vez no mount, o interval e criado uma vez e o guard funciona como descrito. So vira bug real SE alguem futuramente adicionar deps a `loadDevices`. Isso e fragilidade/manutenibilidade, nao um bug de runtime ativo; "high" (reservado a bug/risco real) nao se justifica.

</details>

#### 88. 🟠 MEDIUM — console.log de cada evento de transcript (e status) deixado em código de gravação quente

- **Local:** `L68, L105, L108, L315 (console.log) e L66-92`
- **Categoria:** professionalism · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** `console.log("[transcript event]", event.payload)` (L68) dispara pra TODO parcial e final de transcrição — durante uma reunião isso é dezenas/centenas de logs por minuto com o conteúdo da fala (potencial PII no console) e custo de serialização. Somado a L105/L108 (status check) e L315 (queued meeting), são logs de debug esquecidos. use-live-highlights.tsx (L147,156,160) tem o mesmo padrão de '[live-trigger]' verboso.

**Fix recomendado:** Prioridade 1 (real): remover use-recording.ts:68 `console.log("[transcript event]", event.payload)` — é o único que vaza fala em hot path. Se debug for desejável, gatear atrás de `if (import.meta.env.DEV)` e logar só metadados não-sensíveis (ex: `{ isFinal: payload.isFinal, track: payload.track, len: payload.text.length }`), nunca o texto. Prioridade 2 (noise): remover ou gatear sob DEV os L105, L108, L315 e use-live-highlights.tsx:147/156/160. Manter intactos todos os `console.error`/`console.warn` de falhas reais (ex: L111, L177, L186, L196, L267, L317). Caveat de escopo: api-client.ts:50/60 também logam path/body e fazem parte do mesmo padrão de debug esquecido — vale incluir na mesma limpeza, mas não estava no escopo do achado original. Como não há helper de logging no projeto, a limpeza direta (remover) é a opção mais simples; introduzir um wrapper de log gateado por DEV seria over-engineering para 10 ocorrências.

<details><summary>Justificativa da verificação</summary>

Confirmado nos locais exatos. use-recording.ts:68 `console.log("[transcript event]", event.payload)` está dentro do callback do listener "transcript", que dispara para TODO parcial e final — Azure Speech emite parciais continuamente (vários por segundo por speaker), então durante uma reunião isso são dezenas/centenas de logs/min com o `payload.text` (fala bruta) no console. Dado o posicionamento de PII do NORA (PII Shield non-negotiable, ADR 0012), deixar transcrição bruta no console é um vazamento de higiene real + custo de serialização em hot path. As outras linhas citadas existem e são debug esquecido, mas de menor impacto: L105/L108 (status check, 1x por mount), L315 (queued meeting, baixa frequência). use-live-highlights.tsx:147/156/160 confirmadas: mesmo padrão "[live-trigger]" verboso que dispara a cada avaliação de trigger (as de skip/short bem frequentes). Não existe convenção `import.meta.env.DEV` no codebase (grep retornou 0), e api-client.ts:50/60 logam method/path/response.body — mesma família de debug esquecido, reforçando o padrão. O fix é seguro: são statements de efeito colateral puro, não alteram fluxo de controle nem comportamento cross-platform; remover/gatear não quebra nada. Severidade medium é honesta por causa do leak de fala no hot path (não high porque não é bug funcional nem o console é exfiltrado externamente por padrão).

</details>

#### 89. 🟡 LOW — Cálculo de pendingCount duplicado 3x inline em vez de usar getPendingCount()

- **Local:** `L278, L293, L314 (vs lib/pending-meetings.ts getPendingCount)`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** `getPendingMeetings().filter((m) => m.status === 'pending').length` está copiado literalmente em L278, L293 e L314. lib/pending-meetings.ts JÁ exporta `getPendingCount()` que faz exatamente isso (e `hasPendingMeetings()`), mas o hook nem importa essas funções. Qualquer mudança na regra de 'o que conta como pendente' (ex: incluir/excluir failed_permanently) precisa ser feita em 4 lugares.

**Fix recomendado:** Adicionar getPendingCount ao import existente em L6 (`import { savePendingMeeting, removePendingMeeting, getPendingMeetings, getPendingCount } from "@/lib/pending-meetings";`) e substituir as tres ocorrencias inline por `setPendingCount(getPendingCount())` em L278, L293 e L314. Manter getPendingMeetings no import — ainda e usado no retry worker (L303). Comportamento byte-identico pois getPendingCount() e exatamente a mesma expressao; zero risco cross-platform (logica TS pura, sem Tauri/WebKitGTK). Opcional: o filter de L303-308 do retry worker NAO deve ser trocado por getPendingCount() porque tambem aplica retryCount<10 e !inFlightIds.has(m.id) — nao e o mesmo predicado.

<details><summary>Justificativa da verificação</summary>

Confirmado. lib/pending-meetings.ts L49-51 ja exporta getPendingCount() == getPendingMeetings().filter((m) => m.status === "pending").length. Em use-recording.ts essa mesma expressao esta copiada literalmente em L278, L293 e L314. O import na L6 traz savePendingMeeting, removePendingMeeting, getPendingMeetings mas NAO getPendingCount. Dano concreto: a regra de "o que conta como pendente" vive em 4 lugares; mudar (ex: incluir failed_permanently ou excluir retryCount alto) exige editar todos. Sem bug de runtime — apenas manutenibilidade, por isso low.

</details>

#### 90. 🟡 LOW — Montagem do transcript com [speaker] prefix duplicada entre saveMeeting e fullTranscript

- **Local:** `L335-340 (fullTranscript) e L235-240 (saveMeeting transcript)`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O bloco `.map((l) => { const speakerName = getSpeakerName(...); return (speakerName ? "[${speakerName}] " : "") + l.text; }).join("\n")` está idêntico em L235-240 (dentro de saveMeeting) e L335-340 (fullTranscript, recalculado a cada render do hook sem memo). Lógica de formatação duplicada e o cálculo de fullTranscript roda em todo render mesmo quando transcriptLines não mudou.

**Fix recomendado:** Extrair helper puro `buildTranscript(lines, getSpeakerName)` (ex: fora do componente ou em lib) que faz o map+join, e usar nos dois pontos (L235-240 e L335-340). Para fullTranscript, envolver em useMemo(() => buildTranscript(transcriptLines, getSpeakerName), [transcriptLines, getSpeakerName]). getSpeakerName já é useCallback estável (dep [speakerMap]), então incluí-lo na dep do useMemo é correto e não causa recomputes extras. Caveat: manter getSpeakerName como parâmetro do helper (ou closure) para que a dep array do useCallback de saveMeeting (L284, que já lista getSpeakerName) permaneça consistente. Fix é behavior-preserving — saída idêntica.

<details><summary>Justificativa da verificação</summary>

Confirmado. O bloco `.map((l) => { const speakerName = getSpeakerName(l.speakerId, l.speaker, l.track); return (speakerName ? `[${speakerName}] ` : "") + l.text; }).join("\n")` é byte-for-byte idêntico em use-recording.ts L235-240 (dentro de saveMeeting) e L335-340 (const fullTranscript). É uma duplicação DRY real: as duas cópias podem divergir se uma só for editada. O claim secundário também procede — fullTranscript (L335) é calculado incondicionalmente no corpo do hook, recomputando a cada render mesmo sem mudança em transcriptLines/getSpeakerName. Severidade low está correta: o output é idêntico nos dois pontos, então NÃO há bug funcional; é manutenção + nit mínimo de perf (um único pass de string concat, barato). Ajusto o verdict pra refletir que a parte de impacto de performance é desprezível, mas a duplicação em si é genuína e acionável.

</details>

#### 91. 🟡 LOW — Reunião pendente vira zumbi após 10 retries — status failed_permanently nunca é setado

- **Local:** `L302-327 (retryInterval) e L3 status type`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O retry-worker filtra `m.retryCount < 10`, então ao atingir 10 falhas a reunião para de ser tentada MAS continua com `status: 'pending'` no localStorage. O tipo PendingMeeting define um estado `failed_permanently` que NUNCA é atribuído em lugar nenhum do código (grep confirma). Resultado: a reunião fica eternamente contada no `pendingCount` (badge no UI), nunca mais é reenviada e o usuário não recebe nenhum feedback de que o envio falhou de vez. Dead-end silencioso + estado morto no tipo.

**Fix recomendado:** No catch do retry-worker (use-recording.ts ~L318-322), calcular o novo retryCount e, ao bater o teto, gravar status terminal em vez de manter "pending":\n\n```ts\n} catch (e) {\n  const nextRetry = meeting.retryCount + 1;\n  console.error("[recording] retry failed for queued meeting:", meeting.id, e);\n  savePendingMeeting({\n    ...meeting,\n    status: nextRetry >= 10 ? "failed_permanently" : "pending",\n    retryCount: nextRetry,\n    lastError: e instanceof Error ? e.message : String(e),\n  });\n  if (nextRetry >= 10) {\n    setPendingCount(getPendingMeetings().filter((m) => m.status === "pending").length);\n  }\n}\n```\n\nCaveats / pontos do plano original que NAO precisam ser feitos:\n- A parte do fix "calcular pendingCount considerando apenas pending" JA esta correta no codigo: todos os 3 calculos de setPendingCount (L278, L293, L314) ja filtram `m.status === "pending"`, e getPendingCount() na lib tambem. Nenhuma mudanca necessaria ali.\n- O filtro do retry (L303-308) ja exclui implicitamente failed_permanently porque exige `status === "pending"`; logo, basta o relabel acima para que a reuniao saia da fila.\n- "Expor para o usuario re-tentar manualmente" e escopo novo (UI) — opcional, nao bloqueante; se quiserem manter minimal, basta o relabel para tirar o estado morto do tipo do limbo. Alternativa valida tambem citada pelo proprio achado: se nao houver intencao de UI, remover failed_permanently/completed do union (mas relabel e melhor para futuro debug/limpeza).\n\nRisco do fix: seguro. So muda o rotulo de uma reuniao que ja estava em dead-end (retryCount>=10); nao reintroduz retries, nao toca em nenhum padrao intencional (startDragging/backgrounds solidos/decorations) nem em logica cross-platform. Recomendo verificar `npm run typecheck` em apps/desktop apos a edicao.

<details><summary>Justificativa da verificação</summary>

Confirmei o nucleo do achado, mas a severidade e a justificativa do auditor estao infladas. FATOS verificados:\n\n1) Retry-worker em use-recording.ts L302-327 filtra `m.status === "pending" && m.retryCount < 10`. No catch (L318-322) ele faz `savePendingMeeting({ ...meeting, retryCount: meeting.retryCount + 1, ... })` SEM nunca mudar o status. Logo, ao bater 10 falhas a reuniao para de ser tentada porem permanece `status: "pending"` no localStorage para sempre. Zumbi real, confirmado.\n\n2) O tipo PendingMeeting (pending-meetings.ts L3) define `"pending" | "failed_permanently" | "completed"`. Grep em todo apps/desktop/src confirma que `failed_permanently` E `completed` aparecem APENAS na definicao do tipo — nunca sao atribuidos em lugar nenhum. Dois estados mortos no union type, nao so um.\n\nONDE O AUDITOR EXAGEROU (por isso "adjusted" + severidade rebaixada de medium para low):\n\n- Ele afirma que a reuniao "fica eternamente contada no pendingCount (badge no UI)" e que "o usuario nao recebe feedback". Mas: (a) `pendingCount` e retornado por useRecording porem NAO e exposto pelo unico consumidor real, o contexto `useActiveRecording` (use-active-recording.tsx nao repassa pendingCount no ActiveRecordingState); (b) grep mostra que `pendingCount`/`getPendingCount`/`hasPendingMeetings` NAO sao renderizados como badge em nenhum componente (dock, overlay, App, new-meeting-modal). Ou seja, hoje NAO existe badge nenhum consumindo isso. O impacto visivel ao usuario e basicamente nulo no momento — o zumbi e invisivel, nao "eternamente exibido". O feedback ao usuario so ocorre no momento do save (setError em saveMeeting), nao depende deste contador.\n\nConclusao: bug logico real (dead-end + estados de tipo nunca usados), mas de impacto pratico baixo porque o contador nem chega na UI. Vale corrigir como higiene/data-correctness, nao como bug de UX urgente.

</details>

#### 92. 🟡 LOW — Dependências de useCallback inconsistentes/erradas geram closures stale

- **Local:** `L190 (startRecording deps), L284 (saveMeeting deps), L206/L210 (deps [])`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** `startRecording` declara deps `[selectedDevice, options]` mas usa também `clearTranscript`, `setRecordingState`, `clearHighlights`, `resetTrigger` (todos do contexto/hook). `stopRecording` (L192-206) usa `setRecordingState`, `clearHighlights`, `resetTrigger` mas declara deps `[]`. `renameSpeaker` (L208-210) usa `contextRenameSpeaker` mas deps `[]`. Como essas funções vêm de useCallback estáveis hoje, funciona por sorte; mas `options` é um objeto literal recriado a cada render do caller (useRecording({language:'pt-BR'}) em use-active-recording L62), então `startRecording` muda de identidade a CADA render, invalidando os useMemo/useCallback downstream que dependem de `recording`. Frágil e mascara a real intenção.

**Fix recomendado:** Tratar como limpeza opcional, nao como bug. Se quiser higienizar deps: incluir os callbacks realmente usados nas arrays (sao estaveis, entao incluir nao muda comportamento) — ex. startRecording: [selectedDevice, options.language, options.captureSystemAudio, options.systemAudioDevice, clearTranscript, setRecordingState, clearHighlights, resetTrigger]; desestruturar campos primitivos de options em vez do objeto inteiro evita a troca de identidade por literal. CAVEAT: o fix de memoizar options no caller NAO elimina re-render churn — o objeto `recording` em si nunca e memoizado (use-recording.ts retorna literal novo todo render), entao o useMemo de `value` (use-active-recording.tsx L263) continua recomputando. Para ganho real de perf seria preciso estabilizar o retorno de useRecording (memoizar o objeto retornado), nao apenas options — mas isso e refactor maior e de risco needs-care (precisa garantir que todos os valores derivados como fullTranscript permanecam corretos). Risco baixo do fix de deps, porem zero impacto funcional; recomendo so fazer junto de um refactor maior se/quando perf virar problema medido.

<details><summary>Justificativa da verificação</summary>

As afirmacoes factuais sao verdadeiras: em use-recording.ts, startRecording (L190) declara deps [selectedDevice, options] mas tambem usa clearTranscript, setRecordingState, clearHighlights, resetTrigger; stopRecording (L206) declara [] usando setRecordingState/clearHighlights/resetTrigger; renameSpeaker (L210) declara [] usando contextRenameSpeaker. E options no caller (use-active-recording.tsx L62 `useRecording({ language: 'pt-BR' })`) e de fato um literal recriado a cada render, logo startRecording troca de identidade todo render.\n\nPOReM o dano concreto alegado nao se sustenta. (1) NAO existe stale closure hoje: setRecordingState/addTranscriptLine/clearTranscript/renameSpeaker (use-recording-context.tsx L28-50) e clearHighlights/resetTrigger/triggerAnalysis (use-live-highlights.tsx L62,135,185) sao TODOS useCallback com deps [] -> referencias estaveis. O proprio auditor admite que funciona. Sem lint enforcado tambem: nao ha .eslintrc nem eslint.config no apps/desktop.\n\n(2) A parte 'mais importante' do achado e causalmente errada. O auditor diz que memoizar options estabilizaria startRecording e evitaria invalidar useMemo/useCallback downstream que dependem de `recording`. Mas o objeto `recording` retornado por useRecording (use-recording.ts L342-365 `return { ... }`) e um objeto literal NOVO a cada render, independentemente de options. O unico downstream relevante e o useMemo de `value` (use-active-recording.tsx L263-288) com deps [recording, meta, isFinishing, start, stopAndSave, cancel] — ele JA recomputa todo render por causa de `recording`, que nunca e memoizado. Nenhum useMemo/useCallback depende especificamente da identidade de startRecording. Logo o fix proposto (memoizar options) nao corrige a churn dominante e nao traz beneficio mensuravel.\n\nResumo: e higiene de codigo real (deps incompletas + literal recriado), mas nao e logic-error medium — nao ha bug runtime e o modelo de dano/eficacia do fix esta incorreto. Rebaixo pra low.

</details>

#### 93. 🟡 LOW — stopRecording sempre força isRecording=false mesmo se stop_recording falhou

- **Local:** `L192-206 (stopRecording)`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** Se `invoke('stop_recording')` rejeitar (L194-198), o erro é só logado/setado em error, e o código segue para L200 `setRecordingState(false, '', 0)` + fecha overlay + limpa highlights. Ou seja: o UI passa a achar que parou, mas o sidecar Rust pode ainda estar gravando (o stop falhou). Estado de UI dessincronizado do estado real do backend, sem nenhuma indicação ao usuário de que a parada falhou de fato.

**Fix recomendado:** Tratar o caminho de falha de forma diferenciada em vez de fazer cleanup cego. Sugestao concreta em stopRecording (L192-206):\n\n1. Capturar se o stop falhou: `let stopFailed = false; try { await invoke('stop_recording'); } catch (e) { stopFailed = true; setError('Nao foi possivel parar a gravacao. Tente novamente.'); }`.\n2. Se stopFailed: NAO chamar setRecordingState(false,...) nem toggle_overlay show:false — manter isRecording=true (estado coerente com o backend que pode seguir gravando) e deixar o overlay aberto com o erro visivel, permitindo retry. O timer pode ser pausado, mas o estado de gravacao deve permanecer truthful.\n3. So no caminho de sucesso fazer o cleanup completo (clearInterval + setRecordingState(false) + toggle_overlay show:false + clearHighlights + clear_live_highlights + resetTrigger).\n\nCAVEATS importantes (por isso fixRisk needs-care):\n- A sugestao do auditor de re-consultar get_recording_status nem sempre ajuda: get_recording_status (commands.rs L207-213) tambem faz state.lock(), entao se a causa do Err foi mutex poisoned ele tambem rejeita. Nao confiar nele como fonte de verdade no caminho de falha.\n- Existe garantia explicita em use-active-recording.tsx (L149-153 finally) de que o botao "never gets stuck"; o fix nao pode reintroduzir um estado em que o usuario fica preso sem conseguir sair. Manter o overlay aberto com botao de retry + opcao de cancelar (cancel ja ignora erro via stopRecording().catch) preserva uma rota de saida.\n- cancel() (L156-162) intencionalmente engole o erro do stop e fecha tudo — isso é aceitavel para um cancelamento e NAO deve ser alterado; o tratamento diferenciado se aplica ao stopAndSave/stop normal, nao ao cancel.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. Em apps/desktop/src/hooks/use-recording.ts L192-206, stopRecording faz try/await invoke('stop_recording'); no catch (L195-198) so loga + setError, e DEPOIS (fora do try/catch) executa incondicionalmente clearInterval + setRecordingState(false,'',0) + toggle_overlay show:false + clearHighlights. Ou seja: se o stop rejeitar, a UI marca "parado" e fecha o overlay enquanto o backend pode seguir gravando, sem feedback acionavel ao usuario. A descricao do auditor esta mecanicamente certa.\n\nPORÉM o dano concreto é bem menor do que sugere a confidence medium. Olhando o lado Rust (apps/desktop/src-tauri/src/commands.rs L184-205), stop_recording so retorna Err quando um mutex lock() esta envenenado (state.lock() L193 ou sidecar_state.lock() L197). O proprio capture.stop() (apps/desktop/src-tauri/src/audio_capture.rs L319-351) SEMPRE retorna Ok(()) — usa `if let Ok(...)` internamente e nunca propaga erro. Logo, em operacao normal stop_recording NUNCA rejeita; o unico gatilho realista é um mutex poisoned (algum thread panicou antes segurando o lock), condicao excepcional. Por isso severity low é honesta, nao medium.\n\nObservei tambem que o `error` setado nao chega ao usuario neste fluxo: use-active-recording.tsx (stopAndSave L115-154 / cancel L156-162) chama stopRecording() e fecha o overlay de qualquer jeito; recording.error é exposto (L277) mas o overlay ja foi fechado por L202. Entao a dessincronizacao "silenciosa" se confirma.

</details>

#### 94. 🟡 LOW — console.log/console.error de debug deixados em produção (incl. payload de transcript bruto)

- **Local:** `L68, L105, L108, L147(impl)-L160, L186, L267, L315, L317`
- **Categoria:** professionalism · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Vários logs de debug permanentes: L68 `console.log('[transcript event]', event.payload)` loga TODO payload de transcrição (texto da reunião, potencialmente PII) no console a cada evento; L105/L108 logam status; '[live-trigger]' em use-live-highlights L147/L156/L160 também. Em produção isso polui o console, vaza conteúdo de reunião nos logs e custa performance num stream de eventos de alta frequência.

**Fix recomendado:** Prioridade: remover o L68 de use-recording.ts (`console.log("[transcript event]", event.payload)`) — e o unico que vaza conteudo bruto da reuniao e roda em stream de alta frequencia. Deletar a linha.

Opcional (limpeza low, mesmo PR): os logs puramente de debug — use-recording.ts L105, L108, L315 e use-live-highlights.tsx L147/L156/L160 — gatear atras de `if (import.meta.env.DEV)` ou remover.

MANTER: os `console.error` em catch blocks (use-recording.ts L111, L143, L183, L186, L196, L267, L288, L317; use-live-highlights L177). Sao logging de erro legitimo que da visibilidade a falhas reais (start/stop/save/retry); remove-los pioraria debuggability sem ganho. A lista do achado os incluiu por engano — o texto do fix nao pede remove-los.

Como nao ha logger compartilhado no projeto, nao vale criar um so por isso; remocao direta do L68 + gating opcional resolve. Fix safe: nao altera comportamento funcional.

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo exato. O achado mistura logs de severidade muito diferente; o nucleo real e UM: use-recording.ts L68 `console.log("[transcript event]", event.payload)` dispara em TODO evento `transcript` (stream de alta frequencia, parciais + finais) e loga `event.payload` inteiro, incluindo `payload.text` = conteudo bruto da reuniao (PII em potencial). Isso vaza transcricao no console do devtools a cada frase falada — contrario ao espirito do non-negotiable de PII redaction do NORA, alem de poluir um caminho quente. Esse merece fix.

Os demais sao bem menos graves do que o titulo sugere: L105 (`status check:`), L108 (`restored recording state`) e L315 (`queued meeting sent: meeting.id`) sao debug noise de baixa frequencia, sem PII (so id/status). use-live-highlights.tsx L147/L156/L160 (`[live-trigger]`) logam apenas metadados (contagens, char lengths), nao texto. Limpeza trivial real, mas low.

A lista de locations e over-broad/imprecisa: L186, L267, L317 sao `console.error` em catch blocks legitimos (start_recording FAILED / save meeting FAILED / retry failed) que dao visibilidade de falhas reais — NAO devem ser removidos (o proprio texto do fix nao pede isso, so cita as linhas). L147 "(impl)" foi atribuido a use-recording, mas la nao ha log; o log [live-trigger] esta em use-live-highlights.tsx.

Verifiquei que nao existe logger util nem gating `import.meta.env` no projeto (41 console.* em src). Severidade honesta: low — fica no console do devtools, sem sink remoto; mas o L68 e o ponto que importa.

</details>

#### 95. 🟡 LOW — startRecording recriado a cada render por dep `options` ser objeto literal novo

- **Local:** `L147-190 (startRecording deps) e L62 de use-active-recording.tsx`
- **Categoria:** tech-debt · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** use-active-recording.tsx L62 chama `useRecording({ language: 'pt-BR' })` com um objeto literal novo a cada render. Em use-recording.ts, startRecording tem deps `[selectedDevice, options]` (L190); como `options` muda de identidade todo render, startRecording (e por cascata o value useMemo da ActiveRecordingProvider) é recriado sempre, anulando a memoização. Hoje é benigno porque os setStates são funcionais, mas é fragilidade real e ESLint exhaustive-deps reclamaria (várias useCallbacks aqui têm deps incompletas: stopRecording L206 e renameSpeaker L210 usam closures externas com `[]`).

**Fix recomendado:** Optional hardening, not a required fix. If the file is being touched: in use-recording.ts L190 replace the `options` object dep with the primitives actually read inside startRecording — `[selectedDevice, options.language, options.captureSystemAudio, options.systemAudioDevice]` — so identity churn from the caller's literal stops recreating the callback. This is safe (values read with `??` defaults; `options` defaults to `{}`). Do NOT memoize at the call site purely for this. Be skeptical of the bundled "complete all the useCallback deps" suggestion: stopRecording/renameSpeaker close over callbacks from useRecordingContext/useLiveHighlights/useLiveAnalysisTrigger (setRecordingState, clearHighlights, resetTrigger, contextRenameSpeaker) — before adding them to deps, verify each is itself stably memoized upstream, otherwise you reintroduce per-render churn or risk re-running effects. Note: none of this fixes the ActiveRecordingProvider `value` useMemo (L263-288), which is rebuilt every render because its `recording` dep is an unmemoized return object — a separate, larger change out of scope here. Given zero current impact, deprioritize unless this file is already being refactored.

<details><summary>Justificativa da verificação</summary>

Core factual claims confirmed: use-active-recording.tsx L62 passes a fresh object literal `useRecording({ language: "pt-BR" })` each render, and use-recording.ts L190 lists `options` as a useCallback dep, so `startRecording` is recreated every render. Also confirmed: stopRecording (L206 deps `[]`) and renameSpeaker (L210 deps `[]`) have incomplete deps (they close over setRecordingState/clearHighlights/resetTrigger and contextRenameSpeaker respectively). HOWEVER two parts of the finding are wrong/overstated. (1) The claimed cascade — that startRecording instability "nullifies the value useMemo" of ActiveRecordingProvider — is misleading: that useMemo depends on `recording` (L287), which is a brand-new object literal on EVERY useRecording call (return at L342-365). So `value` is rebuilt every render regardless of startRecording's stability; stabilizing startRecording would NOT fix it. To fix the memo you'd have to memoize the entire return object, which the finding doesn't propose. (2) The "ESLint exhaustive-deps reclamaria" justification is invalid — the desktop app has NO eslint config, no eslint devDependency, and no lint script (package.json scripts are only dev/build/preview/tauri/tauri:linux); the inline `eslint-disable-next-line` at L125 is dead template residue. Concrete harm today: none, which the finding itself admits ("hoje é benigno"). All three callbacks are either invoked imperatively (button click / Tauri listener) or read through the always-fresh `recording` object; none are used as effect deps that would loop, re-subscribe, or yield stale state. So this is a real-but-minor fragility/code-quality note, not a bug — severity low is correct, but the impact framing and the lint argument are not.

</details>

### `apps/desktop/src/hooks/use-live-transcript.tsx`  ·  2 achado(s)

#### 96. 🟡 LOW — Estado `tick` incrementado só para forçar re-render do timer, com `void tick` para calar o lint

- **Local:** `L163-173 (tick state + void tick)`
- **Categoria:** gambiarra · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** L163 cria `const [tick, setTick] = useState(0)`, L166 incrementa a cada 500ms só pra forçar re-render, e a duration (L170-171) é recalculada de Date.now() ignorando tick; L173 `void tick` existe só pra enganar o eslint de variável não usada. É um hack: o estado existe apenas como efeito colateral de re-render e a duração real vem de outro lugar. Confuso de ler e o comentário 'eslint silenciador' admite a gambiarra.

**Fix recomendado:** Nao seguir a sugestao do auditor de "armazenar now no state / criar durationSec" — isso adiciona complexidade e diverge do app. Em vez disso, alinhar ao padrao ja usado em dock-bar.tsx (L64): descartar o valor do state via destructuring e manter so o setter. Ou seja: trocar `const [tick, setTick] = useState(0)` por `const [, forceTick] = useState(0)`, trocar `setTick((t) => t + 1)` por `forceTick((t) => t + 1)` na L166, e deletar L172-173 inteiras (o comentario + `void tick`). Sem variavel `tick` ligada, o eslint nao reclama e o `void` some. Comportamento identico (re-render a cada 500ms recalcula `duration` de Date.now()), zero risco. Opcional: renomear o comentario L162 nao e necessario. Risco safe — destructuring que ignora o valor e identico a ignora-lo com `void`.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo exato. apps/desktop/src/hooks/use-live-transcript.tsx L163 cria `const [tick, setTick] = useState(0)`; L166 incrementa a cada 500ms; L170-171 calcula `duration` direto de `Date.now() - state.startedAt`, sem nunca ler `tick`; L172-173 traz `void tick` com o comentario literal "(eslint silenciador)" admitindo a gambiarra. O valor `tick` so existe pra calar o lint de variavel nao usada — o re-render real e o efeito colateral do setState. Funcionalmente NAO e bug (incrementar state forca re-render, que recalcula Date.now()), por isso severity low: e ruido de legibilidade, nao defeito. O proprio comentario confessa o hack. Importante: o codebase JA tem o padrao limpo logo ao lado — dock-bar.tsx L64 faz `const [, forceTick] = useState(0)`, descartando o valor por destructuring e usando so o setter, exatamente pro mesmo timer de duracao (L90-97). Ou seja, o hook diverge de uma convencao ja existente no mesmo app.

</details>

#### 97. 🟡 LOW — Fallback 'mic' aplicado ao parcial mas não à linha final — inconsistência de track

- **Local:** `L72 (const track = payload.track || 'mic') vs L77 (line.track: payload.track)`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** L72 define `const track = payload.track || 'mic'` e usa esse fallback para a key dos partials (L85, L92). Mas na linha final (L77) grava `track: payload.track` SEM o fallback. Se um evento final vier com track vazio/undefined, o parcial foi limpo na key 'mic' mas a linha final fica com track '' — divergência que o getSpeakerName (use-recording.ts L212) e o agrupamento por track na UI podem interpretar errado (ex: bolha não some, ou aparece no lado errado, justamente o bug que o comentário em L23-28 diz ter consertado).

**Fix recomendado:** Normalizar o track uma unica vez no topo do handler e reusar a variavel em TODOS os pontos. Em use-live-transcript.tsx, trocar L79 `track: payload.track,` por `track,` (a variavel `track` ja existe desde L72 e contem o fallback). Isso elimina a assimetria — se o fallback existe para a key dos partials, deve valer tambem para a linha final, senao o codigo defensivo fica meio-aplicado e sem efeito. Caveats: (1) e cosmetico/hardening, nao corrige bug observavel, porque o emissor Rust garante track sempre "mic"/"system"; (2) opcionalmente, a alternativa mais limpa seria REMOVER o fallback `|| "mic"` de vez (ja que e dead-defensive), mas mante-lo e padroniza-lo nas duas branches e mais conservador e nao muda comportamento; (3) fix e safe — nao toca em logica cross-platform, drag, transparencia nem nada dos padroes intencionais.

<details><summary>Justificativa da verificação</summary>

A inconsistencia textual e real: em apps/desktop/src/hooks/use-live-transcript.tsx, L72 define `const track = payload.track || "mic"` (com fallback) e usa essa variavel como key dos partials (L85: limpeza, L92: gravacao). Mas a linha final em L79 grava `track: payload.track` SEM o fallback. Porem o DANO CONCRETO descrito pelo auditor (bolha nao some / aparece no lado errado) NAO se materializa no fluxo real: o unico emissor do evento "transcript" e stt_sidecar.rs:488, que sempre seta `track: track_label.clone()`. O campo `track_label` e `String` (nao Option) e e sempre alimentado por literais nao-vazios — "mic" (commands.rs:79) ou "system" (commands.rs:93). Logo `payload.track` nunca chega vazio/undefined num evento final, o fallback do L72 nunca dispara na pratica, e a divergencia nunca afeta getSpeakerName (use-recording.ts:212-214, que so checa `track === "mic"`) nem o agrupamento na UI. E uma assimetria de codigo defensivo (fallback aplicado so na metade do handler), nao um bug ativo. Severidade low confirmada; rebaixo o veredito de bug logico ativo para inconsistencia de consistencia/qualidade.

</details>


## 3. TypeScript — lib

### `apps/desktop/src/lib/api-client.ts`  ·  3 achado(s)

#### 98. 🔴 HIGH — console.log do response.body vaza accessToken/refreshToken em texto puro no console da webview

- **Local:** `L60`
- **Categoria:** security · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** `console.log("[api] response:", response.status, response.body)` roda para TODA request. Em /auth/login e /auth/refresh o body contem accessToken e refreshToken em plaintext. Isso despeja JWTs e refresh tokens no console do WebKitGTK (e em qualquer log capturado/devtools), o que contradiz o cuidado de guardar tokens no keyring via secrets.set. Tambem loga bodies de /meetings (titulos, snippets, participantes — potencial PII).

**Fix recomendado:** Minimo seguro: trocar L60 por `console.log("[api] response:", response.status)` — loga so o status, nunca o body. Isso elimina o vazamento de tokens e de PII de uma vez, sem precisar de logica de redacao. Se quiser inspecionar body em desenvolvimento, gate atras de DEV: `if (import.meta.env.DEV) console.debug("[api] body:", response.body)` — mas atencao: gatear em DEV NAO redige tokens; durante dev local os JWTs ainda aparecem no console, o que pode ser aceitavel ou nao dependendo da politica. A sugestao do auditor de redigir accessToken/refreshToken por campo precisa de guard porque response.body e tipado `unknown` (nao da pra assumir que e objeto); por isso a opcao mais limpa e robusta e simplesmente nao logar o body (so status). Deixar o L50 `[api] invoking` como esta (benigno) ou rebaixar pra console.debug se quiser menos ruido — opcional, fora do escopo de seguranca.

<details><summary>Justificativa da verificação</summary>

Confirmado em api-client.ts:60 — `console.log("[api] response:", response.status, response.body)` roda incondicionalmente em TODA request. O body completo e logado. Tracei a cadeia: auth.ts:47 (login -> LoginResponse) e auth.ts:97 (refreshAccessToken -> RefreshResponse) ambos passam por apiClient.request, e types.ts:6-23 confirma que LoginResponse e RefreshResponse carregam `accessToken: string` e `refreshToken: string`. Logo, cada login/refresh despeja o JWT e o refresh token em plaintext no console da webview (WebKitGTK), capturavel via devtools ou qualquer log stream. Isso contradiz diretamente a postura de seguranca do MESMO codigo: auth.ts:63-64 guarda esses tokens no keyring do SO via secrets.set, e auth.ts:84 ate comenta o perigo de reuse do refresh token. Dano concreto: refresh token e long-lived e da acesso persistente a sessao. Secundariamente, bodies de /meetings (titulos/participantes) sao PII. O finding e real e acionavel. O log de [api] invoking (L50) e benigno (so method+path, sem secrets). O fix nao tem risco: console.log e output de debug puro sem efeito em control flow, e nao toca nos padroes intencionais de drag/transparencia cross-platform.

</details>

#### 99. 🟠 MEDIUM — console.log de response.body vaza access/refresh tokens no console

- **Local:** `L60 (console.log) e L50`
- **Categoria:** security · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** `console.log("[api] response:", response.status, response.body)` loga o corpo bruto de TODA resposta do http_proxy. As respostas de /auth/login e /auth/refresh contêm accessToken e refreshToken (LoginResponse/RefreshResponse em types.ts L6-26). Em build de produção esses tokens ficam impressos no console do webview (DevTools, e potencialmente em logs capturados), facilitando exfiltração de credenciais de sessão. L50 também loga método+path de toda request (ruído + superfície de recon).

**Fix recomendado:** Remover o `console.log` de api-client.ts:60 que imprime `response.body`. Se quiser manter observabilidade de status, logar apenas `response.status` (sem o body), e idealmente gatear atras de `import.meta.env.DEV`. O log de L50 (method+path) pode ser mantido se gateado em DEV, ou removido — nao e o vazamento. Como nao existe padrao de DEV-gating no codebase ainda, a opcao mais simples e segura e remover o body do log de L60 (ex: `console.log("[api] response:", response.status)`). Fix e seguro: esses console.* sao puramente observabilidade, nao afetam logica de retry/refresh nem nenhum padrao cross-platform (drag/transparencia). auth.ts:119 nao precisa de mudanca (nao loga valor de token).

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo: api-client.ts:60 `console.log("[api] response:", response.status, response.body)` loga o corpo bruto de TODA resposta do http_proxy. Verifiquei que /auth/login (auth.ts:47) e /auth/refresh (auth.ts:97) passam por apiClient.request, e que LoginResponse/RefreshResponse (types.ts:6-8 e 21-23) contem accessToken e refreshToken como strings em plaintext. Logo esses tokens sao impressos no console do webview. vite.config.ts nao tem drop_console/terser minify, entao os console.* sobrevivem no build. O vazamento e real e acionavel.

Ajustes ao achado: (1) Severidade rebaixada de high para medium. Nao ha feature `devtools` habilitada em src-tauri/Cargo.toml, e o Tauri 2 desabilita o inspector em release por padrao — entao em build de release endurecido a exfiltracao via DevTools nao e trivialmente acessivel. O risco real e: builds de dev/debug compartilhados, captura por logging do WebKitGTK, e higiene de segredos (nunca escrever credenciais em log). E hardening/defense-in-depth, nao uma vuln remotamente acionavel — por isso medium, nao high. (2) L50 loga apenas method+path (sem body, sem token) — e ruido/recon, nao vazamento de token; a propria descricao do auditor ja separa isso. (3) auth.ts:119 e de fato inocuo (so status, sem valor de token) — o auditor concordou. O nucleo do achado (L60 vaza tokens) procede.

</details>

#### 100. 🟡 LOW — getCachedUser exportado mas nunca consumido (cachedUser e write-only)

- **Local:** `L96-98 (getCachedUser)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** setCachedUser e chamado em varios lugares (auth.ts, use-auth.tsx) mas getCachedUser nao tem nenhum caller em todo o src/ (grep confirma). O campo cachedUser e populado e nunca lido — estado morto. Mantem complexidade e da falsa impressao de uma fonte de verdade de user no api-client.

**Fix recomendado:** Remocao minima e segura (nao toca em nada de drag/janela/cross-platform): apagar getCachedUser (L96-98), o campo private cachedUser (L28) e as 6 chamadas setCachedUser. Onde havia setCachedUser(user) em auth.ts/use-auth.tsx, simplesmente remover a linha — os callers ja persistem/leem o user via secrets ("current-user") e via estado do use-auth, entao nada quebra. CAVEAT: se houver intencao futura de o api-client anexar tenant/user as requests (ou servir de cache central), a alternativa e PASSAR A USAR getCachedUser de fato em request() em vez de remover; mas isso e escopo novo, nao deve ser feito agora. Como o achado e low/dead-code, a recomendacao e a remocao. Se preferir esforco zero, manter o campo tambem e inocuo (sem risco), mas ai continua sendo peso morto.

<details><summary>Justificativa da verificação</summary>

Confirmado por leitura direta + grep no app inteiro (apps/desktop, .ts/.tsx, sem node_modules). getCachedUser<T>() existe em api-client.ts L96-98 e e exposto pelo singleton apiClient, mas NAO tem nenhum caller — a unica ocorrencia de "getCachedUser" e a propria definicao. Em contraste, setCachedUser e chamado em 6 lugares (auth.ts L66/L78/L169/L197 e use-auth.tsx L29/L41). Logo o campo privado cachedUser (L28) e write-only: populado e nunca lido. E estado morto real: nao tem efeito em runtime nem em seguranca, mas carrega complexidade e sugere falsamente que o api-client e uma fonte de verdade do user (quando a fonte real e o secrets store "current-user" + o estado do use-auth). Nao e nitpick de estilo nem esta correto como esta — e dead code legitimo. Severidade low e honesta (sem bug/risco, so manutencao).

</details>

### `apps/desktop/src/lib/meetings.ts`  ·  1 achado(s)

#### 101. 🟠 MEDIUM — isTransient trata TODO erro de string/Error como transitorio — retry inutil em falhas permanentes (4xx)

- **Local:** `L60-69, L98-100 (isTransient + uploadTranscript catch)`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** uploadTranscript chama o comando Rust upload_meeting via invoke(). Esse comando retorna seu erro como String (commands.rs:298: Err(format!("Upload failed ({}): {}", status, body_text))), inclusive para 400/401/403. Em isTransient, `if (typeof err === "string") return true` faz QUALQUER erro stringificado contar como transitorio. O ramo `status >= 500 && status < 600` (L66) e codigo morto na pratica: o erro nunca chega como objeto com `.status` por esse caminho. Resultado: uploads com payload invalido, sem auth ou rejeitados por regra de negocio sao re-tentados maxRetries(3) vezes com backoff (~3.5s desperdicados) antes de falhar, e ainda sao enfileirados pelo retry worker (use-recording.ts) que re-tenta por ate 10 ciclos de 30s. Spam de requests garantidamente-falhas no backend.

**Fix recomendado:** Opcao A (minima, isolada em meetings.ts): parsear status da string do Rust e classificar com cuidado preservando erros de rede:

function isTransient(err: unknown): boolean {
  if (err == null) return false;
  if (typeof err === "object") {
    const status = (err as { status?: number }).status;
    if (typeof status === "number") return status >= 500 && status < 600;
  }
  const msg = err instanceof Error ? err.message : typeof err === "string" ? err : "";
  // 4xx/5xx vindos do Rust como "Upload failed (NNN ...): ..."
  const m = msg.match(/\((\d{3})/);
  if (m) {
    const status = Number(m[1]);
    return status >= 500 && status < 600; // so 5xx e transitorio
  }
  // Sem status -> erro de rede/timeout do reqwest ("Upload request failed: ..."): transitorio.
  // Mas "Not authenticated..." NAO deveria re-tentar; trate-o explicitamente:
  if (/not authenticated/i.test(msg)) return false;
  return /upload request failed|failed to read response body/i.test(msg);
}

Opcao B (mais robusta, recomendada a medio prazo): mudar upload_meeting (commands.rs:297-298 e os map_err) para retornar erro estruturado {status, message} (ex: serializando um enum/struct com #[derive(Serialize)] em vez de String), e isTransient checar status==null (rede) || (status>=500 && status<600). Elimina o parsing fragil acoplado ao format string.

Em ambos os casos, remover o `if (typeof err === "string") return true` incondicional (L62) e o `if (err instanceof Error) return true` incondicional (L63), que sao a causa raiz. Caveat: a Opcao A acopla JS ao texto exato dos format! do Rust — se alguem mudar a msg, quebra silenciosamente; documente isso com comentario. Sem testes existentes cobrindo isTransient/uploadTranscript, entao adicionar testes unitarios cobrindo 400/401/500/erro-de-rede ao aplicar o fix.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. commands.rs:298 retorna Err(format!("Upload failed ({}): {}", status, body_text)) como String para QUALQUER status nao-sucesso, incluindo 400/401/403. Tauri serializa Err(String) como rejeicao com `err` sendo uma string crua. Em meetings.ts:62, `if (typeof err === "string") return true` classifica TODO erro do backend como transitorio. O ramo de objeto com `.status` (L64-67) e codigo morto na pratica para o caminho invoke() — o erro nunca chega como {status:number}. Consequencia real: uploads com 4xx (payload invalido, sem auth, regra de negocio) sao re-tentados ate maxRetries(3) com backoff (~3.5s desperdicados em L103/109), depois enfileirados em use-recording.ts:302-327 e re-tentados a cada 30s ate retryCount<10 (L306). Spam de requests garantidamente-falhas. Achado e legitimo.

Ajusto severidade de high para medium: o dano e desperdicio (latencia + requests inuteis), nao corrupcao de dados nem brecha de seguranca; o worker de retry tem cap retryCount<10 (L306) entao auto-termina, e o usuario recebe feedback ("reuniao armazenada localmente"). Volume e modesto (um meeting por vez). E debito real, mas auto-limitado.

Ajusto tambem o FIX proposto: o regex /\((\d{3})\)/ sobre a msg tem uma falha — alguns erros do Rust sao strings SEM status code e que DEVEM ser transitorios: "Upload request failed: <network>" (commands.rs:291, erro de rede real). Um regex que so trata como transitorio quando casa um 5xx descartaria erros de rede genuinos (sem match -> permanente), regredindo retries legitimos. O fix precisa preservar retry de erros de rede.

</details>

### `apps/desktop/src/lib/auth.ts`  ·  4 achado(s)

#### 102. 🟡 LOW — isAuthenticated exportado mas nunca chamado

- **Local:** `L202-213 (isAuthenticated)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** isAuthenticated() nao tem nenhum caller em todo o src/ (grep confirma). A logica de sessao real e feita por bootstrapSession() no AuthProvider. Funcao orfa que duplica parte do fluxo de refresh/logout e pode divergir do comportamento real com o tempo.

**Fix recomendado:** Remover a funcao isAuthenticated() inteira (L202-213 de apps/desktop/src/lib/auth.ts). Remover um export sem nenhum import nao tem impacto em runtime nem em tree-shaking. Caveat unico: se houver intencao de expor um check booleano leve no futuro, mantenha-a delegando a bootstrapSession() (ex: return (await bootstrapSession()) !== null) em vez de reimplementar o fluxo de refresh/logout, evitando a divergencia que motiva o achado. Como nao ha caller hoje, remover e a opcao preferida.

<details><summary>Justificativa da verificação</summary>

Confirmado em apps/desktop/src/lib/auth.ts L202-213. isAuthenticated() e exportado mas nao tem nenhum caller: grep em todo apps/desktop mostra a unica ocorrencia na propria definicao (L202). Os imports reais de lib/auth sao bootstrapSession/logout/stopTokenRefreshLoop (use-auth.tsx), login (login.tsx) e refreshAccessToken/logout (api-client.ts). Nao ha export * que pudesse esconder uso transitivo. A logica de sessao real e bootstrapSession() (L173-200), consumida pelo AuthProvider; isAuthenticated duplica um subset do fluxo refresh-on-expiry + logout (L205-211 espelha L177-184), entao e codigo morto que pode divergir do comportamento real ao longo do tempo. Nao e nitpick: e uma funcao publica completa sem consumidor algum, nao apenas um import/var nao usado.

</details>

#### 103. 🟡 LOW — window.dispatchEvent/CustomEvent('auth-expired') quebra o padrao de comunicacao entre janelas (deveria ser Tauri emit/listen)

- **Local:** `L170 (handleAuthExpired) + L23-26 (parseJwtRoles)`
- **Categoria:** gambiarra · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** O brief declara: comunicacao entre janelas via Tauri emit()/listen(), NAO window.dispatchEvent. handleAuthExpired usa window.dispatchEvent(new CustomEvent('auth-expired')) e use-auth.tsx escuta com window.addEventListener. Como o loop de refresh roda por janela e o evento e DOM-local, a janela main pode expirar a sessao e derrubar so a si mesma — overlay/dock continuam com cache de user stale e tokens ja deletados do keyring, levando a 401s silenciosos nessas janelas ate proxima request. Inconsistente com o resto do app que usa emit/listen.

**Fix recomendado:** Trocar o window.dispatchEvent por emit("auth-expired") (de @tauri-apps/api/event) em auth.ts:170 e o window.addEventListener por listen("auth-expired", handler) em use-auth.tsx, registrando via o helper useTauriListener (ou listen+unlisten manual no cleanup do useEffect) pra evitar leak. Manter a limpeza de keyring em handleAuthExpired exatamente como esta (ela ja roda independente do evento). Caveats: (1) com emit o handler global pode disparar em qualquer janela que tenha o listener — como overlay/dock nao montam AuthProvider, nao havera listener la, entao a linha window.location.hash="#/login" continua sendo um efeito so-da-main e nao quebra nada; manter esse redirect dentro do handler do AuthProvider (main) e ok. (2) Garantir unlisten no return do useEffect (o handler atual ja faz removeEventListener; o equivalente Tauri precisa do unlisten()). Beneficio real e baixo (alinhar ao padrao e cobrir o caso futuro em que overlay/dock passem a fazer requests autenticadas), entao tratar como cleanup de consistencia, nao como hotfix urgente.

<details><summary>Justificativa da verificação</summary>

Confirmado o codigo: auth.ts:170 usa window.dispatchEvent(new CustomEvent("auth-expired")) e use-auth.tsx:62 escuta via window.addEventListener — evento DOM-local, fora do padrao Tauri emit/listen que o resto do app usa (dock-bar.tsx, overlay.tsx, use-active-recording.tsx, use-live-* todos usam emit/listen de @tauri-apps/api/event). Tambem confirmado que o loop de refresh (handleAuthExpired) so roda na janela main: AuthProvider/bootstrapSession/startTokenRefreshLoop estao SO em App.tsx; overlay-main.tsx e dock-main.tsx nao montam AuthProvider nem chamam useAuth. O keyring (secrets) e estado OS-compartilhado e http_proxy.rs:90 le 'access-token' dele, entao quando main deleta os tokens em handleAuthExpired eles somem pra todas as janelas — porem o CustomEvent fica preso na main. Ate aqui a inconsistencia de padrao e real. PORÉM o auditor SUPERESTIMOU o dano: (1) overlay/dock NAO usam useAuth, entao nao existe "cache de user stale" em React nessas janelas pra ficar dessincronizado; (2) durante seu ciclo de vida overlay/dock nao fazem requests autenticadas via apiClient — overlay so emite eventos nora:// e invoca comandos de window-management (toggle_dock/toggle_overlay/focus_*); os consumidores reais do apiClient (meetings.ts via use-recording/meetings.tsx/meeting-detail.tsx) vivem na main. Logo o cenario de "401 silencioso na overlay/dock" e em grande parte teorico no codigo atual. E uma inconsistencia de padrao com risco latente, nao um bug ativo — por isso rebaixo de medium pra low.

</details>

#### 104. 🟡 LOW — setCachedUser chamado em dobro: dentro de bootstrapSession e de novo no AuthProvider

- **Local:** `L173-200 (bootstrapSession) + use-auth.tsx L27-31`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** bootstrapSession (L197) ja faz apiClient.setCachedUser(user) antes de retornar. O AuthProvider (use-auth.tsx:29) chama apiClient.setCachedUser(stored) de novo com o mesmo valor. Redundante e sintoma de responsabilidade difusa sobre quem possui o cache (agravado pelo fato de getCachedUser nunca ser lido — ver achado correlato).

**Fix recomendado:** Nao "deduplicar a chamada" (no-op cosmetico). O fix acionavel e remover o cache morto por inteiro, ja que getCachedUser nunca e lido: deletar o campo `private cachedUser: unknown = null;` (api-client.ts:28), os metodos setCachedUser/getCachedUser (api-client.ts:92-98), e as 6 chamadas a apiClient.setCachedUser (auth.ts:66, 78, 169, 197 e use-auth.tsx:29, 41). Caveat: confirmar via grep que nada fora de src (ex: outro pacote ou teste) importa getCachedUser antes de remover — o grep em apps/desktop/src confirma zero leitores hoje. SE por algum motivo o cache deve ser mantido (ex: leitura sincrona planejada do user fora do React tree), entao manter UM dono: bootstrapSession ja seta (auth.ts:197), entao remover a chamada redundante em use-auth.tsx:29 (deixar so o setUser(stored)). Ambas opcoes sao safe: a linha 29 e provadamente um no-op porque o mesmo objeto ja foi setado em 197 no mesmo fluxo.

<details><summary>Justificativa da verificação</summary>

Os fatos batem: bootstrapSession (apps/desktop/src/lib/auth.ts:197) chama apiClient.setCachedUser(user) antes de retornar, e o AuthProvider (apps/desktop/src/hooks/use-auth.tsx:29 — a location citada como use-auth.tsx:27-31 estava no caminho errado, o arquivo real e src/hooks/use-auth.tsx) chama apiClient.setCachedUser(stored) de novo com exatamente o mesmo objeto retornado por bootstrapSession. Logo, no bootstrap o cache e escrito duas vezes com o mesmo valor. PORÉM, isolado, isso e inofensivo: setCachedUser e apenas `this.cachedUser = user` (api-client.ts:92-94), uma atribuicao idempotente sem side-effects, listeners ou custo — chamar duas vezes com o mesmo valor e indistinguivel de chamar uma. Nao ha race, estado stale, nem comportamento observavel. Enquadrar "setCachedUser chamado em dobro" como item de tech-debt acionavel proprio e enganoso. O problema REAL e o correlato (que eu confirmei): getCachedUser (api-client.ts:96-98) e definido mas NUNCA lido em lugar nenhum do src — o campo cachedUser inteiro e estado write-only morto, escrito em 6 lugares (login, logout, bootstrapSession, handleAuthExpired, use-auth handleLogin/effect) e jamais consumido. A "chamada em dobro" e so um sintoma cosmetico disso. Por isso: ajustado — o achado como titulado e nitpick sem dano; o acionavel e remover o cache morto inteiro.

</details>

#### 105. 🟡 LOW — Cast inseguro de roles do JWT: (payload?.roles as string[]) sem checar Array

- **Local:** `L23-26 (parseJwtRoles)`
- **Categoria:** type-safety · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** parseJwtRoles faz `(payload?.roles as string[]) || []`. Se o claim 'roles' vier como string unica, objeto ou ausente-mas-truthy de forma inesperada, o cast mente sobre o tipo e SessionUser.roles pode nao ser string[], quebrando consumidores que fazem roles.includes/map. O || [] so cobre undefined/null/'' — nao um objeto ou numero.

**Fix recomendado:** Substituir L25 por validação real com type predicate pra preservar string[] sem cast: `return Array.isArray(payload?.roles) ? payload.roles.filter((r): r is string => typeof r === "string") : [];`. O type predicate `(r): r is string` é importante porque, após `Array.isArray`, `payload.roles` é `unknown[]` (payload é `Record<string, unknown>`), então um `.filter(r => typeof r === "string")` simples retornaria `unknown[]` e exigiria cast — o predicate mantém o retorno corretamente tipado como string[]. Fix é safe: validação de dado puro, sem nenhuma interação com Tauri/WebKitGTK/drag/decorations, não afeta nenhum dos padrões intencionais cross-platform. Comportamento muda só nos casos hoje quebrados (string única deixa de crashar o Settings; tipos inesperados viram []).

<details><summary>Justificativa da verificação</summary>

Confirmado em apps/desktop/src/lib/auth.ts:23-26. `parseJwtRoles` faz `(payload?.roles as string[]) || []`. O `as string[]` é uma asserção de tipo que mente: o claim `roles` decodificado do JWT é, na prática, `unknown`, e o `|| []` só captura falsy (undefined/null/''/0/false), não um truthy não-array (objeto, número ou uma string única). Dano concreto rastreável: o único consumidor real é settings.tsx:292 — `user?.roles?.length ? user.roles.join(", ") : "—"`. Se o claim vier como string única (ex.: `"admin"`), `roles` vira a string `"admin"`, que tem `.length` (5, truthy), e então `user.roles.join(", ")` lança `TypeError: join is not a function`, derrubando a renderização da tela de Settings. Se vier objeto/número, cai silenciosamente em "—" (valor errado, sem crash). O emissor do JWT é o backend Spring (controlado), então a probabilidade é baixa — daí severity low, não high — mas a asserção realmente desalinha o tipo declarado SessionUser.roles: string[] da realidade runtime, e a validação é barata. Achado real e acionável, não nitpick.

</details>

### `apps/desktop/src/lib/pending-meetings.ts`  ·  3 achado(s)

#### 106. 🟡 LOW — getPendingCount e hasPendingMeetings exportados e nunca usados; consumidores re-implementam o filtro inline

- **Local:** `L49-55 (getPendingCount, hasPendingMeetings)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** getPendingCount e hasPendingMeetings nao tem nenhum caller fora do proprio arquivo (grep confirma). Pior: use-recording.ts repete `getPendingMeetings().filter((m) => m.status === "pending").length` em 4 lugares (L278, L293, L314 e similar) — exatamente o que getPendingCount() faz. Violacao DRY + codigo morto coexistindo.

**Fix recomendado:** 1) Em use-recording.ts importar getPendingCount de @/lib/pending-meetings e substituir as 3 chamadas identicas das L278, L293 e L314 por setPendingCount(getPendingCount()). 2) NAO mexer em L303-308: e um filtro distinto (retryCount/inFlightIds) que retorna array, nao count. 3) Remover hasPendingMeetings (pending-meetings.ts:53-55) pois nao tem caller; com isso getPendingCount passa a ter caller real (use-recording.ts) e deixa de ser morto. Caveat: confirmar que nenhum outro pacote/janela (overlay/dock/main) importa hasPendingMeetings antes de remover — o grep ja cobriu apps/desktop/src e deu zero, entao a remocao e segura. Mudanca behavior-preserving, sem relacao com padroes intencionais de drag/transparencia cross-platform.

<details><summary>Justificativa da verificação</summary>

Confirmado, com correcao na contagem. getPendingCount() (pending-meetings.ts:49-51) so e referenciado dentro do proprio arquivo (por hasPendingMeetings na L54); hasPendingMeetings (L53-55) nao tem NENHUM caller em apps/desktop/src (grep confirma) — codigo morto de verdade. Ao mesmo tempo, use-recording.ts duplica inline o corpo exato de getPendingCount() — `getPendingMeetings().filter((m) => m.status === "pending").length` — em 3 pontos: L278, L293 e L314 (nao 4, como diz o auditor). O quarto ponto citado (L303-308) NAO e duplicata: filtra `status === "pending" && retryCount < 10 && !inFlightIds.has(m.id)` e retorna o array, nao o .length; logica diferente, nao da pra substituir por getPendingCount(). Dano concreto: baixo (cosmetico/manutencao) — DRY violado coexistindo com dead code, e qualquer mudanca na definicao de "pending count" precisa ser replicada em 3 lugares. Nao e bug funcional.

</details>

#### 107. 🟡 LOW — getPendingCount() existe mas nunca é usado; use-recording.ts reimplementa o filtro 'pending' 3x inline

- **Local:** `L49-55 (getPendingCount/hasPendingMeetings) vs apps/desktop/src/hooks/use-recording.ts L278, L293, L314`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** pending-meetings.ts exporta getPendingCount() e hasPendingMeetings(), mas nenhum dos dois é importado em lugar algum. Enquanto isso use-recording.ts repete `getPendingMeetings().filter((m) => m.status === 'pending').length` em L278, L293 e L314. Código morto + duplicação da mesma regra de negócio.

**Fix recomendado:** 1) Em use-recording.ts L6, adicionar getPendingCount ao import: `import { savePendingMeeting, removePendingMeeting, getPendingMeetings, getPendingCount } from "@/lib/pending-meetings";`. 2) Substituir as 3 ocorrencias `setPendingCount(getPendingMeetings().filter((m) => m.status === "pending").length)` (L278, L293, L314) por `setPendingCount(getPendingCount())`. 3) Remover hasPendingMeetings() de pending-meetings.ts (zero consumidores). CAVEAT IMPORTANTE: manter o import de getPendingMeetings — ele continua sendo usado no retry-worker em L303 (`getPendingMeetings().filter((m) => m.status === "pending" && m.retryCount < 10 && !inFlightIds.has(m.id))`), cujo filtro tem condicoes extras e NAO pode ser trocado por getPendingCount(). Comportamento identico nos 3 pontos de count (mesma expressao). Risco safe; rodar `npm run typecheck` no apps/desktop apos a edicao.

<details><summary>Justificativa da verificação</summary>

Verificado nos dois arquivos. grep confirma que getPendingCount() e hasPendingMeetings() so aparecem dentro de pending-meetings.ts (L49-55) — nenhum import externo. O import em use-recording.ts L6 traz apenas savePendingMeeting/removePendingMeeting/getPendingMeetings. hasPendingMeetings() nao tem nenhum consumidor (dead code total); getPendingCount() so e alcancado transitivamente por hasPendingMeetings(), logo o par inteiro e uma subarvore morta do ponto de vista de consumidores reais. Em paralelo, use-recording.ts repete a mesma regra de negocio inline exatamente 3x (L278, L293, L314): `getPendingMeetings().filter((m) => m.status === "pending").length`. Achado real: dead-code + violacao DRY da mesma regra. Severidade low e honesta — sem impacto funcional, so manutencao. Sem relacao com padroes intencionais cross-platform (logica pura de localStorage).

</details>

#### 108. 🟡 LOW — JSON.parse do localStorage retornado como PendingMeeting[] sem validacao de shape

- **Local:** `L21-31 (getPendingMeetings)`
- **Categoria:** type-safety · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** getPendingMeetings faz JSON.parse(raw) e retorna `parsed` quando Array.isArray(parsed), com tipo de retorno declarado PendingMeeting[]. Nao ha validacao dos elementos: um array de qualquer coisa (ou objetos parciais de versao antiga do schema) passa o guard e e tratado como PendingMeeting. O retry worker em use-recording.ts acessa m.status, m.id, m.retryCount, m.payload assumindo o shape; itens corrompidos/legados causam comportamento indefinido (ex: retryCount undefined < 10 = false silencioso, ou upload com payload undefined).

**Fix recomendado:** Hardening opcional de baixa prioridade. Adicionar um guard de elemento e descartar invalidos com filter, mantendo o fix minimo (sem parser completo, sem versionar storage). Em getPendingMeetings(), trocar `if (Array.isArray(parsed)) return parsed;` por algo como:

const VALID_STATUS = new Set(["pending", "failed_permanently", "completed"]);
function isPendingMeeting(x: unknown): x is PendingMeeting {
  if (typeof x !== "object" || x === null) return false;
  const m = x as Record<string, unknown>;
  return (
    typeof m.id === "string" &&
    typeof m.status === "string" && VALID_STATUS.has(m.status) &&
    typeof m.retryCount === "number" &&
    typeof m.payload === "object" && m.payload !== null &&
    typeof (m.payload as Record<string, unknown>).fileContent === "string"
  );
}
// ...
if (Array.isArray(parsed)) return parsed.filter(isPendingMeeting);

Caveats: (1) o fix e safe — so estreita o que e retornado, nunca alarga; nao toca em nenhum padrao intencional cross-platform. (2) Como o trigger real e praticamente inexistente (unico writer e tipado, schema nunca mudou), isso e melhoria de robustez, nao correcao de bug — pode ficar fora do escopo desta branch se houver itens de maior prioridade. (3) Ignorar a sugestao de versionar a chave de storage; nao ha migracao que justifique.

<details><summary>Justificativa da verificação</summary>

A observacao de type-safety e factualmente correta: em /home/pollo/Dev/nora/apps/desktop/src/lib/pending-meetings.ts L21-31, getPendingMeetings() faz JSON.parse(raw) e retorna `parsed` apenas com guard Array.isArray(parsed), declarando o tipo de retorno como PendingMeeting[] sem validar a forma dos elementos. Isso e um cast inseguro (any[] alargado pra PendingMeeting[]).

PORÉM a severidade e o dano concreto estao superdimensionados:

1. Trigger realista inexistente. O unico produtor da chave "nora-pending-meetings" e savePendingMeeting(), chamado SO de dentro de use-recording.ts (L270-277 e L318-322) sempre com objetos PendingMeeting totalmente tipados. Nao ha outro writer, nem dado vindo do servidor, nem compartilhamento cross-app dessa chave. O git history confirma que o schema NUNCA mudou desde a introducao (commits 18f1929 -> b5e5ed8 todos com o mesmo shape), entao "objetos parciais de versao antiga" e hipotetico — nao existe migracao pendente. So tampering manual do localStorage produziria itens malformados.

2. O dano descrito esta tecnicamente errado/exagerado. No retry worker (use-recording.ts L302-327): "retryCount undefined < 10 = false" — sim, `undefined < 10` e false, mas isso EXCLUI o item corrompido do retry (comportamento seguro), nao causa upload de lixo. E "upload com payload undefined" cairia dentro do try/catch existente (L311-322): uploadTranscript lanca, o catch incrementa retryCount (`undefined + 1 = NaN`, e `NaN < 10` e false), entao o item para de tentar. Ou seja, mesmo no caso hipotetico de corrupcao, o modo de falha e benigno e auto-limitante — nao ha "comportamento indefinido" danoso nem duplicacao de dados.

3. A sugestao de "versionar a chave de storage pra migracoes" e gold-plating especulativo: nao ha nenhuma migracao real hoje.

Conclusao: hardening defensivo legitimo, mas LOW (nao medium) — protege contra edicao manual do localStorage, nao contra um bug com gatilho realista.

</details>

### `apps/desktop/src/lib/secrets.ts`  ·  1 achado(s)

#### 109. 🟡 LOW — Chaves de secret e storage como strings literais espalhadas; sem constantes nomeadas

- **Local:** `L1-15 (modulo inteiro)`
- **Categoria:** dry · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** secrets.get/set/delete sao chamados com literais 'access-token', 'refresh-token', 'current-user' (auth.ts, 7+ ocorrencias) e 'azure-speech-key'/'azure-region' (settings.tsx). pending-meetings.ts tem STORAGE_KEY local mas o padrao nao e replicado. Um typo numa chave (ex: 'refesh-token') falha silenciosamente (secret_get retorna null e o app trata como nao-logado) sem erro de compilacao.

**Fix recomendado:** Em apps/desktop/src/lib/secrets.ts, criar um objeto const central com TODAS as chaves em uso (incluindo as de migracao legada em settings.tsx) e tipar os params via keyof:

  export const SECRET_KEYS = {
    ACCESS_TOKEN: "access-token",
    REFRESH_TOKEN: "refresh-token",
    CURRENT_USER: "current-user",
    // legacy/migracao — settings.tsx so faz has()+delete() pra limpar config antiga
    AZURE_SPEECH_KEY: "azure-speech-key",
    AZURE_REGION: "azure-region",
  } as const;

  type SecretKey = (typeof SECRET_KEYS)[keyof typeof SECRET_KEYS];

  export const secrets = {
    set: (key: SecretKey, value: string) => invoke<void>("secret_set", { key, value }),
    get: (key: SecretKey) => invoke<string | null>("secret_get", { key }),
    has: (key: SecretKey) => invoke<boolean>("secret_has", { key }),
    delete: (key: SecretKey) => invoke<void>("secret_delete", { key }),
  };

Depois trocar os literais nos call sites por SECRET_KEYS.* em auth.ts e settings.tsx. CAVEATS: (1) os VALORES das strings devem permanecer byte-identicos — mudar o valor literal orfanaria secrets ja gravados no keyring do usuario. O refactor preserva valores, entao e seguro. (2) Rodar `npm run typecheck` apos a troca pra confirmar que o tipo SecretKey cobre todos os call sites (foi por isso que as 2 chaves legacy precisam estar no const). Risco de quebrar comportamento: nenhum, e puramente type-level + indireção de constante.

<details><summary>Justificativa da verificação</summary>

Confirmado. secrets.ts (L4-14) tipa `key: string` em set/get/has/delete sem nenhuma restricao. As mesmas chaves literais aparecem espalhadas: auth.ts usa 'access-token' (6x), 'refresh-token' (5x), 'current-user' (4x); settings.tsx usa 'azure-speech-key' e 'azure-region' (has+delete). Sao 15+ ocorrencias de 5 chaves distintas sem definicao compartilhada. A falha silenciosa tambem e real: secret_get retorna string|null e auth.ts L90-94 trata null como "sem token -> logout" (so um console.warn, segue como nao-logado). Um typo numa chave de get compila e degrada silenciosamente para nao-logado; um typo num set pareado com get certo grava o secret numa chave morta. NAO e nitpick — e gap real de DRY + type-safety. Severidade low esta correta: e risco latente (hoje nenhum bug, todas as chaves batem) e os literais sao constantes read-only. AJUSTE no fix proposto: o auditor listou so ACCESS_TOKEN/REFRESH_TOKEN/CURRENT_USER, mas o vocabulario completo precisa incluir tambem 'azure-speech-key' e 'azure-region' usadas em settings.tsx, senao tipar como keyof deixa esses call sites fora da union e quebra o typecheck.

</details>


## 4. Python — sidecar

### `apps/desktop/sidecar/sidecar-linux.spec`  ·  2 achado(s)

#### 110. 🟠 MEDIUM — Tres .spec do PyInstaller quase identicos com bloco Analysis/EXE e arch_map duplicados

- **Local:** `L1-99 (e os arquivos macos/windows inteiros)`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** sidecar-linux.spec, sidecar-macos.spec e sidecar-windows.spec repetem palavra por palavra: o mesmo _arch_map, o sys.path.insert, o collect_dynamic_libs/collect_all de azure+pydantic+pydantic_core, a lista hiddenimports (8 modulos azure + nora_stt_sidecar.*), a lista excludes e quase todo o EXE(). As unicas diferencas reais sao o sufixo do triple (unknown-linux-gnu / apple-darwin / pc-windows-msvc) e upx=False no macOS. Alem disso _arch_map ja existe em build_sidecar.py (_normalize_machine) — terceira copia da mesma logica. Manutencao triplica: adicionar um hiddenimport novo exige editar 3 arquivos e e facil esquecer um (ja ha drift: windows nomeia variaveis all_binaries/all_datas enquanto linux/macos usam bundled_binaries/bundled_datas, e os comentarios divergem).

**Fix recomendado:** Extrair um modulo plano compartilhado, ex: _spec_shared.py em apps/desktop/sidecar/, contendo APENAS dados/listas: o arch_map (ou reusar _normalize_machine importado de build_sidecar — cuidado: build_sidecar.py nao e um pacote, entao precisa de sys.path.insert do diretorio sidecar antes do import), a funcao que computa src_path, as chamadas collect_dynamic_libs/collect_data_files/collect_all retornando (bundled_binaries, bundled_datas, hiddenimports), e as constantes HIDDENIMPORTS_BASE + EXCLUDES. Cada .spec importa essas listas e mantem o triple_suffix e o flag upx locais. CAVEAT IMPORTANTE (por isso needs-care, nao safe): NAO mover as chamadas Analysis()/PYZ()/EXE() para o modulo compartilhado nem para um factory que devolve (a, pyz, exe). PyInstaller injeta os nomes Analysis/PYZ/EXE/SPECPATH apenas no namespace do proprio .spec executado, NAO em modulos importados via import — um factory externo daria NameError. As chamadas Analysis/PYZ/EXE devem permanecer dentro de cada .spec; so as listas de dados sao compartilhadas. Padronizar tambem os nomes de variavel (escolher bundled_* OU all_*, nao ambos) para matar o drift. Depois do refactor, rodar de fato `python build_sidecar.py` em pelo menos uma plataforma (ou `python -m PyInstaller sidecar-linux.spec --clean --noconfirm`) para confirmar que o exec context ainda resolve os globals. Manter o upx=False do macOS e os 3 sufixos de triple intactos.

<details><summary>Justificativa da verificação</summary>

Confirmado por leitura dos 3 arquivos. sidecar-linux.spec, sidecar-macos.spec e sidecar-windows.spec repetem verbatim: o sys.path.insert (linux L13-16 / macos L12-15 / windows L13-16), os collect_dynamic_libs/collect_data_files/collect_all de azure+pydantic+pydantic_core, a lista hiddenimports identica (4 modulos nora_stt_sidecar.* + 5 modulos azure, linux L47-57 / macos L43-53 / windows L49-59), a lista excludes identica (linux L61-69 / macos L57-65 / windows L63-71), e quase todo o Analysis/PYZ/EXE. As unicas diferencas reais sao o sufixo do triple (unknown-linux-gnu L38 / apple-darwin L34 / pc-windows-msvc L24) e upx=False so no macOS (L87). O _arch_map e identico em linux L36, macos L32, windows L22 — e e a mesma logica de _normalize_machine em build_sidecar.py L22-29 (na verdade sao 4 copias: 3 specs + o build script, nao 3). O drift citado tambem confere: windows usa all_binaries/all_datas/all_hiddenimports (L38-40) enquanto linux/macos usam bundled_binaries/bundled_datas, windows calcula o bloco de arch ANTES das coletas (L21-24) enquanto linux/macos calculam DEPOIS, e os comentarios divergem (windows tem 'IMPORTANTE: collect_all retorna...' L33, macos tem a nota de UPX/notarization L85-86). Isso prova que os arquivos ja divergiram. Adicionar um hiddenimport novo exige editar 3 arquivos e e facil esquecer um — debito real. Ajustado de 'high confidence/medium' mantendo medium: e tooling de build, nao runtime; nenhum bug funcional presente (o drift ate agora e so naming/ordering), entao high seria exagero.

</details>

#### 111. 🟡 LOW — import os nao usado nos tres .spec do PyInstaller

- **Local:** `L10 (import os) — mesmo problema em sidecar-macos.spec:9 e sidecar-windows.spec:10`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** Os tres specs fazem `import os` mas nenhum usa `os.` (caminhos sao montados via pathlib.Path). Import morto, replicado 3x.

**Fix recomendado:** Remover a linha `import os` de cada um dos tres arquivos: sidecar-linux.spec:10, sidecar-macos.spec:9, sidecar-windows.spec:10. Manter `import sys` e `from pathlib import Path` (ambos usados: `sys.path.insert` e `Path(...)`). Nao consolidar os tres specs num so — eles tem opcoes intencionalmente diferentes por plataforma (upx, target triple). Risco zero: remover import nao usado de um spec PyInstaller nao altera o build.

<details><summary>Justificativa da verificação</summary>

Confirmado nos tres arquivos. `import os` aparece em sidecar-linux.spec:10, sidecar-macos.spec:9 e sidecar-windows.spec:10. Rodei `grep -E '\bos\.'` nos tres e retornou zero matches (exit 1) — nenhum uso de `os.` em lugar nenhum. Construcao de caminho usa pathlib (`Path(SPECPATH).parent`, `project_root / \"src\"`) e deteccao de arch usa `platform.machine()`, nao `os`. Logo o `import os` e morto, replicado 3x. Impacto e nulo (specs sao Python de build-time; PyInstaller nao consome `os` implicitamente), por isso severidade low e quase nitpick — mas o brief pede explicitamente pra apontar imports nao usados, entao confirmo. Importante notar que os tres .spec sao intencionalmente divergentes (ex: macOS usa upx=False com comentario sobre notarization, Linux/Windows upx=True), entao o conserto e so deletar a linha em cada um, NAO consolidar os arquivos.

</details>

### `apps/desktop/sidecar/src/nora_stt_sidecar/transcriber.py`  ·  7 achado(s)

#### 112. 🟠 MEDIUM — confidence sempre None em producao (atributo inexistente no SDK real)

- **Local:** `L137 (_on_transcribed) and L120/L133 (_on_transcribing/_on_transcribed speaker_id)`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** Em _on_transcribed faz `confidence=getattr(result, "confidence", None)`. O objeto real do SDK (ConversationTranscriptionResult, subclasse de RecognitionResult) NAO possui atributo `confidence` — confirmei lendo o SDK instalado em .venv (azure/cognitiveservices/speech/speech.py e transcription.py): RecognitionResult expoe offset/duration/result_id/text/reason e ConversationTranscriptionResult adiciona speaker_id, mas confidence so existe dentro de `result.json` (NBest/SpeechServiceResponse_JsonResult). Logo o getattr cai sempre no default None e o campo FinalMessage.confidence chega null no Rust/UI em 100% dos casos. O bug fica invisivel porque test_transcriber_fake.py usa um FakeResult que TEM `.confidence`, dando falsa sensacao de cobertura.

**Fix recomendado:** Extrair confidence parseando o JSON do resultado. ATENCAO: o fix proposto pelo auditor usa `result.properties.get_property(...)`, mas no SDK instalado `RecognitionResult.properties` (speech.py L604-611) retorna um dict comum (`self._properties.copy()`), nao um PropertyCollection — chamar `.get_property()` nele lanca AttributeError. Usar em vez disso a property publica `result.json` (speech.py L590-595), que ja e exatamente PropertyId.SpeechServiceResponse_JsonResult. Em _on_transcribed:\n\n    import json  # topo do modulo\n    ...\n    confidence = None\n    raw = getattr(result, "json", None)\n    if raw:\n        try:\n            nbest = json.loads(raw).get("NBest")\n            if nbest:\n                confidence = nbest[0].get("Confidence")\n        except (json.JSONDecodeError, KeyError, IndexError, TypeError):\n            confidence = None  # mantem nullable; nao deixa um JSON malformado derrubar o evento\n\nCaveats: (1) NBest/Confidence so vem populado quando o formato de saida detalhado esta habilitado — para garantir, setar speech_config.output_format = speechsdk.OutputFormat.Detailed (ou set_property RequestDetailedResultTrueFalse=true) em _setup_transcriber; sem isso o JSON pode nao conter NBest e confidence segue None (mas ai por motivo legitimo, nao por bug de atributo). (2) Ajustar o teste: FakeResult deve expor `.json` (string com {"NBest":[{"Confidence":0.95}]}) em vez do atributo ficticio `.confidence`, senao o teste continua mascarando regressao. (3) Envolver o parse em try/except para nao trocar um bug silencioso por um crash em payload inesperado.

<details><summary>Justificativa da verificação</summary>

Confirmado lendo o SDK realmente instalado em .venv/lib/python3.14/site-packages/azure/cognitiveservices/speech/. A classe RecognitionResult (speech.py L474-615) expoe apenas: cancellation_details, no_match_details, offset, duration, channel, result_id, reason, text, json, error_json, properties — NAO ha atributo nem property `confidence`. ConversationTranscriptionResult (transcription.py L28-51, subclasse de RecognitionResult) adiciona somente `speaker_id`. Um grep por "confidence" em speech.py e transcription.py retorna ZERO ocorrencias. Logo, em transcriber.py:137 `confidence=getattr(result, "confidence", None)` cai sempre no default None em producao: FinalMessage.confidence chega null no Rust/UI em 100% dos casos reais. A confianca de fato so existe dentro do JSON do servico (result.json -> NBest[0].Confidence / PropertyId.SpeechServiceResponse_JsonResult). O bug fica invisivel porque tests/test_transcriber_fake.py define um FakeResult com atributo `.confidence` ficticio (L18-23) e o assert L166 valida `confidence == 0.95` — falsa cobertura. Escopo: o arquivo esta em apps/desktop/sidecar/, ou seja apps/desktop/**, dentro de escopo (nao e o services/nlp-worker excluido). Rebaixo a severidade de high para medium: o campo e metadado opcional (FinalMessage.confidence ja e nullable), nao quebra a transcricao em si — degrada silenciosamente um sinal de qualidade, sem crash nem perda de texto.

</details>

#### 113. 🟠 MEDIUM — Restart bloqueia a thread de eventos do SDK e ignora _stopped

- **Local:** `L141-184 (_on_canceled) — restart com time.sleep e .get() na thread de callback`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** _on_canceled e chamado pelo SDK numa thread de eventos interna. La dentro o restart faz `time.sleep(backoff)` (ate 4s) e depois `_cleanup()` (que faz stop_transcribing_async().get()) seguido de `_setup_transcriber()` (start_transcribing_async().get()) — tudo sincrono dentro do callback. Bloquear a thread de eventos do SDK por segundos pode travar/serializar a entrega de outros eventos e o proprio shutdown. Pior: o restart nao re-checa `self._stopped`; se stop() foi chamado durante o backoff, o codigo recria push_stream/transcriber e ressuscita a sessao que deveria estar encerrada (o emit de StoppedMessage ja foi enviado), deixando recursos vivos sem dono.

**Fix recomendado:** Fix minimo e seguro: no inicio do bloco de restart (logo apos L166, antes de incrementar _restart_count) adicionar `if self._stopped: return`, e re-checar apos o time.sleep (entre L171 e L172) com outro `if self._stopped: return` antes de chamar _cleanup()/_setup_transcriber(). Isso so adiciona early-returns, nao altera o happy-path do restart — risco baixo. Fix mais robusto (recomendado mas mais invasivo): tirar o restart da thread de callback, enfileirando uma flag/evento que o loop principal consome ou usando threading.Timer, eliminando o bloqueio da thread de eventos do SDK; nesse caso ainda manter o guard _stopped e cuidar da ordenacao do StoppedMessage. Caveat: idealmente proteger _stopped/_cleanup com um threading.Lock para fechar a janela de corrida entre stop() e _on_canceled, ja que hoje nao ha sincronizacao alguma. Os padroes intencionais cross-platform (startDragging/resize, backgrounds solidos do WebKitGTK) sao do frontend Tauri e nao tem relacao com este sidecar Python — sem conflito.

<details><summary>Justificativa da verificação</summary>

Confirmado em apps/desktop/sidecar/src/nora_stt_sidecar/transcriber.py. _on_canceled (L141-184) e registrado como callback do SDK via self._transcriber.canceled.connect(self._on_canceled) (L108), entao roda na thread interna de eventos do Azure Speech SDK — NAO na thread do loop de stdin em __main__.py. Dois danos concretos:

1) Bloqueio da thread de eventos do SDK: L171 time.sleep(backoff) (ate 4s no restart 3: 2**(3-1)=4), seguido de _cleanup() que chama stop_transcribing_async().get() (L222, sincrono) e _setup_transcriber() que chama start_transcribing_async().get() (L111, sincrono). Chamar stop/start sincronos de dentro de um event handler do SDK e o padrao classico de deadlock/serializacao de entrega de eventos.

2) Restart nao re-checa self._stopped (bug de ressurreicao). Nao existe lock nenhum no arquivo (grep so achou self._stopped sem threading/Lock). Cenario real: erro NETWORK dispara _on_canceled na thread do SDK -> dorme 4s; nesse meio tempo o loop principal recebe 'stop' -> __main__._handle_stop chama transcriber.stop() (L119) que seta _stopped=True, faz _cleanup(), emite StoppedMessage e zera self._transcriber=None (L120). Apos o sleep, _on_canceled segue para L173-174 e recria push_stream/transcriber via _setup_transcriber(), ressuscitando uma sessao ja reportada como 'stopped'. Resultado: transcriber/push_stream vivos sem dono (SidecarApp._transcriber e None), conexao Azure vazada, e emit de partial/final/error para uma sessao que o consumidor ja viu encerrada. Achado real e acionavel, nao nitpick.

</details>

#### 114. 🟡 LOW — Erros tratados de forma incompleta no mapa de cancelamento

- **Local:** `L141-156 (_on_canceled error_code_map) — falta CancellationErrorCode.NoError/ServiceTimeout`
- **Categoria:** error-handling · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** Quando cancellation_details.reason != CancellationReason.Error (ex: EndOfStream), o codigo ainda emite um ErrorMessage com code='UNKNOWN' e message do cancelamento — um fim normal de stream seria reportado como erro a UI. Alem disso o mapa nao cobre ServiceTimeout e outros codigos; qualquer codigo nao mapeado vira 'UNKNOWN', perdendo a distincao para o cliente decidir retry.

**Fix recomendado:** Em _on_canceled (L141-164): mover a emissao do ErrorMessage e o bloco de restart para DENTRO do guard `if cancellation_details.reason == CancellationReason.Error:`. Para EndOfStream e CancelledByUser, apenas `logger.info(...)` (sao encerramentos normais; o StoppedMessage ja e emitido em stop()). Concretamente: (1) construir error_code_map e error_code dentro do if; (2) emitir ErrorMessage dentro do if; (3) manter a logica de restart dentro do if. Expandir o mapa com codigos relevantes do SDK instalado: ServiceTimeout -> "NETWORK" (retryable, entra no restart), ServiceError/Forbidden conforme politica. CORRIGIR TAMBEM o nome errado: trocar `speechsdk.CancellationErrorCode.BadRequestParameters` (inexistente) por `speechsdk.CancellationErrorCode.BadRequest` em L150, senao o caminho de erro real estoura AttributeError. Risco baixo: nenhum teste cobre cancelamento; restart so dispara para NETWORK/SERVICE_UNAVAILABLE (e agora ServiceTimeout), preservando o comportamento de erro real e eliminando so o falso-positivo no encerramento normal.

<details><summary>Justificativa da verificação</summary>

Confirmei o codigo em apps/desktop/sidecar/src/nora_stt_sidecar/transcriber.py L141-164. O arquivo esta DENTRO do escopo (apps/desktop/sidecar/**, NAO services/nlp-worker). O defeito e real: na linha 146 error_code recebe "UNKNOWN" por default e o ErrorMessage e emitido INCONDICIONALMENTE em L158-164, fora do guard `if cancellation_details.reason == CancellationReason.Error:`. Confirmei via SDK instalado que CancellationReason tem 3 valores: CancelledByUser, EndOfStream, Error. O evento `canceled` do Azure Speech SDK dispara com reason=EndOfStream quando o push stream e fechado — exatamente o que stop() -> _cleanup() -> push_stream.close() faz num encerramento normal. Logo, um stop gracioso emite um ErrorMessage(code="UNKNOWN") espurio. Esse erro e repassado pelo Rust: stt_sidecar.rs L490-491 faz `Some("error") => app.emit("stt-error", &json)`. Portanto a parte do auditor "fim normal de stream reportado como erro" e mecanicamente correta. CAVEAT que rebaixa severidade pra low: nao existe NENHUM listener de "stt-error" no frontend (grep em apps/desktop/src/ retornou vazio), entao o dano e LATENTE — o usuario nao ve o erro hoje, mas qualquer consumidor futuro de stt-error recebera falso-positivo a cada parada. A 2a parte (mapa incompleto) tambem procede: ServiceTimeout/ServiceError/Forbidden/BadRequest existem no SDK e colapsam pra "UNKNOWN", e ServiceTimeout deveria ser retryable como NETWORK. Nao ha teste que assercione erro-no-cancelamento (test_transcriber_fake.py so testa ready/partial/final/stopped), entao o fix nao quebra suite. DESCOBERTA ADJACENTE (fora do achado, mas no mesmo bloco): L150 usa speechsdk.CancellationErrorCode.BadRequestParameters que NAO existe no SDK (o correto e BadRequest) — confirmei hasattr=False; isso levantaria AttributeError na construcao do dict toda vez que _on_canceled rodar com reason=Error, quebrando o caminho inteiro de erro. Vale corrigir junto.

</details>

#### 115. 🟡 LOW — Estado compartilhado entre thread de callback e thread principal sem lock (race)

- **Local:** `L48-53, L186-189 (feed), L191-233 (stop/_cleanup), L141-184 (_on_canceled)`
- **Categoria:** logic-error · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** _push_stream/_transcriber/_started/_restart_count sao lidos e escritos por threads distintas sem nenhuma sincronizacao (nao ha import threading nem Lock em todo o pacote). feed() (chamada pela thread do stdin/main via _handle_audio) faz `if self._push_stream and self._started: self._push_stream.write(...)` enquanto _on_canceled (thread do SDK) faz _cleanup() setando `self._push_stream = None` e recriando-o. Janela classica de race: feed() le _push_stream nao-None, o cleanup zera/fecha, e o write cai num stream fechado/None -> excecao silenciada em _handle_audio (so logada). Tambem _stopped/_started nao sao memory-safe entre threads.

**Fix recomendado:** Adicionar `import threading` e um `self._lock = threading.RLock()` no __init__. Proteger as transicoes de estado de _push_stream/_transcriber/_started/_stopped. Mas com CUIDADO para nao introduzir deadlock/stall:\n\n1. Em feed(): capturar a referencia local sob o lock e soltar o lock ANTES do write (write pode bloquear): `with self._lock: stream = self._push_stream if self._started else None`; depois `if stream: stream.write(pcm_bytes)`. Isso garante que o objeto write-alvo seja uma referencia estavel, mesmo que _cleanup zere o atributo em seguida.\n2. Em _cleanup()/_setup_transcriber()/stop(): NAO segurar o lock enquanto chama operacoes bloqueantes do SDK (`stop_transcribing_async().get()`, `start_transcribing_async().get()`). Segurar o lock so para ler/trocar as referencias (ex: pegar `t = self._transcriber; s = self._push_stream; self._transcriber=None; self._push_stream=None` sob lock, depois fazer t.stop().get()/s.close() fora do lock). Caso contrario o feed() da main thread ficaria bloqueado por segundos durante restart, travando o pipe de audio.\n3. Em _on_canceled: NAO segurar o lock durante `time.sleep(backoff)` nem durante _emit (que faz print bloqueante em stdout).\n\nAlternativa mais simples e suficiente para o dano concreto observado (sem reescrever toda a sincronizacao): no minimo trocar feed() para capturar a referencia local uma unica vez — `stream = self._push_stream` e checar/escrever sobre a local — eliminando a leitura dupla do atributo (L188 + L189). Isso ja remove o caso de `self._push_stream` virar None entre a checagem e o write. Um write num PushAudioInputStream ja fechado tende a so levantar excecao (ja capturada), entao a referencia local resolve o NoneType e reduz o risco real, com risco de regressao minimo.

<details><summary>Justificativa da verificação</summary>

Confirmado que o codigo existe e o modelo de threads e real. feed() (transcriber.py:186-189) e chamada por _handle_audio (__main__.py:103) que roda na thread MAIN (loop `for line in sys.stdin` em run(), L141). _on_canceled (transcriber.py:141) e conectado via `self._transcriber.canceled.connect(...)` (L108) e dispara em thread INTERNA do Azure Speech SDK — os callbacks transcribing/transcribed/canceled nunca rodam na main thread. Grep confirma que NAO existe `import threading`/Lock/RLock em nenhum arquivo do pacote (so referencias a pytest-asyncio em egg-info). Logo _push_stream/_transcriber/_started sao realmente lidos/escritos por duas threads distintas sem sincronizacao.\n\nA janela de race descrita procede: em _on_canceled, no caminho de restart (error_code NETWORK ou SERVICE_UNAVAILABLE e _restart_count < _max_restarts, L167), _cleanup() faz `self._push_stream.close()` (L228) e `self._push_stream = None` (L233), e _setup_transcriber() recria o stream. Enquanto isso feed() avalia `if self._push_stream and self._started` (L188) e depois faz `self._push_stream.write(...)` (L189) — duas leituras separadas do atributo. Entre a checagem e o write, a thread do SDK pode fechar/zerar o stream, fazendo o write cair em stream fechado/None. A excecao e engolida em _handle_audio (try/except L104-105, apenas logada).\n\nPORQUE AJUSTEI A SEVERIDADE de medium pra low: o impacto e limitado e transiente. (1) A race so e possivel no caminho de restart por erro de rede/servico, que ja e um estado degradado; (2) ha `time.sleep(backoff)` (L171) ANTES do _cleanup, estreitando (embora nao eliminando) a janela; (3) o pior caso e um chunk de audio perdido + uma excecao logada durante reconexao — nao ha crash do processo nem corrupcao de estado persistente. Continua sendo um defeito real de concorrencia (acesso a estado compartilhado mutavel sem lock entre duas threads genuinas), mas o dano concreto e modesto. A afirmacao do auditor sobre stop() vs feed() concorrentes e mais fraca: _handle_stop e _handle_signal rodam na main thread (em CPython signal handlers executam na main thread), entao stop() nao corre de fato concorrente com feed(); o par realmente concorrente e callback-do-SDK vs main.

</details>

#### 116. 🟡 LOW — Backoff de restart nunca reseta apos recuperacao bem-sucedida

- **Local:** `L166-184 (restart) — _restart_count nunca reseta apos sucesso`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** _restart_count so incrementa e e comparado com _max_restarts (3), mas nunca volta a 0 depois de um restart bem-sucedido. Numa sessao longa com falhas de rede esporadicas (recuperaveis), apos 3 quedas espalhadas ao longo de horas o transcriber para de tentar reconectar para sempre, mesmo que cada falha anterior tenha se recuperado. O contador deveria contar tentativas consecutivas, nao acumuladas.

**Fix recomendado:** Em _on_canceled, dentro do bloco try de restart, logo apos o log de sucesso em L175 (`logger.info("Transcriber restarted successfully")`), adicionar `self._restart_count = 0`. Assim _restart_count passa a contar tentativas CONSECUTIVAS: cada restart que se conecta com sucesso reinicia o orcamento de 3 e tambem o backoff exponencial (L169), preservando a escalacao de backoff dentro de uma rajada continua de falhas mas restaurando a resiliencia em sessoes longas. Caveat: condicionar a reset ao sucesso (apos L175), e nao zerar o contador antes/junto de start_transcribing_async().get(); como ha exponential backoff por tentativa consecutiva, um eventual loop apertado de reconectar-e-cair ainda fica limitado pelo backoff entre quedas.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo real. Em apps/desktop/sidecar/src/nora_stt_sidecar/transcriber.py, _restart_count e inicializado em 0 apenas no __init__ (L52), so incrementa em L168 e e comparado de forma acumulada contra _max_restarts=3 em L167. Apos um restart bem-sucedido (L174 _setup_transcriber + L175 log "Transcriber restarted successfully") o contador NUNCA volta a 0 — confirmei por grep que nao ha nenhum self._restart_count = 0 fora do construtor em todo o repo. O ciclo de vida em __main__.py confirma o dano: um unico LiveTranscriber e criado em _handle_start (L46) e .start() chamado uma vez (L53), vivendo por toda a sessao de uma reuniao (potencialmente horas). Logo o orcamento de reconexao e de 3 restarts TOTAIS na sessao inteira, nao 3 consecutivos. Numa sessao longa com blips de rede esporadicos e individualmente recuperaveis (NETWORK / SERVICE_UNAVAILABLE), apos a 3a queda acumulada o bloco de restart (L167) deixa de disparar para sempre: o ErrorMessage ainda e emitido (L158-164, antes do bloco), mas nenhuma tentativa de reconexao acontece, deixando a transcricao silenciosamente morta mesmo que a rede tenha se recuperado. Degradacao real da resiliencia. Severidade low e honesta: exige sessao longa + multiplas falhas recuperaveis espalhadas para se manifestar; nao e crash nem corrupcao. Fix e safe: e logica pura do sidecar Python, sem qualquer relacao com os padroes intencionais cross-platform/WebKitGTK (drag, backgrounds solidos, decorations); nao ha testes cobrindo restart (grep em tests/ nao retornou nada), entao nao quebra asserts existentes. Ajuste sobre o fix do auditor: a reset deve ocorrer SOMENTE apos restart bem-sucedido (apos L175) para tornar o limite de tentativas consecutivas; NAO colocar a reset dentro de _setup_transcriber de forma que zere o contador que acabou de ser incrementado em L168 ou mascare um loop reconectar-e-cair-imediatamente.

</details>

#### 117. 🟡 LOW — Numeros/constantes magicas espalhadas (segmentation timeout, formato de audio, restarts)

- **Local:** `L84-92 (set_property com magic strings) e L95-99`
- **Categoria:** professionalism · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** Valores como "800" (Speech_SegmentationSilenceTimeoutMs), samples_per_second=16000/bits_per_sample=16/channels=1, _max_restarts=3 e o divisor 10000 (ticks->ms, repetido em 3 pontos L122/L135/L136) sao magic numbers sem nome. O 16000/16/1 tambem e duplicado conceitualmente do StartMessage.sample_rate (protocol.py L16) e do lado Rust (stt_sidecar.rs sample_rate:16000) — fonte de verdade espalhada; se o sample_rate do Rust mudar, este AudioStreamFormat fica hardcoded e dessincroniza.

**Fix recomendado:** Parte segura (recomendada): extrair constantes nomeadas no topo de transcriber.py — TICKS_PER_MS = 10_000 (e usar result.offset // TICKS_PER_MS em L122/L135/L136), SEGMENTATION_SILENCE_MS = 800 (passar str(SEGMENTATION_SILENCE_MS) em L91), SAMPLE_RATE_HZ = 16000, BITS_PER_SAMPLE = 16, CHANNELS = 1 (usar em L96-98), MAX_RESTARTS = 3 (usar em L53). Isso e refactor puro, sem mudanca de comportamento. Parte opcional/needs-care (NAO obrigatoria pra fechar o achado): se quiser realmente honrar StartMessage.sample_rate, o trabalho e maior do que o achado sugere — hoje o campo ja e ignorado; seria preciso (1) adicionar sample_rate/channels ao LiveTranscriber.__init__, (2) passa-los em __main__.py:46-52 a partir de msg.sample_rate/msg.channels, e (3) confirmar que o pipeline Rust (audio_capture.rs resample p/ 16000) entrega exatamente esse rate, senao introduz bug de audio corrompido. Recomendo NAO acoplar essa segunda parte ao cleanup; trate como follow-up separado se houver intencao real de tornar o sample_rate configuravel.

<details><summary>Justificativa da verificação</summary>

Codigo confirmado em transcriber.py: L91 "800" (Speech_SegmentationSilenceTimeoutMs), L96-98 samples_per_second=16000/bits_per_sample=16/channels=1, L53 _max_restarts=3, e o divisor //10000 (ticks->ms) repetido em L122, L135 e L136 (3 ocorrencias). Sao de fato magic numbers sem nome. A preocupacao de fonte-de-verdade espalhada tambem e real: 16000 aparece hardcoded em protocol.py:16, stt_sidecar.rs:360, stt_sidecar_test.rs:34 e audio_capture.rs:293 — se o sample_rate mudar de um lado, transcriber.py dessincroniza silenciosamente. Severidade low esta correta: nenhum bug, nenhum risco de runtime; e puramente manutenibilidade, e os valores ja tem comentarios inline (L82/L88/L94/L122). PORÉM, a parte do fix "derivar samples_per_second do StartMessage.sample_rate para honrar o campo do protocolo" esta imprecisa: o campo StartMessage.sample_rate JA e descartado hoje — __main__.py:46-52 instancia LiveTranscriber sem passar sample_rate, e LiveTranscriber.__init__ (L34-46) nem aceita esse parametro. Logo nao e cleanup trivial: exige adicionar parametro no __init__ e thread-lo pelo call site, alem de validar que o resampler Rust realmente entrega o rate combinado. Por isso ajusto: extracao de constantes nomeadas e safe e vale; derivar do protocolo e needs-care (mudanca de assinatura multi-arquivo, nao cosmética).

</details>

#### 118. 🟡 LOW — getattr defensivo desnecessario em speaker_id mascara contrato real

- **Local:** `L120, L133 (getattr result.speaker_id)`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** `getattr(result, "speaker_id", None)` sugere que speaker_id pode nao existir, mas o objeto real ConversationTranscriptionResult sempre expoe a property speaker_id (verificado no SDK). O getattr defensivo e ruido que esconde o contrato; se um dia o result vier de outro tipo, o erro fica silencioso. (Diferente do caso confidence, aqui o atributo existe — o problema e so estilo/clareza.)

**Fix recomendado:** Em apps/desktop/sidecar/src/nora_stt_sidecar/transcriber.py L120 e L133, trocar `getattr(result, "speaker_id", None)` por `result.speaker_id` direto, pois a property sempre existe (transcription.py L42-47). NAO mexer no L137 `getattr(result, "confidence", None)` — esse default e necessario (confidence nao e property da classe). Caveat importante que o achado nao menciona: quando a diarizacao ainda nao identificou o falante, o SDK retorna string VAZIA (""), nao ausencia — tanto o codigo atual quanto o fix entregam "" (nunca None) ao PartialMessage/FinalMessage nesse caso. Se a intencao for normalizar para None quando nao ha falante, use `result.speaker_id or None` em vez do getattr; caso contrario, `result.speaker_id` direto preserva o comportamento atual. Fix safe: nenhum dos padroes intencionais cross-platform (drag/resize WebKitGTK, backgrounds solidos, decorations:false) e tocado — e Python puro do sidecar.

<details><summary>Justificativa da verificação</summary>

Confirmei na fonte do SDK instalado (azure/cognitiveservices/speech/transcription.py L42-47): `ConversationTranscriptionResult.speaker_id` e uma @property SEMPRE presente — setada no __init__ via `conversation_transcription_result_get_speaker_id` e exposta sem fallback. Verifiquei tambem que `hasattr(ConversationTranscriptionResult, 'speaker_id')` == True e `hasattr(..., 'confidence')` == False. Logo o achado e factualmente correto em ambas as pernas: em transcriber.py L120 e L133 o `getattr(result, "speaker_id", None)` tem default morto (nunca cai pra None), enquanto o `getattr(result, "confidence", None)` em L137 e legitimo porque confidence realmente nao existe como property na classe. O dano, porem, e baixo e o proprio auditor admite ("o problema e so estilo/clareza"): ambos os callbacks recebem ConversationTranscriptionEventArgs cujo .result e sempre ConversationTranscriptionResult, entao o cenario de "outro tipo silencioso" e especulativo. E ruido de clareza/contrato, nao bug. Confirmo como tech-debt de baixa severidade (verdict ajustado de low->adjusted apenas por refinar o fix com a nuance da string vazia).

</details>

### `apps/desktop/sidecar/build_sidecar.py`  ·  1 achado(s)

#### 119. 🟡 LOW — import tardio de shutil e fallback de erro lista dist mas nao usa candidates

- **Local:** `L96-103 (fallback quando binario nao encontrado) e L107 (import shutil tardio)`
- **Categoria:** professionalism · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** shutil e importado no meio do main() (L107) em vez do topo — inconsistente com os demais imports e dificulta leitura. No bloco de erro L97-103, `candidates = list(dist_dir.iterdir())` e computado e iterado para imprimir, ok, mas se dist_dir nao existir (PyInstaller falhou de forma estranha) iterdir() lanca FileNotFoundError nao tratado, mascarando a mensagem de erro real. Menor: as f-strings em L62 e L100 ('Specs disponiveis:', 'Conteudo de ...') misturam prints de erro.

**Fix recomendado:** 1) Mover `import shutil` para o bloco de imports do topo (junto a platform/subprocess/sys/pathlib em L16-19) e remover de L107. 2) Em L96-103, guardar a listagem: `candidates = list(dist_dir.iterdir()) if dist_dir.exists() else []` (ou envolver o loop em `if dist_dir.exists():`) para nunca lancar FileNotFoundError e preservar a mensagem amigavel de erro. 3) Remover o prefixo `f` de L62 (`print("Specs disponíveis:", file=sys.stderr)`). CAVEAT: NAO mexer em L100 — o `f` ali e necessario porque interpola `{dist_dir}`; o achado esta errado nesse ponto. As demais f-strings (L79, L88 etc.) tambem usam interpolacao e devem ficar.

<details><summary>Justificativa da verificação</summary>

Conferi build_sidecar.py inteiro. Os pontos do achado se sustentam em parte: (1) `import shutil` esta de fato no meio do main() em L107, fora do topo (L16-19) — inconsistente com o resto e sem ganho de lazy-import porque shutil e stdlib. Style/legibilidade, trivial. (2) Em L98 `candidates = list(dist_dir.iterdir())` realmente pode lancar FileNotFoundError se `dist_dir` nao existir: chega-se ao bloco quando `built_exe.exists()` (L96) e False, e built_exe = dist_dir/expected_name; se o proprio dist_dir nao existir, built_exe.exists() e False e iterdir() estoura. POReM esse caminho so e alcancado APOS PyInstaller retornar 0 (L82 ja faz return early em falha), e PyInstaller sempre cria dist/ quando tem sucesso — entao o cenario e quase impossivel. E mesmo nele o erro nao e silenciado: vira um traceback em vez da mensagem amigavel "Binário esperado não encontrado". Robustez menor, nao masking real de erro. (3) L62 `print(f"Specs disponíveis:")` tem f-prefix sem interpolacao — correto, e stray. MAS o achado erra em L100: `print(f"Conteúdo de {dist_dir}:")` TEM interpolacao ({dist_dir}), o f e necessario — a afirmacao do auditor sobre L100 esta incorreta. Resumo: bundle de nitpicks low reais (shutil tardio, guard de iterdir, f stray em L62) com uma imprecisao (L100). Nada cross-platform/intencional e afetado; fix e seguro.

</details>

### `apps/desktop/sidecar/pyproject.toml`  ·  1 achado(s)

#### 120. 🟡 LOW — pydantic e dependencia direta do sidecar mas pyproject pede >=2.0 enquanto specs assumem pydantic_core C-ext (>=2.x)

- **Local:** `L6-9 dependencies + build_sidecar/specs`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** pyproject declara pydantic>=2.0 e os 3 specs fazem collect_all('pydantic_core') porque 'Pydantic 2 tem pydantic_core como C extension'. Esta coerente com 2.x, mas o constraint aberto >=2.0 sem upper bound deixa o build sujeito a breaking changes futuros do pydantic_core (renomeacao de submodulos quebraria os hiddenimports hardcoded azure.*/pydantic). CLAUDE.md fixa Pydantic >=2.9 no worker; aqui ficou >=2.0, divergindo do padrao de versao do projeto. azure-cognitiveservices-speech>=1.40 tambem sem upper bound.

**Fix recomendado:** Alinhar o pin do pydantic com o padrao do projeto: `pydantic>=2.9` em apps/desktop/sidecar/pyproject.toml:8 (consistencia com services/nlp-worker/pyproject.toml:17). Opcionalmente adicionar upper bounds defensivos contra major bumps automaticos no CI (`pydantic>=2.9,<3`, `azure-cognitiveservices-speech>=1.40,<2`). Caveat: o upper bound em pydantic protege pouco na pratica porque collect_all ja descobre submodulos dinamicamente (resiliente a refactors de submodulo dentro do 2.x); o ganho real e travar major 3.x ate validacao manual. Caveat 2: upper bounds muito restritos podem gerar atrito de resolver/Dependabot — se o projeto nao adota essa convencao em outros pyproject, o minimo acionavel e so corrigir o `>=2.0` para `>=2.9`. Nao mexer nos specs: a abordagem collect_all ja esta correta e cross-platform-safe.

<details><summary>Justificativa da verificação</summary>

Os fatos batem: apps/desktop/sidecar/pyproject.toml L7-8 declara `azure-cognitiveservices-speech>=1.40` e `pydantic>=2.0`, ambos sem upper bound. Os 3 specs (sidecar-linux.spec L29-30, sidecar-windows.spec L34-35, macos por inferencia) fazem collect_all('pydantic') + collect_all('pydantic_core') com o comentario "Pydantic 2 tem pydantic_core como C extension". pydantic e dependencia direta real (src/nora_stt_sidecar/protocol.py:2 `from pydantic import BaseModel, Field`). E ha divergencia de pin confirmada: services/nlp-worker/pyproject.toml:17 fixa `pydantic>=2.9`, enquanto o sidecar usa `>=2.0`. Logo a parte de "inconsistencia de versao com o padrao do projeto" e legitima. POReM ajusto a severidade/justificativa: (1) nao ha quebra atual — venv tem pydantic 2.13.4 e o build funciona; (2) a premissa do achado de que "hiddenimports hardcoded de pydantic quebrariam com renomeacao de submodulos" esta ERRADA: os hiddenimports de pydantic/pydantic_core NAO sao hardcoded, vem dinamicamente de collect_all (que descobre submodulos/binarios em build-time e por isso e justamente resiliente a renomeacoes). Apenas os hiddenimports `azure.*` sao hardcoded. Entao o risco real residual e estreito: hygiene de pin (consistencia >=2.0 vs >=2.9 do projeto) e ausencia de upper bound, nao um bug funcional. E tech-debt low de verdade, nao high.

</details>

### `apps/desktop/sidecar/src/nora_stt_sidecar/__main__.py`  ·  3 achado(s)

#### 121. 🟡 LOW — _audio_seq e codigo morto; validacao de sequencia e apenas cosmetica

- **Local:** `L26, L54, L96-99 (_audio_seq e validacao de seq)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** self._audio_seq e atribuido (=0) em __init__ e em _handle_start mas nunca e lido em lugar nenhum (confirmado por grep no pacote). E debito/ruido. Alem disso a 'validacao' de sequencia em _handle_audio apenas loga um warning de gap e segue em frente — nao ha reordenacao, descarte de duplicatas nem reset por sessao alem do feito no start; ou seja, nao protege a integridade do audio, so polui o stderr.

**Fix recomendado:** Remover APENAS self._audio_seq (deletar L26 em __init__ e L54 em _handle_start) — e dead code write-only, remocao trivial e segura, sem efeito comportamental. NAO remover self._expected_seq nem a checagem de gap (L97-99): _expected_seq E lido e a validacao funciona. Sobre tornar a validacao "real" (descartar duplicatas/sinalizar gap ao Rust): NAO fazer agora — adicionar reorder/dedup no caminho quente de audio adiciona complexidade e risco real sem nenhum consumidor que precise disso hoje. O log de warning de gap e diagnostico valido e deve ficar. Se quiser tirar a impressao de robustez, ajustar o comentario L96 de "Validate sequence number" para "Diagnostic: log sequence gaps (no reorder/dedup)". Caveat: grep em apps/desktop/ ja confirma que nenhum bridge Rust/JS le _audio_seq.

<details><summary>Justificativa da verificação</summary>

Confirmei abrindo o arquivo e cruzando com grep no pacote inteiro. A parte central do achado e REAL e precisa: self._audio_seq e write-only. E atribuido em __init__ (L26) e em _handle_start (L54), e grep -rn "_audio_seq" em apps/desktop/ retorna SOMENTE essas duas linhas de escrita — nunca e lido em lugar nenhum. E codigo morto puro, seguro de remover. Note que e um campo DISTINTO de self._expected_seq (L27/L55/L97-99), que NAO e morto: _expected_seq e lido em L97 e L98 e usado na checagem de gap. Por isso o achado precisa de AJUSTE: o titulo/escopo agrupa as duas coisas, mas so _audio_seq e dead-code. A segunda alegacao ("validacao de seq e apenas cosmetica") tambem e factualmente verdadeira — _handle_audio so loga warning de gap (L98), nao reordena, nao descarta duplicata, nao reseta nada alem do start, nem sinaliza ao Rust. Porem isso e um julgamento de design, nao um defeito: logar um warning diagnostico num gap de sequencia e escolha legitima de observabilidade, nao falsa promessa de robustez que cause dano concreto. Confirmei tambem que msg.seq existe em protocol.py (L28: seq: int), entao o codigo de validacao em si nao esta quebrado. Severidade low: nenhum impacto funcional; e higiene de codigo.

</details>

#### 122. 🟡 LOW — Signal handler chama stop()->print() (nao async-signal-safe) e pode reentrar

- **Local:** `L122-128 (_handle_signal) + L31 (_emit usa print)`
- **Categoria:** error-handling · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** _handle_signal roda em contexto de signal handler do Python e chama self._transcriber.stop(), que por sua vez faz _emit(StoppedMessage) -> print(..., flush=True) e _cleanup() com chamadas .get() bloqueantes ao SDK. Fazer I/O bloqueante e chamadas ao SDK dentro de um signal handler nao e async-signal-safe e pode deadlockar se o sinal chegar durante um print no loop principal. Alem disso, apos o handler zerar self._transcriber, o bloco finally do run() (L194) tambem tenta stop() — embora stop() seja idempotente via _stopped, a janela de corrida com signal existe.

**Fix recomendado:** No _handle_signal (L122-128) NAO chamar stop()/cleanup/print. Apenas: log via logger (logging tem locks proprios mas e o que ja existe; idealmente nem isso) e setar self._running = False. Mover a parada graciosa (stop()/_cleanup/_emit) para o bloco finally do run() (L193-196), que ja chama stop() e ja confia no guard _stopped para idempotencia. CAVEAT importante (por isso needs-care): setar so a flag NAO desbloqueia de forma confiavel um `for line in sys.stdin` que esteja parado num read bloqueante — por PEP 475 o CPython auto-retenta syscalls interrompidas por EINTR, entao o loop pode nao acordar prontamente quando o sidecar esta ocioso. Isso e aceitavel aqui porque o pai sempre da child.kill() (SIGKILL) como fallback final, mas deve ser documentado. Se desbloqueio imediato for desejado, registrar signal.set_wakeup_fd ou usar signal.signal sem desativar EINTR e tratar InterruptedError no read — porem isso e overkill dado o SIGKILL do pai. Manter o finally como unico ponto de stop() real; o guard _stopped ja existente garante que nao haja parada dupla. Risco do fix: baixo desde que o finally continue cobrindo o caso de saida normal do stdin (EOF) — verificar que a flag nao introduz caminho em que stop() deixe de ser chamado.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo. apps/desktop/sidecar/src/nora_stt_sidecar/__main__.py L122-128 (_handle_signal) chama self._transcriber.stop() de dentro de um signal handler do Python. stop() (transcriber.py L191-199) executa: (a) _cleanup() -> stop_transcribing_async().get() (L222), uma chamada BLOQUEANTE para o C-extension do Azure Speech SDK; e (b) _emit(StoppedMessage) -> __main__.py L31 print(..., flush=True). Signal handlers em Python rodam na main thread entre bytecodes; print() para stdout adquire o lock NAO-reentrante do BufferedWriter. Se o sinal chegar enquanto a main thread (ou um callback do SDK via _on_transcribing/_on_transcribed -> _emit -> print, que disparam com alta frequencia durante sessao ativa) ja segura esse lock, o handler chamando print() re-entra o mesmo lock -> deadlock. Fazer I/O bloqueante + chamadas ao SDK dentro de signal handler nao e async-signal-safe — anti-pattern reconhecido. A janela e estreita mas nao puramente teorica numa sessao ativa. O bloco finally (L193-195) tambem chama stop(), mas o handler ja zerou _transcriber (vira None) e stop() e idempotente via _stopped — entao essa parte do achado e corretamente auto-avaliada como inofensiva (nao e segundo bug). Severidade low esta correta: o pai (src-tauri/src/stt_sidecar.rs L523 child.kill().await = SIGKILL) sempre força a morte do filho como fallback final, e o caminho normal de parada e a mensagem 'stop' via stdin tratada no loop principal (_handle_stop), nao o sinal. Logo o deadlock so se manifesta em SIGTERM/SIGINT direto ao sidecar (Ctrl-C em dev, shutdown de process-group), e mesmo assim o SIGKILL do pai mitiga. Nenhum teste cobre o handler (grep vazio).

</details>

#### 123. 🟡 LOW — Falhas de audio/decodificacao engolidas sem feedback ao Rust

- **Local:** `L102-105 (_handle_audio) e L33-58 (_handle_start)`
- **Categoria:** error-handling · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** Em _handle_audio, exceptions de decode_audio_message ou feed() sao apenas logadas em stderr (logger.error) e descartadas — nenhum ErrorMessage e emitido ao processo Rust, que nao tem como saber que o audio esta sendo perdido (ex: PCM impar lancado por audio_pipe, ou write em stream fechado pos-race). Em _handle_start, quando start() falha, ele ja emitiu ErrorMessage internamente, mas o except aqui apenas loga e nao re-emite — comportamento ok, porem inconsistente com os demais handlers que sempre emitem.

**Fix recomendado:** Manter o tratamento do _handle_start como esta (rejeitar essa metade — start() ja emite START_FAILED; re-emitir duplicaria). Para _handle_audio, adicionar visibilidade COM supressao de flood, ja que falhas de audio sao sistematicas: emitir ErrorMessage somente na PRIMEIRA falha de uma sequencia (ou no maximo 1 por janela de tempo / a cada N falhas), distinguindo decode vs feed. Ex.: manter um contador self._audio_error_count; no except, logar sempre, mas so chamar self._emit(ErrorMessage(session_id=msg.session_id, code="AUDIO_FEED_FAILED", message=str(e))) quando self._audio_error_count == 0 ou (count % 100 == 0), e resetar o contador no _handle_start. Isso da visibilidade ao Rust/UI sem floodar o canal NDJSON. Caveat: validar que o codigo "AUDIO_FEED_FAILED" e tratado/exibido de forma neutra no listener "stt-error" do frontend para nao alarmar o usuario por um unico frame corrompido transitorio.

<details><summary>Justificativa da verificação</summary>

O codigo citado existe e o diagnostico do _handle_audio procede em parte. Em __main__.py L101-105, uma excecao de decode_audio_message (audio_pipe.py L9-10 lanca ValueError para PCM com tamanho impar; base64.b64decode tambem pode lancar) ou de self._transcriber.feed() (transcriber.py L186-189, onde push_stream.write pode lancar em stream fechado/disposed) e apenas logada com logger.error em stderr e descartada. O Rust de fato encaminha mensagens "error" pra UI via app.emit("stt-error") (stt_sidecar.rs L490-492), entao um ErrorMessage emitido aqui CHEGARIA ao usuario — confirmando que hoje uma falha de audio fica totalmente invisivel pro Rust/UI. Ate ai o achado e valido. POReM ha dois reparos importantes: (1) a metade do _handle_start e um NAO-BUG auto-admitido pelo proprio auditor ("comportamento ok, porem inconsistente") — start() ja emite ErrorMessage(code="START_FAILED") internamente (transcriber.py L68-74), entao re-emitir no _handle_start causaria DUPLO emit; isso e estilo, nao defeito, e deve ser rejeitado. (2) O fix ingenuo de "emitir ErrorMessage no except" e perigoso: frames de audio chegam em alta frequencia (dezenas/s) e falhas de decode sao sistematicas, nao transientes (se um frame esta malformado, todos os subsequentes do mesmo encoder Rust tambem estarao), entao emitir por frame floodaria o canal IPC e a UI com milhares de eventos identicos. O proprio achado reconhece isso ("possibly with rate-limit"). Alem disso, o cenario de "race write em stream fechado" e majoritariamente teorico: o loop de stdin e single-threaded (stt_sidecar.rs/__main__.py run()), entao _handle_audio e _handle_stop nao executam concorrentemente e feed() ja e guardado por "if self._push_stream and self._started". Resumo: gap de visibilidade real porem de baixo valor, fix requer rate-limit/dedup obrigatorio.

</details>

### `apps/desktop/sidecar/src/nora_stt_sidecar/logging_setup.py`  ·  1 achado(s)

#### 124. 🟡 LOW — configure() adiciona handler sem ser idempotente

- **Local:** `L5-17 (configure)`
- **Categoria:** logic-error · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** configure() faz logger.addHandler(handler) incondicionalmente. Se chamado mais de uma vez (run() chama uma vez, mas qualquer reuso/teste que importe e chame de novo, ou um re-init), acumula handlers duplicados e cada linha de log e impressa N vezes em stderr. O comentario 'Remove default handlers to avoid duplicates' e enganoso: ele so seta propagate=False, nao remove handlers previamente adicionados por configure().

**Fix recomendado:** Tornar configure() idempotente e corrigir o comentario. Opcao minima e segura: no inicio de configure(), apos obter o logger, limpar handlers antes de adicionar — `logger = logging.getLogger("nora_stt_sidecar"); logger.handlers.clear(); logger.setLevel(level); logger.addHandler(handler)`. E trocar o comentario L16 de "Remove default handlers to avoid duplicates" por algo verdadeiro, ex: "Don't propagate to the root logger (avoids duplicate output via root handlers)". Alternativa equivalente: `if logger.handlers: return` no topo (mas isso ignora um novo `level` numa segunda chamada; clear() e mais previsivel). Risco do fix e nulo no caminho atual de chamada unica: handlers esta vazio na primeira chamada, entao clear() e no-op. Nao toca nenhum padrao intencional cross-platform (e Python puro, sem relacao com WebKitGTK/drag/transparencia).

<details><summary>Justificativa da verificação</summary>

Confirmado parcialmente, com ressalvas. Em logging_setup.py L14 `logger.addHandler(handler)` e mesmo incondicional, e o comentario L16 "Remove default handlers to avoid duplicates" e factualmente errado: `logger.propagate = False` (L17) so desliga propagacao pro root logger, nao remove nenhum handler. Entao a funcao NAO e idempotente e o comentario engana. PORÉM o dano concreto descrito (cada linha impressa N vezes em stderr) NAO ocorre no codigo atual: grep mostra que `configure()` e definida 1x e chamada exatamente 1x, em __main__.py:132 dentro de run(), num sidecar de processo unico que so le stdin ate EOF/shutdown. Nao ha re-init, loop ou reuso. Os testes do projeto (tests/test_protocol.py, tests/test_transcriber_fake.py, tests/fake_sidecar.py) nao importam nem chamam configure(). Logo o bug e latente/footgun (so dispara se alguem adicionar uma 2a chamada), nao um bug ativo. Severidade low e justa; o defeito mais defensavel e o comentario enganoso, nao acumulo real de handlers. Por isso ajusto de "configure() acumula handlers" (dano presente) para "configure() nao e idempotente + comentario enganoso" (risco latente + clareza).

</details>

### `apps/desktop/sidecar/src/nora_stt_sidecar/protocol.py`  ·  1 achado(s)

#### 125. 🟡 LOW — Aliases redundantes e campos do protocolo nunca consumidos

- **Local:** `L13-18, L23, L29, L50, L57-58 (Field(alias=) redundantes) e L11-33 (sample_rate/channels/speakers_hint ignorados)`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** Varios `Field(alias="x")` usam alias identico ao nome do atributo (azure_region, auth_token, sample_rate, pcm_b64, speaker_id, offset_ms, duration_ms) — alias igual ao nome e no-op e pode ate quebrar populacao por nome dependendo de config (sem populate_by_name nao impacta aqui, mas e ruido). Mais relevante: StartMessage.sample_rate, channels e speakers_hint sao parseados mas NUNCA usados pelo transcriber (que hardcoda 16000/1 e nao passa speakers_hint ao SDK). Sao campos de contrato mortos do lado Python.

**Fix recomendado:** Dividir em dois fixes, ambos seguros (risco baixo): (1) Remover todos os Field(alias=...) onde alias==nome do atributo (L13,14,16,18,23,29,48,50,55,57,58), deixando so o campo com seu default/tipo (ex: `azure_region: str`; `sample_rate: int = 16000`; `speaker_id: str | None = None`; `offset_ms: int`; `duration_ms: int | None = None`). Como nao ha populate_by_name e os aliases coincidem com os nomes, parse via model_validate e serializacao via model_dump_json(by_alias=True) continuam identicos — testes em tests/test_protocol.py permanecem verdes sem alteracao. (2) Para os campos mortos do StartMessage: opcao A (preferivel se diarizacao multi-speaker importa) — consumir speakers_hint mapeando-o pra a config de diarizacao do SDK no _setup_transcriber; opcao B (mais simples) — remover sample_rate/channels/speakers_hint do StartMessage ja que o formato de audio e fixo (16k/16bit/mono) e o SDK nao usa o hint. NAO mexer no `channels=1` / samples_per_second=16000 hardcoded de transcriber.py: e o formato fixo do push stream Azure Speech (decisao consciente), nao deve virar parametro a menos que se implemente A. Caveat: se algum dia adicionar populate_by_name=True ou aliases divergentes (camelCase no wire), reintroduzir aliases conscientemente — hoje sao puro ruido.

<details><summary>Justificativa da verificação</summary>

Confirmei ambas as alegacoes lendo apps/desktop/sidecar/src/nora_stt_sidecar/protocol.py e cruzando consumidores. NOTA DE ESCOPO: o arquivo esta sob apps/desktop/** (sidecar do desktop), logo DENTRO de escopo — a exclusao do brief era so para services/nlp-worker. (1) ALIASES REDUNDANTES (reais): L13 azure_region, L14/L23 auth_token, L16 sample_rate, L29 pcm_b64, L48/L55 speaker_id, L50/L57 offset_ms, L58 duration_ms usam Field(alias="X") com alias identico ao nome do atributo. Nao existe model_config/ConfigDict/populate_by_name em nenhum modelo (confirmado por grep). Logo sao no-ops puros: ruido. A propria preocupacao do auditor ("pode quebrar populacao por nome") ele mesmo neutraliza ("sem populate_by_name nao impacta aqui") — nao ha quebra real, so redundancia. Importante: __main__.py:31 e os testes serializam com model_dump_json(by_alias=True); como alias==nome, remover os aliases produz JSON identico e mantem os testes verdes (test_protocol.py L98-123 afirmam chaves speaker_id/offset_ms/duration_ms). (2) CAMPOS MORTOS (reais): StartMessage.sample_rate (L16), channels (L17), speakers_hint (L18) sao parseados mas nunca consumidos — __main__.py _handle_start (L46-52) so repassa session_id/azure_region/auth_token/language ao LiveTranscriber; transcriber.py _setup_transcriber hardcoda samples_per_second=16000/bits=16/channels=1 (L96-98) e nunca mapeia speakers_hint pra config de diarizacao do SDK. VEREDITO=adjusted, nao confirmed cheio, por dois motivos honestos: (a) severidade real e baixa — nada quebra em runtime, e dead-code/contract-smell, nao bug; (b) o achado agrupa dois itens de naturezas diferentes e o item com leve relevancia funcional e so speakers_hint (cliente manda hint que o Python ignora silenciosamente = lacuna latente de feature de diarizacao, nao defeito).

</details>

### `apps/desktop/sidecar/src/nora_stt_sidecar_main.py`  ·  1 achado(s)

#### 126. 🟡 LOW — Entry-point duplicado (nora_stt_sidecar_main.py vs __main__.py)

- **Local:** `L1-4 (modulo wrapper)`
- **Categoria:** dry · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** nora_stt_sidecar_main.py apenas reimporta e chama main() de nora_stt_sidecar.__main__, duplicando o bloco `if __name__ == '__main__': raise SystemExit(main())` que ja existe em __main__.py. Os specs do PyInstaller (verifiquei sidecar-windows.spec L45) apontam para src/nora_stt_sidecar/__main__.py, nao para este arquivo — entao este wrapper nao e o entry-point do build e parece codigo orfao/redundante. Confunde sobre qual e o ponto de entrada canonico.

**Fix recomendado:** Resolver a divergência ADR-vs-código, não apenas deletar o arquivo. Como o build e o `python -m` já usam nora_stt_sidecar.__main__ (entry point real e canônico), o wrapper nora_stt_sidecar_main.py é dispensável e pode ser removido. CAVEAT: deletá-lo deixa docs/adr/0008-desktop-tauri-sidecar.md:93 incorreto, e ADRs são imutáveis depois de aceitos (non-negotiable do CLAUDE.md) — então NÃO edite o ADR no lugar; adicione nota de retificação/sucessor registrando que o entry point canônico é nora_stt_sidecar.__main__ via `python -m`/specs PyInstaller e que o wrapper foi removido. Antes de remover, dê um grep final por `nora_stt_sidecar_main` para garantir que nenhuma ferramenta externa dependa dele. Alternativa (mais trabalho, sem ganho): apontar os 3 specs e a doc para o wrapper de forma consistente. Recomendo remover o wrapper + nota no ADR. Risco de build baixo (specs não o usam), mas marcado needs-care pelo acoplamento com o ADR imutável.

<details><summary>Justificativa da verificação</summary>

Confirmei o código: apps/desktop/sidecar/src/nora_stt_sidecar_main.py (L1-4) é um wrapper de 4 linhas que faz `from nora_stt_sidecar.__main__ import main` e repete `if __name__ == "__main__": raise SystemExit(main())`, bloco que já existe em src/nora_stt_sidecar/__main__.py (L206-207). A claim central do auditor sobre os specs é VERDADEIRA: os três specs apontam para src/nora_stt_sidecar/__main__.py (sidecar-linux.spec:43, sidecar-macos.spec:39, sidecar-windows.spec:45), não para o wrapper. Também confirmei que o wrapper não é usado em lugar nenhum funcional: README.md:37 documenta `python -m nora_stt_sidecar` (que roteia por __main__.py), pyproject.toml NÃO tem [project.scripts]/console_scripts apontando pro wrapper, e build_sidecar.py só chama o spec. Ou seja, o arquivo é morto do ponto de vista de build e runtime — nada o executa. PORÉM o auditor erra ao chamá-lo de "órfão por descuido": a ÚNICA referência ao arquivo em todo o repo é docs/adr/0008-desktop-tauri-sidecar.md:93, que o designa EXPLICITAMENTE como "Entry point" canônico. Logo o problema real não é só DRY do `raise SystemExit` (trivial num arquivo de 4 linhas), e sim a DIVERGÊNCIA entre o ADR (diz que o entry point é o wrapper) e a realidade (specs/build usam __main__.py). É débito de clareza legítimo, sem impacto funcional — severidade low honesta.

</details>


## 5. Config / Build

### `apps/desktop/src-tauri/tauri.conf.json`  ·  4 achado(s)

#### 127. 🔴 HIGH — URL da API de producao hardcoded como http://localhost:8080 e lida em compile-time via include_str!

- **Local:** `L64-68 (plugins.nora.apiBaseUrl) + src-tauri/src/lib.rs:27-47 (api_base_url)`
- **Categoria:** tech-debt · **Risco do fix:** needs-care · **Veredito:** confirmed

**Problema:** apiBaseUrl em tauri.conf.json e 'http://localhost:8080' e lib.rs faz include_str!("../tauri.conf.json") em compile-time. Nao ha override por env var nem por build profile. Qualquer bundle gerado pelo CI (desktop-bundle no push em main, ci.yml L158) aponta o desktop 'deployed' para localhost. O CLAUDE.md afirma que o produto esta deployed no Azure desde 2026-05-13, entao o app distribuido nunca alcanca o backend real. Como a leitura e compile-time + OnceLock, nem da pra trocar em runtime.

**Fix recomendado:** Parametrizar a base URL por build sem quebrar o fluxo dev local. Opcao recomendada (idiomatica neste repo, que ja usa NORA_SIDECAR_PATH em stt_sidecar.rs:68): em `src-tauri/build.rs`, ler `NORA_API_BASE_URL` e expor via `println!("cargo:rustc-env=NORA_API_BASE_URL={}", ...)`; em `api_base_url()` usar `option_env!("NORA_API_BASE_URL")` como primeira fonte e cair no valor de tauri.conf.json (ou em localhost so sob `#[cfg(debug_assertions)]`) quando ausente. No CI `desktop-bundle` (ci.yml), passar `NORA_API_BASE_URL` (de secret/variavel de repo) no `env:` do passo "Build Tauri app" (L222-227), ao lado dos TAURI_SIGNING_*. Caveats: (1) `cargo:rustc-env` so re-injeta se o build.rs reexecutar — adicionar `println!("cargo:rerun-if-env-changed=NORA_API_BASE_URL")` no build.rs para invalidar cache quando a var muda; (2) manter o `expect("Invalid apiBaseUrl...")` em lib.rs:60 para falhar cedo se a env vier malformada; (3) NAO mexer na CSP do webview (L61) — ela nao afeta o http_proxy, mas se algum dia o renderer fizer fetch direto para o backend remoto a CSP precisaria do host real; fora de escopo deste fix; (4) preferir build.rs + env a "gerar tauri.conf.json no CI" para nao introduzir mutacao de arquivo versionado no pipeline. Alternativa minima (menor esforco, aceitavel para academico): apenas adicionar o passo de override no CI antes do `tauri build`, mas a abordagem env/build.rs e mais limpa e testavel localmente.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo exato. `tauri.conf.json:66` define `plugins.nora.apiBaseUrl = "http://localhost:8080"`. `lib.rs:27-47` (`api_base_url()`) le esse valor em compile-time via `include_str!("../tauri.conf.json")` (L30) e o memoiza num `OnceLock` (L28) — sem leitura de env var, sem build profile, sem fallback condicional. Em `lib.rs:56-68` o valor parseado vira `http_proxy::ApiBaseUrl(base_url)` gerenciado pelo Tauri. Esse `ApiBaseUrl` e a UNICA origem de destino do `http_proxy` (http_proxy.rs:62-68), que e o gateway central por onde o renderer faz TODAS as chamadas ao backend (auth, speech token em commands.rs:47-55, upload em commands.rs:253, live analysis em live_analysis.rs:77). Logo, qualquer bundle aponta para localhost:8080 e nao alcanca o backend Azure. A clausula `unwrap_or` (L43) so muda nada porque o JSON sempre tem a chave. A leitura compile-time + OnceLock realmente impede troca em runtime (confirmado: nenhum `env!`/`option_env!`/`std::env::var` para a URL em todo src/). O claim de CI tambem procede: o job `desktop-bundle` (ci.yml:158-238) roda em `push` em main (L160), executa `npx tauri build` (L227) SEM nenhum passo que sobrescreva apiBaseUrl, e publica .deb/.AppImage/.msi/.dmg como artifacts (L229-238) — todos com localhost embutido. Severidade high: defeito que torna qualquer artefato distribuivel inutil contra o backend real, sem workaround em runtime. Ressalva honesta de altitude: e gap de config/release, nao bug de logica nem falha de seguranca; o desktop e operado por colaborador separado e os artifacts sao apenas upload de CI (nao ha publish/release visivel), entao o impacto pratico depende de quando o desktop for de fato distribuido. CSP em tauri.conf.json:61 so libera localhost no connect-src, mas isso NAO afeta o proxy (reqwest no Rust nao passa pela CSP do webview) — apenas reforca que a config inteira foi escrita para dev local.

</details>

#### 128. 🟡 LOW — $schema aponta para URL remota fake (raw.githubusercontent.com/nora/desktop) em vez do schema gerado local

- **Local:** `L2 ($schema)`
- **Categoria:** professionalism · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** O $schema referencia https://raw.githubusercontent.com/nora/desktop/main/src-tauri/gen/schemas/desktop-schema.json. Esse repo/path provavelmente nao existe (o identifier do produto e com.nora.desktop mas nao ha org 'nora' publica garantida), entao a validacao/intellisense do editor falha silenciosamente ao buscar 404. O Tauri gera localmente gen/schemas/desktop-schema.json.

**Fix recomendado:** Trocar L2 para o schema local gerado, caminho relativo a src-tauri/: "$schema": "gen/schemas/desktop-schema.json". E o padrao do tauri init, resolve offline e da IntelliSense correto. Caveat: o Tauri gera tanto desktop-schema.json quanto linux-schema.json; ambos sao identicos aqui (mesmo tamanho), entao desktop-schema.json e a escolha certa (padrao do tauri init). Nenhum risco de regressao — $schema nao afeta build nem runtime do Tauri.

<details><summary>Justificativa da verificação</summary>

Confirmado lendo o arquivo: L2 de apps/desktop/src-tauri/tauri.conf.json tem "$schema": "https://raw.githubusercontent.com/nora/desktop/main/src-tauri/gen/schemas/desktop-schema.json". Esse repo publico nao existe (identifier e com.nora.desktop mas nao ha org 'nora' garantida no GitHub), entao a URL retorna 404 e o IntelliSense/validacao do editor falha silenciosamente. O schema gerado localmente EXISTE: gen/schemas/desktop-schema.json (133KB, mais linux-schema.json). O dano e concreto porem estritamente de developer-experience: $schema so e consumido por editores JSON, nunca pelo Tauri em build/runtime, logo nenhum impacto funcional nem cross-platform. Severidade low honesta. Fix e safe: nao toca runtime nem os padroes intencionais WebKitGTK (startDragging, decorations:false, backgrounds solidos).

</details>

#### 129. 🟡 LOW — CSP com 'unsafe-inline' em script-src amplifica risco de XSS sobre os comandos de secret

- **Local:** `L61 (security.csp)`
- **Categoria:** security · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** A CSP define `script-src 'self' 'unsafe-inline'`. 'unsafe-inline' em script-src anula boa parte da protecao contra XSS: qualquer injecao de <script> ou handler inline executa. Combinado com secret_get exposto ao renderer (secrets.rs), um XSS consegue exfiltrar o access-token. Em app desktop o vetor e menor (conteudo e local), mas o app renderiza dados vindos do backend/transcripts, entao nao e zero.

**Fix recomendado:** Hardening valido (defense-in-depth, severidade baixa), nao bug. Trocar script-src de `'self' 'unsafe-inline'` para `'self'` em tauri.conf.json:61 — seguro para o output de producao porque Vite emite apenas bundles externos type=module e nao ha scripts/handlers inline no codigo. Caveats: (a) tambem remover 'unsafe-inline' de default-src para completar o hardening (script-src ja sobrescreve, mas default-src com 'unsafe-inline' e folga desnecessaria); (b) MANTER `style-src 'unsafe-inline'` — index.html tem <style> critico inline e Tailwind precisa; (c) apos a mudanca, LANCAR o app (main + overlay + dock) e confirmar que a IPC bridge do Tauri 2 inicializa e que invoke() funciona — se a bootstrap injetar script inline, usar a config de CSP do Tauri que aplica hash/nonce automatico ao inves de manter 'unsafe-inline'; (d) idealmente separar CSP dev (precisa de http://localhost:*/ws para HMR) de prod num passo futuro, fora do escopo deste achado.

<details><summary>Justificativa da verificação</summary>

Os fatos batem: tauri.conf.json:61 tem `script-src 'self' 'unsafe-inline'` e secret_get (secrets.rs:72) expoe o access-token ao renderer (auth.ts:152). Um XSS no renderer poderia chamar invoke('secret_get',{key:'access-token'}) e exfiltrar. E o build de producao confirma que NAO ha scripts inline: dist/index.html, dist/overlay.html e dist/dock.html so referenciam bundles externos type=module; nao ha onclick/handlers inline nos HTMLs nem innerHTML/dangerouslySetInnerHTML no src React. Logo remover 'unsafe-inline' de script-src e tecnicamente viavel sem quebrar o output. POReM: (1) severidade e LOW, nao medium — desktop carrega so assets locais bundlados (sem origem remota no frontendDist), e o vetor XSS citado ('renderiza transcripts/backend') e especulativo: React auto-escapa e nao existe sink de DOM injection no codigo, entao o risco residual removido e pequeno; e hardening defense-in-depth legitimo, nao bug. (2) O fix como escrito e incompleto: a CSP tambem tem `default-src 'self' 'unsafe-inline'`; como script-src e explicito, ele sobrescreve default-src para scripts (ok), mas o default-src com 'unsafe-inline' fica como folga e idealmente deveria ser tightened tambem. (3) Precisa validar empiricamente que a bootstrap IPC do Tauri 2 ainda inicializa apos a mudanca (rodar o app), nao apenas assumir. style-src 'unsafe-inline' DEVE permanecer (Tailwind + <style>/CSS de boot no index.html) — o fix corretamente preserva.

</details>

#### 130. 🟡 LOW — CSP permite 'unsafe-inline' em script-src e default-src

- **Local:** `L60-62 (app.security.csp)`
- **Categoria:** security · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** A CSP inclui 'unsafe-inline' em default-src e em script-src. Isso enfraquece a protecao contra XSS caso conteudo de terceiros (ex: transcricoes/markdown de reunioes renderizados via react-markdown em meeting-detail) chegue a injetar markup. O app nao faz connect remoto direto do webview (tudo passa pelo http_proxy em Rust), o que limita exfiltracao, mas script-src 'unsafe-inline' continua sendo o vetor classico. Por isso severity low e nao high.

**Fix recomendado:** Direcao do fix esta correta, mas com ressalvas: 1) MANTER `style-src 'unsafe-inline'` — obrigatorio, pois ha <style> inline nos 3 HTML (index.html L7-82, overlay.html/dock.html L7-10) e style={{}} inline em 10+ componentes React (App.tsx, meeting-detail.tsx, overlay-notifications.tsx etc.). 2) Remover 'unsafe-inline' de default-src e seguro: script-src tem diretiva propria (nao herda de default-src) e os demais tipos (connect/img/style/font) ja tem diretivas explicitas. 3) CAVEAT CRITICO nao citado pelo auditor: Tauri usa uma unica CSP pra dev e prod, e o Vite React Fast Refresh (npm run dev, localhost:1420) injeta scripts inline em dev — remover 'unsafe-inline' de script-src pode quebrar o dev server. Antes de mergear, validar `npm run tauri dev` ou separar CSP de dev/prod (ex: csp diferente via condicional de build). Como reforco real, considerar tambem fixar versoes e nao adicionar rehype-raw ao react-markdown no futuro. Por ser endurecimento de defense-in-depth de baixo impacto com risco de regressao em dev, tratar como melhoria opcional, nao bloqueante.

<details><summary>Justificativa da verificação</summary>

Confirmado no codigo: apps/desktop/src-tauri/tauri.conf.json L60-62 traz `default-src 'self' 'unsafe-inline' ...` e `script-src 'self' 'unsafe-inline'`. Existe sim renderizacao de conteudo nao-confiavel via react-markdown (meeting-detail.tsx:522, `<ReactMarkdown>{analysis.summary}</ReactMarkdown>` sobre conteudo derivado de LLM/transcricao). PORÉM a premissa de "vetor XSS classico" do auditor esta fortemente atenuada: react-markdown v9 NAO renderiza HTML bruto por default — exigiria rehype-raw, que nao existe em lugar nenhum (grep por rehype/remark/dangerouslySetInnerHTML/allowDangerous = vazio). Logo, hoje nao ha caminho pratico de injetar <script> ou handlers inline. Isso e fraqueza de defense-in-depth, nao bug explorável — consistente com severity low. As 3 entradas HTML (index/overlay/dock) usam apenas `<script type="module" src=...>` (sem script inline), entao remover 'unsafe-inline' de script-src nao quebra os scripts proprios em producao. Ajusto o veredito porque (a) o dano concreto descrito esta exagerado dado o sanitize default do react-markdown e (b) o fix tem caveat real nao mencionado.

</details>

### `apps/desktop/package.json`  ·  1 achado(s)

#### 131. 🟡 LOW — react-router-dom declarado como dependency mas nunca importado

- **Local:** `L19 react-router-dom`
- **Categoria:** dead-code · **Risco do fix:** safe · **Veredito:** confirmed

**Problema:** grep -rln 'react-router' src/ nao retorna nada. O roteamento da janela main e feito por estado proprio em App.tsx (imports de pages/* sem Router). A dependencia so adiciona peso ao node_modules e ruido no lockfile/audit.

**Fix recomendado:** Remover a linha "react-router-dom": "^6.26.0" de dependencies em apps/desktop/package.json (atencao a virgula da linha anterior, react-markdown L18, que vira o ultimo item do objeto). Em seguida regenerar o lockfile rodando npm install no local correto (verificar se o projeto usa lockfile proprio em apps/desktop ou um lockfile de monorepo na raiz) e revisar/commitar o diff resultante do package-lock.json. Risco de quebra praticamente nulo, ja que nada no codigo referencia o pacote.

<details><summary>Justificativa da verificação</summary>

Confirmado. Em /home/pollo/Dev/nora/apps/desktop/package.json L19, "react-router-dom": "^6.26.0" esta declarado como dependency. grep abrangente (src/ e arvore inteira excluindo node_modules/dist) nao encontra nenhum import de react-router nem uso de qualquer API (BrowserRouter/HashRouter/Routes/useNavigate/useParams/useLocation/Link/RouterProvider). As unicas ocorrencias fora de node_modules sao a propria declaracao no package.json e seu eco no package-lock.json. O roteamento da janela main e feito por estado em src/App.tsx, que importa as paginas diretamente de @/pages/* (login, meetings, meeting-detail, settings, chat) sem nenhum wrapper de Router. Logo a dependencia e codigo morto real: so adiciona peso ao node_modules e ruido em lockfile/audit. Dano concreto e baixo (nao e bug de runtime nem seguranca), mas o achado e legitimo e acionavel.

</details>

### `apps/desktop/src-tauri/Cargo.toml`  ·  1 achado(s)

#### 132. 🟡 LOW — Dependencias Rust declaradas mas nao usadas no codigo da fatia (futures, tokio-util, x11, raw-window-handle)

- **Local:** `L29 futures, L32 tokio-util, L36 x11, L37 raw-window-handle`
- **Categoria:** dead-code · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** Grep em src-tauri/src/ nao encontra nenhum uso de `futures::`/`use futures` (so `tokio::select!`), `tokio_util::`/`CancellationToken`, `x11`/`Xlib`/`XOpen`, nem `raw_window_handle`/`HasRawWindowHandle`. As fingerprints do target/ confirmam que sao compiladas como deps diretas mas sem call-sites. x11 e raw-window-handle ainda inflam o build Linux (x11 puxa libs nativas) sem retorno. tokio-util e futures aumentam tempo de compile e superficie de supply-chain a toa.

**Fix recomendado:** Limpeza de manifesto, nao bug. Remover SO `futures = "0.3"` (L29) e `tokio-util = { ... }` (L32) — sao deps diretas sem call-site e sem reverse-dep transitivo, entao saem de fato do build graph. Antes de remover, rodar `cargo build` (idealmente por plataforma, pois sao deps cross-platform e pode haver feature gate nao-Linux) pra confirmar que nada quebra. Para x11 (L36) e raw-window-handle (L37): tecnicamente tambem nao tem call-site, mas remove-las do bloco [target.linux] NAO reduz build nem supply-chain (continuam vindo transitivamente via tauri/wry/tao). Removivel por higiene, sem urgencia e sem o beneficio alegado. Caveat: confirmar que nenhum modulo Linux-only futuro pretende usar essas APIs antes de deletar; manter o `libc` Linux (L35) que pode ter uso indireto/futuro — nao mexer nele sem checar.

<details><summary>Justificativa da verificação</summary>

Confirmei que as 4 deps (futures L29, tokio-util L32, x11 L36, raw-window-handle L37) NAO tem call-site nenhum em src/: grep por `use futures`/`use tokio_util`/`use x11`/`use raw_window_handle`/`extern crate` em todos os 13 .rs + build.rs retorna vazio (exit 1). O unico `tokio::select!` (stt_sidecar.rs L246/292/450) vem do crate `tokio`, nao de tokio-util nem futures. stealth_mode.rs no path Linux explicitamente retorna "only supported on Windows" e nao usa x11/raw-window-handle; windows.rs so usa `tauri`. Ate aqui o fato "declaracao morta" eh real pras 4. PORÉM o `cargo tree -i` desmonta metade da justificativa do auditor: (1) futures e tokio-util tem como UNICO reverse-dep o proprio nora-desktop — sao 100% superfluas, remove-las realmente tira do build graph (ganho real, embora pequeno). (2) x11 e raw-window-handle JA sao puxadas transitivamente: x11 via gdkx11->wry->tauri-runtime-wry->tauri (feature "x11" default) e via gdkx11-sys->tao; raw-window-handle via tao/wry/tauri-runtime/tauri. Logo as afirmacoes "x11 ainda inflam o build Linux / puxa libs nativas sem retorno" e "aumentam superficie de supply-chain a toa" sao FALSAS pra essas duas: as libs nativas (libX11) e o tempo de compile vem do tauri de qualquer jeito. Inclusive a feature `xlib` declarada eh subconjunto do `default` que o x11 ja recebe transitivamente. Entao remover x11/raw-window-handle eh limpeza cosmetica de manifesto com zero beneficio de build/seguranca. Severity rebaixada de medium->low: nao ha bug, nao ha risco de seguranca, e o ganho real (futures+tokio-util) eh marginal num projeto FIAP/dev.

</details>

### `apps/desktop/src-tauri/capabilities/default.json`  ·  2 achado(s)

#### 133. 🟡 LOW — Permissoes de janela concedidas mas nunca usadas (maximize/unmaximize)

- **Local:** `L11-12 (core:window:allow-maximize, core:window:allow-unmaximize)`
- **Categoria:** security · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** default.json concede allow-maximize e allow-unmaximize, mas o JS so chama win.toggleMaximize() (titlebar.tsx L74,119,90), win.minimize() (L114) e win.close() (L124) — grep confirma zero chamadas diretas .maximize()/.unmaximize(). toggleMaximize e gated por allow-toggle-maximize (ja presente, L11... na verdade L11 e toggle), entao as duas permissoes individuais sao superficie concedida sem uso. Nao e perigoso, mas viola minimo-privilegio e gera ruido na auditoria de capabilities.

**Fix recomendado:** Remover as duas linhas nao usadas de /home/pollo/Dev/nora/apps/desktop/src-tauri/capabilities/default.json (L10 `"core:window:allow-maximize"` e L11 `"core:window:allow-unmaximize"`). Manter `allow-toggle-maximize` (L12), `allow-minimize` (L9), `allow-close` (L13), `allow-start-dragging` (L7) e `allow-start-resize-dragging` (L8), que sao os efetivamente chamados. Risco zero: remover permissao de um comando que nunca e invocado nao altera runtime, pois `toggleMaximize` usa seu proprio permission. Caveat: se houver intencao futura de expor botoes separados de maximizar/restaurar (em vez de toggle), as permissoes precisariam voltar — mas isso e easy add quando o codigo realmente chamar `.maximize()`/`.unmaximize()`. Aplicar so se a equipe quiser higiene estrita de capabilities; nao e bloqueante.

<details><summary>Justificativa da verificação</summary>

Confirmei o fato no arquivo. /home/pollo/Dev/nora/apps/desktop/src-tauri/capabilities/default.json concede `core:window:allow-maximize` (L10) e `core:window:allow-unmaximize` (L11). Grep literal em todo src/ e src-tauri/ confirma ZERO chamadas de `.maximize()` ou `.unmaximize()` (JS ou Rust). As unicas chamadas de janela sao: `toggleMaximize()` (titlebar.tsx L74), `minimize()` (L114), `close()` (L124), `startDragging()` (titlebar L68, dock-bar L126), `startResizeDragging()` (resize-handles L54). O `toggleMaximize()` e gated pelo seu proprio permission `core:window:allow-toggle-maximize` (ja presente L12) — no Tauri 2 toggleMaximize e um comando distinto que NAO depende de allow-maximize/allow-unmaximize. Logo as duas permissoes sao superficie concedida sem uso real.

Ajustes ao achado: (1) a numeracao de linhas do auditor esta deslocada — no arquivo real allow-maximize/allow-unmaximize estao em L10-L11 (nao L11-12); allow-toggle-maximize esta em L12, allow-close em L13. (2) Severidade "low" esta correta, mas a categoria "security" e generosa: o impacto pratico de poder maximizar/restaurar a propria janela e nulo do ponto de vista de seguranca real — e mais higiene de minimo-privilegio / limpeza de config do que vetor de ataque. Um atacante com injecao de JS no webview teria capacidades muito piores; maximizar janela e acao de UI benigna. Por isso ajusto de "bug de seguranca" para "cleanup de least-privilege" e mantenho severity low.

</details>

#### 134. 🟡 LOW — Capability unica concede shell:allow-open e controles de janela (maximize/close) a todas as 3 janelas indistintamente

- **Local:** `L4 windows + L13 shell:allow-open`
- **Categoria:** security · **Risco do fix:** needs-care · **Veredito:** adjusted (severidade ajustada)

**Problema:** A capability 'default' aplica o mesmo conjunto de permissoes a main, overlay e dock. shell:allow-open (abrir URL/arquivo externo via shell) e necessario so onde ha link externo (overlay -> blackhole), mas e concedido tambem a dock e main. Da mesma forma allow-maximize/unmaximize/toggle-maximize sao concedidos a dock, que e fixo em 320x60 e nao-resizable (maximize nao faz sentido ali). Permissao mais ampla que o necessario por janela.

**Fix recomendado:** Fix minimo e mais seguro que o proposto: (1) Remover shell:allow-open inteiramente de default.json (L14) — esta morto, ninguem usa o comando open do plugin-shell; o link blackhole continua abrindo via <a target=_blank> sem ele. (2) Restringir maximize/unmaximize/toggle-maximize a 'main' (unica que renderiza Titlebar com toggleMaximize). Forma robusta no Tauri 2: manter a capability default com as permissoes comuns (core:default, start-dragging, start-resize-dragging, minimize, close, shell:allow-execute do sidecar) em windows:[main,overlay,dock], e criar capabilities/main.json com windows:[main] contendo apenas core:window:allow-maximize/unmaximize/toggle-maximize. CAVEAT/risco: ao separar arquivos, garantir que cada janela continue listada em pelo menos uma capability para os controles que precisa — se 'main' for omitida da capability que tem minimize/close/drag, a titlebar quebra. Testar manualmente nas 3 janelas (drag, resize handles na overlay, minimize/close na main, dock fixa) apos a mudanca, pois capability mal particionada falha silenciosamente em runtime, nao em build. NAO seguir literalmente a sugestao do achado de 'overlay.json com shell:allow-open' — isso reintroduziria a permissao morta. Prioridade baixa; pode ser feito junto de outra mudanca em capabilities.

<details><summary>Justificativa da verificação</summary>

A observacao de over-provisioning e real, mas a justificativa do auditor esta parcialmente errada. Confirmado em default.json: uma unica capability 'default' (L4 windows:[main,overlay,dock]) concede o mesmo set a todas as 3 janelas, incluindo allow-maximize/unmaximize/toggle-maximize (L10-12) e shell:allow-open (L14). Verifiquei o comportamento real das janelas em tauri.conf.json: a dock e 320x60 com resizable:false e maxWidth/maxHeight travados em 320/60 — maximize ali nao faz sentido. O unico consumidor de maximize e o titlebar.tsx (win.toggleMaximize, L74), que so e renderizado em App.tsx <Titlebar/> = janela 'main'. Entao maximize so deveria ir pra main. POReM o ponto central do auditor sobre shell:allow-open esta incorreto: ele afirma que 'shell:allow-open e necessario so onde ha link externo (overlay -> blackhole)'. O link blackhole (overlay.tsx:809) e um <a href target=_blank rel=noopener>, que e tratado pelo webview/OS, NAO pelo comando open do plugin-shell. Confirmei: nao ha nenhum import de open de @tauri-apps/plugin-shell, nenhum invoke de shell open, nada em TS nem Rust (lib.rs:63 so inicializa o plugin pro sidecar via shell:allow-execute). Logo shell:allow-open e dead permission em TODAS as 3 janelas, inclusive na overlay — nao 'necessaria na overlay' como o achado diz. Dano concreto e baixo: app local, single-tenant, desktop, sem superficie remota; o OS simplesmente ignora maximize numa janela non-resizable e a permissao shell:allow-open ociosa nao e explorada por nenhuma origem remota (sem CSP-bypass aqui). E higiene de least-privilege, nao um bug exploravel — por isso severity low confirmada e verdict 'adjusted' (premissa do fix corrigida).

</details>

### `apps/desktop/tsconfig.json`  ·  1 achado(s)

#### 135. 🟡 LOW — package.json roda 'tsc -b' (build mode) mas nao existe tsconfig de project references nem tsconfig.node.json

- **Local:** `L1-24 (arquivo inteiro)`
- **Categoria:** tech-debt · **Risco do fix:** safe · **Veredito:** adjusted (severidade ajustada)

**Problema:** O script build e 'tsc -b && vite build'. O modo -b (build/composite) e feito pra project references, mas o tsconfig.json nao tem "references" nem "composite": true, e nao existe tsconfig.node.json (comum em scaffolds Vite pra tipar vite.config.ts). Na pratica 'tsc -b' num tsconfig sem references so faz um typecheck do projeto unico (funciona, mas e uso atipico do -b e nao cobre vite.config.ts). O vite.config.ts fica fora de qualquer include (tsconfig inclui apenas ["src"]) entao erros de tipo nele passam batido.

**Fix recomendado:** Opcao A (recomendada, risco zero): trocar package.json:8 de "tsc -b && vite build" para "tsc --noEmit && vite build". Como noEmit:true e nao ha references, isso e funcionalmente equivalente ao typecheck atual do src, so com invocacao idiomatica (sem o uso atipico do -b nem o tsconfig.tsbuildinfo gerado). Nao muda runtime nem cobertura, apenas remove a estranheza. Opcao B (resolve tambem o gap do vite.config.ts, mais trabalho): adotar o par do template oficial do Vite — tsconfig.json com "references":[{"path":"./tsconfig.node.json"}] + "composite":true, e criar tsconfig.node.json com composite:true e include:["vite.config.ts"], mantendo o build como "tsc -b". Isso passa a tipar vite.config.ts. Caveat da B: composite exige cuidado com emit/declaration e gera mais tsbuildinfo; so vale se quiserem de fato checar a config de build. Dada a severidade baixa, prefira a Opcao A. Nenhuma das opcoes toca em padroes cross-platform intencionais (drag/resize, backgrounds solidos, decorations:false) — e puramente tooling de typecheck.

<details><summary>Justificativa da verificação</summary>

Confirmei todas as alegacoes factuais. package.json:8 = "tsc -b && vite build". tsconfig.json (L1-24) nao tem "references" nem "composite:true" (grep count 0 pra ambos) e include e apenas ["src"]. Nao existe tsconfig.node.json — so tsconfig.json e o tsconfig.tsbuildinfo (gitignored). Verifiquei empiricamente: (1) `tsc -b` roda limpo (exit 0) e gera tsbuildinfo, ou seja, o build NAO esta quebrado — num projeto unico nao-composite o -b so faz um typecheck do src; (2) injetei um erro de tipo deliberado em vite.config.ts (`const x: number = "string"`) e `tsc -b` passou com exit 0, comprovando que vite.config.ts fica fora de qualquer checagem de tipo. Entao o achado e real e preciso: uso atipico do -b + config de build (vite.config.ts) sem cobertura de tipos. Ajusto para "adjusted" porque o impacto pratico e baixissimo (vite.config.ts e pequeno, estavel e raramente editado; nada quebra hoje) — a severidade low/tech-debt do auditor esta correta, mas vale registrar que o dano concreto e so "erros de tipo em vite.config.ts passam batido", nao falha de build.

</details>

---

## Notas

- **1 refactor marcado como `risky` e deliberadamente NÃO recomendado pela síntese:** o split do `overlay.tsx` (1925 linhas). É preocupação de tamanho, não bug; quebrar o arquivo é churn de alto risco de regressão e fica como opcional/PR dedicado.
- Os 20 achados rejeitados na verificação adversarial não constam aqui por terem sido classificados como falsos-positivos, nitpicks subjetivos ou código já correto.
- Padrões intencionais (que NÃO são bugs) foram excluídos por construção: `startDragging()` em vez de `-webkit-app-region: drag` (WebKitGTK/Linux), backgrounds sólidos na overlay, `decorations:false` + titlebar custom.
