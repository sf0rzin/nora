# Samples — Transcrições Mínimas

Transcrições **fictícias** muito curtas (1–2 KB), simplificadas, para uso em testes unitários do worker NLP e em smoke tests do upload.

Para o dataset completo (12 transcrições mais ricas, 4–7 KB cada, em `.txt`/`.vtt`/`.srt`), ver `data/synthetic/`.

| Arquivo | Tipo | Cenário |
|---|---|---|
| `sample-short-decision.txt` | Plain | Decisão técnica de stack |
| `sample-short-upsell.txt` | Plain | Sinal de upsell comercial |
| `sample-short-risk.srt` | SubRip | Sinal de risco de churn |
| `smoke-e2e-transcript.txt` | Plain | Entrada do `scripts/smoke-e2e.sh`: contém uma decisão, um action item com responsável e prazo, e um risco — o smoke falha se a análise não devolver os três |

Política: nenhum dado real. Nomes, empresas e CNPJs são fictícios. Esses arquivos devem passar pelo PII Shield sem falhas — se quebrarem, é bug no redactor.

Essa política agora é verificada, e não apenas declarada: `services/nlp-worker/tests/test_pii_over_transcripts.py` roda o PII Shield sobre cada arquivo daqui e de `data/synthetic/meetings/`, e afirma que todo locutor cujo nome está nas listas do shield desaparece do texto redigido. Até então nenhum teste referenciava este diretório — só o `scripts/smoke-e2e.sh`.
