# NORA — comandos oficiais de desenvolvimento.
# Use este Makefile como fonte da verdade para "como rodar a coisa".

SHELL := bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/docker/docker-compose.yml --env-file .env.local

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
	@echo "OK — .env.local criados (revisar antes de usar em produção)."

# --- Infra local ---

.PHONY: db-up
db-up: ## Sobe Postgres + Adminer
	$(COMPOSE) up -d

.PHONY: db-down
db-down: ## Para Postgres + Adminer
	$(COMPOSE) down

.PHONY: db-reset
db-reset: ## Apaga volume e sobe banco do zero
	$(COMPOSE) down -v
	$(COMPOSE) up -d

# --- Dev completo ---

# Diretório de logs e PIDs dos processos lançados pelo `make dev`.
DEV_RUN_DIR := .run
DEV_LOG_DIR := .logs

.PHONY: dev
dev: ## Sobe DB + worker + API + web (tudo em background, logs em .logs/)
	@mkdir -p $(DEV_RUN_DIR) $(DEV_LOG_DIR)
	@echo ">> [1/4] subindo Postgres + Adminer (docker compose)…"
	@$(COMPOSE) up -d
	@echo ">> [2/4] subindo NLP worker (FastAPI :8001)…"
	@cd services/nlp-worker && \
		nohup uvicorn nora_nlp.main:app --reload --port 8001 \
		> ../../$(DEV_LOG_DIR)/worker.log 2>&1 & echo $$! > ../../$(DEV_RUN_DIR)/worker.pid
	@echo ">> [3/4] subindo API (Spring Boot :8080)…"
	@cd services/api && \
		nohup mvn -q spring-boot:run \
		> ../../$(DEV_LOG_DIR)/api.log 2>&1 & echo $$! > ../../$(DEV_RUN_DIR)/api.pid
	@echo ">> [4/4] subindo Web (Next.js :3000)…"
	@cd apps/web && \
		nohup pnpm dev \
		> ../../$(DEV_LOG_DIR)/web.log 2>&1 & echo $$! > ../../$(DEV_RUN_DIR)/web.pid
	@echo ""
	@echo "OK — stack iniciada. URLs:"
	@echo "  Web      → http://localhost:3000"
	@echo "  API      → http://localhost:8080"
	@echo "  Worker   → http://localhost:8001"
	@echo "  Adminer  → http://localhost:8090"
	@echo ""
	@echo "Logs em $(DEV_LOG_DIR)/  (tail -f $(DEV_LOG_DIR)/api.log etc)"
	@echo "Para parar tudo: make dev-stop"

.PHONY: dev-stop
dev-stop: ## Para worker, API e web lançados pelo `make dev` (NÃO derruba o DB)
	@for svc in web api worker; do \
		if [ -f $(DEV_RUN_DIR)/$$svc.pid ]; then \
			pid=$$(cat $(DEV_RUN_DIR)/$$svc.pid); \
			echo ">> parando $$svc (pid $$pid)"; \
			kill $$pid 2>/dev/null || true; \
			rm -f $(DEV_RUN_DIR)/$$svc.pid; \
		else \
			echo ">> $$svc: sem pid registrado"; \
		fi; \
	done
	@echo "OK — processos parados. DB segue de pé (use 'make db-down' para derrubar)."

.PHONY: dev-logs
dev-logs: ## Tail simultâneo dos logs de api, worker e web
	@tail -f $(DEV_LOG_DIR)/api.log $(DEV_LOG_DIR)/worker.log $(DEV_LOG_DIR)/web.log

.PHONY: dev-status
dev-status: ## Mostra status dos PIDs registrados
	@for svc in web api worker; do \
		if [ -f $(DEV_RUN_DIR)/$$svc.pid ]; then \
			pid=$$(cat $(DEV_RUN_DIR)/$$svc.pid); \
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
	cd services/api && mvn spring-boot:run

.PHONY: api-test
api-test: ## Roda os testes do backend
	cd services/api && mvn test

# --- Worker NLP ---

.PHONY: worker-dev
worker-dev: ## Roda o worker FastAPI com reload
	cd services/nlp-worker && uvicorn nora_nlp.main:app --reload --port 8001

.PHONY: worker-test
worker-test: ## Roda os testes do worker
	cd services/nlp-worker && pytest

# --- Web ---

.PHONY: web-dev
web-dev: ## Roda o frontend Next.js em modo dev
	cd apps/web && pnpm dev

.PHONY: web-test
web-test: ## Roda os testes do web
	cd apps/web && pnpm test

# --- Qualidade ---

.PHONY: lint
lint: ## Roda lint em todos os pacotes
	cd apps/web && pnpm lint || true
	cd services/nlp-worker && ruff check . || true
	cd services/api && mvn spotless:check || true

.PHONY: format
format: ## Formata todos os pacotes
	cd apps/web && pnpm format || true
	cd services/nlp-worker && ruff format . || true
	cd services/api && mvn spotless:apply || true

.PHONY: test
test: api-test worker-test web-test ## Roda toda a bateria de testes
