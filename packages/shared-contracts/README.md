# shared-contracts

Contracts shared between NORA services. This directory centralises definitions that need to be aligned across multiple languages / runtimes (Java backend, Python worker, TypeScript web/desktop).

## Current content

- `pii-types.json` — canonical enum of redacted PII types (aligned with ADR 0012 + `services/nlp-worker/src/nora_nlp/models.py:PiiType`)
- `processing-status.json` — enum of meeting processing statuses (aligned with `services/api/.../domain/meeting/ProcessingStatus.java`)
- `error-codes.md` — convention for the error codes emitted by the backend and expected by the client

## How to use

For now, each service **mirrors** these definitions in its own code (Java enum, Pydantic Enum, TS union). This package serves as the **documentary source of truth** — divergences between clients must be resolved by bringing them all here.

Automatic code generation (json-schema → types) is a debt of Sub-phase 1.12 (ADR 0016).

## Why not empty?

An audit identified that the previous directory with only a `.gitkeep` was confusing: the documentation did not reference the state, devs saw it and ignored it. Now at least the **enum value lists** that need to match between services are centralised — when a new aggregate is added, we remember to propagate it to all the clients.
