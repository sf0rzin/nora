# Samples — Transcrições Mínimas

Transcrições **fictícias** muito curtas (1–2 KB), simplificadas, para uso em testes unitários do worker NLP e em smoke tests do upload.

Para o dataset completo (12 transcrições mais ricas, 4–7 KB cada, em `.txt`/`.vtt`/`.srt`), ver `data/synthetic/`.

| Arquivo | Tipo | Cenário |
|---|---|---|
| `sample-short-decision.txt` | Plain | Decisão técnica de stack |
| `sample-short-upsell.txt` | Plain | Sinal de upsell comercial |
| `sample-short-risk.srt` | SubRip | Sinal de risco de churn |

Política: nenhum dado real. Nomes, empresas e CNPJs são fictícios. Esses arquivos devem passar pelo PII Shield sem falhas — se quebrarem, é bug no redactor.
