# NORA — official development commands.
# Use this Makefile as the source of truth for "how to run the thing".

SHELL := bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/docker/docker-compose.yml --env-file .env.local

# Path to the worker venv. The concrete python is resolved at runtime
# (Windows uses .venv/Scripts/python.exe; Unix uses .venv/bin/python).
WORKER_VENV := $(CURDIR)/services/nlp-worker/.venv
WORKER_PYTHON_RESOLVE := if [ -x "$(WORKER_VENV)/Scripts/python.exe" ]; then echo "$(WORKER_VENV)/Scripts/python.exe"; else echo "$(WORKER_VENV)/bin/python"; fi
# System python used to create the venv. Default `python3` (Ubuntu 22+/macOS 14+
# no longer ship the `python` symlink). Override: `make ... PYTHON=python3.12`.
PYTHON ?= python3

.PHONY: help
help: ## List the available commands
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# --- Bootstrap ---

.PHONY: env
env: ## Create .env.local at the root and in each service from the examples
	@[ -f .env.local ] || cp .env.example .env.local
	@[ -f services/api/.env.local ] || cp services/api/.env.example services/api/.env.local
	@[ -f services/nlp-worker/.env.local ] || cp services/nlp-worker/.env.example services/nlp-worker/.env.local
	@[ -f apps/web/.env.local ] || cp apps/web/.env.example apps/web/.env.local
	@[ -f apps/desktop/.env.local ] || cp apps/desktop/.env.example apps/desktop/.env.local
	@echo "OK — .env.local files created (review before using in production)."

# --- Infra local ---

.PHONY: db-up
db-up: ## Start Postgres + Adminer
	$(COMPOSE) up -d

.PHONY: db-down
db-down: ## Stop Postgres + Adminer
	$(COMPOSE) down

.PHONY: db-reset
db-reset: ## Drop the volume and start the database from scratch
	$(COMPOSE) down -v
	$(COMPOSE) up -d

# --- Dev completo ---

# Directory holding the logs and PIDs of the processes launched by `make dev`.
# We use $(CURDIR) for absolute paths -- it avoids cwd problems in sub-shells.
DEV_RUN_DIR := $(CURDIR)/.run
DEV_LOG_DIR := $(CURDIR)/.logs

.PHONY: worker-setup
worker-setup: ## Create the worker venv and install its dependencies (idempotent)
	@WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); \
	if [ ! -x "$$WORKER_PY" ]; then \
		echo ">> creating the worker venv at $(WORKER_VENV)..."; \
		cd services/nlp-worker && $(PYTHON) -m venv .venv; \
		WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); \
		"$$WORKER_PY" -m pip install -q --upgrade pip; \
		echo ">> installing the local nlp-baseline package (ADR 0010)..."; \
		"$$WORKER_PY" -m pip install -q -e "$(CURDIR)/packages/nlp-baseline"; \
		"$$WORKER_PY" -m pip install -q -e ".[dev]"; \
		echo ">> worker venv ready."; \
	fi

.PHONY: web-setup
# `npm ci`, not `npm install`. The guard already makes this first-install-only, so nothing
# is gained by letting npm resolve fresh — and `npm install` REWRITES package-lock.json:
# measured on a clean worktree, it produced 60 deletions on this lock, stripping `libc`
# fields written by a newer npm. A setup target that dirties the tree teaches people to
# `git checkout` the lock, which is how a real dependency change gets discarded one day.
# `npm ci` is deterministic, never touches the lock, and fails loudly if it and
# package.json disagree — which is a thing worth knowing rather than silently fixing.
# It is also what CI already runs.
web-setup: ## Install the web dependencies (idempotent)
	@if [ ! -d "apps/web/node_modules" ]; then \
		echo ">> installing the web dependencies (npm ci)..."; \
		cd apps/web && npm ci; \
	else \
		echo ">> web: node_modules already exists (npm ci skipped)"; \
	fi

.PHONY: dev
dev: worker-setup web-setup ## Start DB + worker + API + web (all in the background, logs in .logs/)
	@mkdir -p "$(DEV_RUN_DIR)" "$(DEV_LOG_DIR)"
	@echo ">> [1/4] starting Postgres + Adminer (docker compose)..."
	@$(COMPOSE) up -d
	@echo ">> [2/4] starting the NLP worker (FastAPI :8001)..."
	@WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); cd services/nlp-worker && \
		nohup "$$WORKER_PY" -m uvicorn nora_nlp.main:app --reload --port 8001 \
			> "$(DEV_LOG_DIR)/worker.log" 2>&1 & \
		echo $$! > "$(DEV_RUN_DIR)/worker.pid"
	@echo ">> [3/4] starting the API (Spring Boot :8080)..."
	@cd services/api && \
		nohup mvn -q spring-boot:run \
			> "$(DEV_LOG_DIR)/api.log" 2>&1 & \
		echo $$! > "$(DEV_RUN_DIR)/api.pid"
	@echo ">> [4/4] starting Web (Next.js :3000)..."
	@cd apps/web && \
		nohup npm run dev \
			> "$(DEV_LOG_DIR)/web.log" 2>&1 & \
		echo $$! > "$(DEV_RUN_DIR)/web.pid"
	@echo ""
	@echo "OK -- stack started. URLs:"
	@echo "  Web      -> http://localhost:3000"
	@echo "  API      -> http://localhost:8080"
	@echo "  Worker   -> http://localhost:8001"
	@echo "  Adminer  -> http://localhost:8090"
	@echo ""
	@echo "Logs in .logs/ (use: make dev-logs)"
	@echo "To stop everything: make dev-stop"

.PHONY: dev-stop
dev-stop: ## Stop the worker, API and web launched by `make dev` (does NOT stop the DB)
	@DEV_RUN_DIR="$(DEV_RUN_DIR)" bash scripts/dev-stop.sh

.PHONY: dev-logs
dev-logs: ## Simultaneous tail of the api, worker and web logs
	@tail -f "$(DEV_LOG_DIR)/api.log" "$(DEV_LOG_DIR)/worker.log" "$(DEV_LOG_DIR)/web.log"

# The demonstration seed. API_BASE is forwarded rather than defaulted here: the script refuses
# to run without it on purpose (it creates tenants and root users that no endpoint can delete),
# and a default in this Makefile would put that decision back in the place it was taken out of.
# For the usual local case that is one variable:
#
#     API_BASE=http://localhost:8080 make seed-demo
#
# Read the header of scripts/seed-demo.sh before pointing it at anything that is not localhost.
.PHONY: seed-demo
seed-demo: ## Populate a running environment with the demo narrative (needs API_BASE, curl, jq)
	@bash scripts/seed-demo.sh

.PHONY: dev-status
dev-status: ## Show the status of the registered PIDs
	@for svc in web api worker; do \
		if [ -f "$(DEV_RUN_DIR)/$$svc.pid" ]; then \
			pid=$$(cat "$(DEV_RUN_DIR)/$$svc.pid"); \
			if kill -0 $$pid 2>/dev/null; then \
				echo "  $$svc: running (pid $$pid)"; \
			else \
				echo "  $$svc: pid $$pid registered but the process died"; \
			fi; \
		else \
			echo "  $$svc: stopped"; \
		fi; \
	done
	@echo ""
	@$(COMPOSE) ps

# --- Backend ---

.PHONY: api-dev
api-dev: ## Run the Spring Boot backend in dev mode
	cd services/api && mvn spring-boot:run

.PHONY: api-test
api-test: ## Run the backend tests
	cd services/api && mvn test

# --- Worker NLP ---

.PHONY: worker-dev
worker-dev: worker-setup ## Run the FastAPI worker with reload
	@WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); cd services/nlp-worker && "$$WORKER_PY" -m uvicorn nora_nlp.main:app --reload --port 8001

.PHONY: worker-test
worker-test: worker-setup ## Run the worker tests
	@WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); cd services/nlp-worker && "$$WORKER_PY" -m pytest

# --- Web ---

.PHONY: web-dev
web-dev: web-setup ## Run the Next.js frontend in dev mode
	cd apps/web && npm run dev

# apps/admin had no target at all, and nothing in the repository installed it. The README
# said "run npm install && npm run dev inside apps/admin", which documents the trap rather
# than removing it — the same argument that gave web-dev a web-setup prerequisite.
#
# It is deliberately NOT in `make dev`. The operator console is a separate concern from the
# product slice and it serves on 3002, so starting it alongside everything else would mostly
# add a port and a log.
#
# READ THIS BEFORE REPORTING admin-dev AS BROKEN. Until 2026-08-16 it rendered mock data by
# default and this comment said so. The default is now the opposite, on purpose: the variable
# has to spell NORA_ADMIN_USE_MOCKS=true, and without it the console runs its real data layer
# with Cloudflare Access JWT validation on — which, on a laptop with no CF_ACCESS_* set, means
# every page answers 403 naming the two missing variables. That is the console working, not
# failing. For local work:
#
#     NORA_ADMIN_USE_MOCKS=true make admin-dev
#
# The old default was the reason the inversion happened: forgetting one variable served
# fabricated data with the identity gate switched off, and nothing anywhere said so.
.PHONY: admin-setup
admin-setup: ## Install the operator console dependencies (idempotent)
	@if [ ! -d "apps/admin/node_modules" ]; then \
		echo ">> installing the admin dependencies (npm ci)..."; \
		cd apps/admin && npm ci; \
	else \
		echo ">> admin: node_modules already exists (npm ci skipped)"; \
	fi

.PHONY: admin-dev
admin-dev: admin-setup ## Run the operator console in dev mode (port 3002; NORA_ADMIN_USE_MOCKS=true for mock data)
	cd apps/admin && npm run dev

# There is no `web-test`: apps/web has no test script and no test files, so the target
# it used to declare invoked `npm test` against a package.json that does not define it,
# and `make test` failed for that reason alone. The gap is real and recorded in ADR 0018;
# advertising a target that cannot work only hid it.

# --- Quality ---

.PHONY: lint
lint: ## Lint every package (fails if any linter reports errors)
	cd apps/web && npm run lint
	cd services/nlp-worker && ruff check .
	cd services/api && mvn spotless:check

.PHONY: format
format: ## Format every package (modifies files)
	cd apps/web && npm run format
	cd services/nlp-worker && ruff format .
	cd services/api && mvn spotless:apply

.PHONY: test
test: api-test worker-test ## Run the full test suite
