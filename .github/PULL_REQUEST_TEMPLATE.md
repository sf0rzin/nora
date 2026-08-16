# PR — NORA

## Summary

> What does this PR deliver? (1–3 lines)

Issue / Story: `#`

## Main Changes

- [ ]
- [ ]

## How to Test

```bash
# commands
```

## Checklist

- [ ] Follows the standards in `docs/engineering/standards.md`
- [ ] Commit messages in **English**, in Conventional Commits format (see `AGENTS.md` › How we work)
- [ ] Multi-tenancy preserved (`tenant_id` filter or RLS) — when applicable
- [ ] PII Shield applied to user input before the LLM (ADR 0012) — when applicable
- [ ] No secrets in the diff
- [ ] Tests added or updated
- [ ] Contracts updated (`docs/api/openapi.yaml` / `docs/api/examples/*.json`) if the API changed
- [ ] LLM schema versioned (`docs/api/llm-schemas/*-vN.schema.json`) if the worker output changed
- [ ] ADR created if the decision is durable (see `docs/adr/README.md`)
- [ ] CI green

## Notes for the Reviewer

>
