# NORA — comandos oficiais de desenvolvimento.
# Use este Makefile como fonte da verdade para "como rodar a coisa".

SHELL := bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/docker/docker-compose.yml --env-file .env.local

# Caminho do venv do worker. O python concreto e resolvido em runtime
# (Windows usa .venv/Scripts/python.exe; Unix usa .venv/bin/python).
WORKER_VENV := $(CURDIR)/services/nlp-worker/.venv
WORKER_PYTHON_RESOLVE := if [ -x "$(WORKER_VENV)/Scripts/python.exe" ]; then echo "$(WORKER_VENV)/Scripts/python.exe"; else echo "$(WORKER_VENV)/bin/python"; fi
# Python do sistema usado para criar o venv. Default `python3` (Ubuntu 22+/macOS 14+
# nao tem mais o symlink `python`). Override: `make ... PYTHON=python3.12`.
PYTHON ?= python3

.PHONY: help
help: ## Lista os comandos disponíveis
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# --- Bootstrap ---

.PHONY: env
env: ## Cria .env.local em raiz e em cada serviço a partir dos exemplos
	@[ -f .env.local ] || cp .env.example .env.local
	@[ -f services/api/.env.local ] || cp services/api/.env.example services/api/.env.local
	@[ -f services/nlp-worker/.env.local ] || cp services/nlp-worker/.env.example services/nlp-worker/.env.local
	@[ -f apps/web/.env.local ] || cp apps/web/.env.example apps/web/.env.local
	@[ -f apps/desktop/.env.local ] || cp apps/desktop/.env.example apps/desktop/.env.local
	@echo "OK — .env.local criados (revisar antes de usar em produção)."

# --- Infra local ---

.PHONY: db-up
db-up: env ## Sobe Postgres + Adminer
	$(COMPOSE) up -d

.PHONY: db-down
db-down: ## Para Postgres + Adminer
	$(COMPOSE) down

.PHONY: db-reset
db-reset: env ## Apaga volume e sobe banco do zero
	$(COMPOSE) down -v
	$(COMPOSE) up -d

# --- Dev completo ---

# Diretorio de logs e PIDs dos processos lancados pelo `make dev`.
# Usamos $(CURDIR) para paths absolutos -- evita problemas com cwd em sub-shells.
DEV_RUN_DIR := $(CURDIR)/.run
DEV_LOG_DIR := $(CURDIR)/.logs

.PHONY: worker-setup
worker-setup: ## Cria venv do worker e instala dependencias (idempotente)
	@set -e; \
	if [ ! -f "$(WORKER_VENV)/.deps-installed" ]; then \
		echo ">> criando venv do worker em $(WORKER_VENV)..."; \
		cd services/nlp-worker && $(PYTHON) -m venv .venv; \
		WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); \
		if [ ! -x "$$WORKER_PY" ]; then \
			echo "ERRO: o venv nao gerou um Python executavel ($$WORKER_PY)."; \
			echo "      No Windows, 'python3' costuma ser o stub da Microsoft Store."; \
			echo "      Instale o Python 3.12 real e rode: make worker-setup PYTHON=py"; \
			exit 1; \
		fi; \
		"$$WORKER_PY" -m pip install -q --upgrade pip; \
		echo ">> instalando package local nlp-baseline (ADR 0010)..."; \
		"$$WORKER_PY" -m pip install -q -e "$(CURDIR)/packages/nlp-baseline"; \
		"$$WORKER_PY" -m pip install -q -e ".[dev]"; \
		touch "$(WORKER_VENV)/.deps-installed"; \
		echo ">> venv do worker pronto."; \
	fi

.PHONY: web-setup
web-setup: ## Instala dependencias do web (idempotente)
	@if [ ! -d "apps/web/node_modules" ]; then \
		echo ">> instalando dependencias do web (npm)..."; \
		cd apps/web && npm install; \
	else \
		echo ">> web: node_modules ja existe (npm install pulado)"; \
	fi

# `make dev` e orientado a Unix/macOS: sobe tudo em background (nohup &) e grava PIDs.
# No WINDOWS/Git Bash o backgrounding morre quando o make sai -- use os alvos
# foreground em terminais separados: `make api-dev` / `make web-dev` / `make worker-dev`
# (o Postgres sobe via Docker e sobrevive de qualquer jeito).
.PHONY: dev
dev: env worker-setup web-setup ## Sobe DB + worker + API + web em background (Unix/macOS; ver nota p/ Windows)
	@mkdir -p "$(DEV_RUN_DIR)" "$(DEV_LOG_DIR)"
	@echo ">> [1/4] subindo Postgres + Adminer (docker compose)..."
	@$(COMPOSE) up -d
	@echo ">> [2/4] subindo NLP worker (FastAPI :8001)..."
	@WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); cd services/nlp-worker && \
		nohup "$$WORKER_PY" -m uvicorn nora_nlp.main:app --reload --port 8001 \
			> "$(DEV_LOG_DIR)/worker.log" 2>&1 & \
		echo $$! > "$(DEV_RUN_DIR)/worker.pid"
	@echo ">> [3/4] subindo API (Spring Boot :8080)..."
	@cd services/api && \
		nohup ./mvnw -q spring-boot:run \
			> "$(DEV_LOG_DIR)/api.log" 2>&1 & \
		echo $$! > "$(DEV_RUN_DIR)/api.pid"
	@echo ">> [4/4] subindo Web (Next.js :3000)..."
	@cd apps/web && \
		nohup npm run dev \
			> "$(DEV_LOG_DIR)/web.log" 2>&1 & \
		echo $$! > "$(DEV_RUN_DIR)/web.pid"
	@echo ""
	@echo "OK -- stack iniciada. URLs:"
	@echo "  Web      -> http://localhost:3000"
	@echo "  API      -> http://localhost:8080"
	@echo "  Worker   -> http://localhost:8001"
	@echo "  Adminer  -> http://localhost:8090"
	@echo ""
	@echo "Logs em .logs/ (use: make dev-logs)"
	@echo "Para parar tudo: make dev-stop"

.PHONY: dev-stop
dev-stop: ## Para worker, API e web lancados pelo `make dev` (NAO derruba o DB)
	@DEV_RUN_DIR="$(DEV_RUN_DIR)" bash scripts/dev-stop.sh

.PHONY: dev-logs
dev-logs: ## Tail simultâneo dos logs de api, worker e web
	@tail -f "$(DEV_LOG_DIR)/api.log" "$(DEV_LOG_DIR)/worker.log" "$(DEV_LOG_DIR)/web.log"

.PHONY: dev-status
dev-status: ## Mostra status dos PIDs registrados
	@for svc in web api worker; do \
		if [ -f "$(DEV_RUN_DIR)/$$svc.pid" ]; then \
			pid=$$(cat "$(DEV_RUN_DIR)/$$svc.pid"); \
			if kill -0 $$pid 2>/dev/null; then \
				echo "  $$svc: rodando (pid $$pid)"; \
			else \
				echo "  $$svc: pid $$pid registrado mas processo morreu"; \
			fi; \
		else \
			echo "  $$svc: parado"; \
		fi; \
	done
	@echo ""
	@$(COMPOSE) ps

# --- Backend ---

.PHONY: api-dev
api-dev: ## Roda o backend Spring Boot em modo dev
	cd services/api && ./mvnw spring-boot:run

.PHONY: api-test
api-test: ## Roda os testes do backend
	cd services/api && ./mvnw test

# --- Worker NLP ---

.PHONY: worker-dev
worker-dev: worker-setup ## Roda o worker FastAPI com reload
	@WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); cd services/nlp-worker && "$$WORKER_PY" -m uvicorn nora_nlp.main:app --reload --port 8001

.PHONY: worker-test
worker-test: worker-setup ## Roda os testes do worker
	@WORKER_PY=$$($(WORKER_PYTHON_RESOLVE)); cd services/nlp-worker && "$$WORKER_PY" -m pytest

# --- Web ---

.PHONY: web-dev
web-dev: ## Roda o frontend Next.js em modo dev
	cd apps/web && npm run dev

.PHONY: web-test
web-test: ## Roda os testes do web
	cd apps/web && npm test

# --- Qualidade ---

.PHONY: lint
lint: ## Roda lint em todos os pacotes (falha se algum lintar com erros)
	cd apps/web && npm run lint
	cd services/nlp-worker && ruff check .
	cd services/api && ./mvnw spotless:check

.PHONY: format
format: ## Formata todos os pacotes (modifica arquivos)
	cd apps/web && npm run format
	cd services/nlp-worker && ruff format .
	cd services/api && ./mvnw spotless:apply

.PHONY: test
test: api-test worker-test web-test ## Roda toda a bateria de testes
