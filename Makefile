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
