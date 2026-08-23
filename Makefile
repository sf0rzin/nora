# NORA — official development commands.
# Use this Makefile as the source of truth for "how to run the thing".

SHELL := bash
.DEFAULT_GOAL := help

# `--env-file .env.local` is CONDITIONAL, and the conditional is the fix rather than a nicety.
# On a fresh clone that file does not exist, `docker compose --env-file .env.local` refuses to
# start before reading anything, and `db-up`, `db-down`, `db-reset`, `dev` and `dev-status` all
# inherited the failure — the first five commands a new contributor runs. The compose itself
# needs nothing from the file: every variable it reads carries a default
# (infra/docker/docker-compose.yml). The file was only mandatory because this line said so.
#
# `=` and not `:=`, deliberately: a recursively-expanded variable re-runs the `$(shell)` at USE
# time, so a target that has `env` as a prerequisite picks up the .env.local that prerequisite
# just created. With `:=` the probe would run once, at parse time, and always see the old world.
COMPOSE_ENV_FILE = $(shell [ -f .env.local ] && printf -- '--env-file .env.local')
COMPOSE = docker compose -f infra/docker/docker-compose.yml $(COMPOSE_ENV_FILE)

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

# `env` as a prerequisite on the targets that START something, for the same reason `dev`
# already declares `worker-setup web-setup`: a bootstrap step that a command silently needs is
# a trap, and the fix is to declare it rather than to document it. `db-down` and `dev-status`
# deliberately do NOT get it — a stop and a status must not create files — and they no longer
# need it either, since COMPOSE_ENV_FILE above degrades to no flag.
.PHONY: db-up
db-up: env ## Start Postgres + Adminer
	$(COMPOSE) up -d

.PHONY: db-down
db-down: ## Stop Postgres + Adminer
	$(COMPOSE) down

.PHONY: db-reset
db-reset: env ## Drop the volume and start the database from scratch
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
dev: env worker-setup web-setup ## Start DB + worker + API + web (all in the background, logs in .logs/)
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

# `verify`, not `test`. Both jacoco executions in services/api/pom.xml — `report` and the
# `check-iam-coverage` rule with haltOnFailure — are bound to the VERIFY phase, so `mvn test`
# stopped one phase short of the only backend coverage gate this repository has. `make test`
# advertised itself as the local stand-in for CI while never running it; CI itself has always
# run `mvn -B verify`. The cost is honest and worth naming: verify also runs the failsafe
# integration tests, which need Docker for Testcontainers and take minutes rather than seconds.
# If you want the fast inner loop, run `mvn test` in services/api directly — but know that a
# green run there says nothing about PolicyEvaluator's coverage floor.
.PHONY: api-test
api-test: ## Run the backend tests AND the JaCoCo gate (mvn verify; needs Docker for Testcontainers)
	cd services/api && mvn verify

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

# `npm run test:coverage`, not `npm test`, for the reason web-test gives: the coverage run is
# what applies the thresholds in apps/admin/vitest.config.mts, so this target and the CI step
# assert the same thing. Here that is one module — `src/lib/access.ts`, the console's only
# authentication boundary, which can regress from fail-closed to fail-open while every page
# keeps rendering. Seconds, and no browser.
.PHONY: admin-test
admin-test: admin-setup ## Run the operator console unit tests with coverage (Vitest)
	cd apps/admin && npm run test:coverage

# --- Desktop ---

.PHONY: desktop-setup
desktop-setup: ## Install the desktop frontend dependencies (idempotent)
	@if [ ! -d "apps/desktop/node_modules" ]; then \
		echo ">> installing the desktop dependencies (npm ci)..."; \
		cd apps/desktop && npm ci; \
	else \
		echo ">> desktop: node_modules already exists (npm ci skipped)"; \
	fi

# TWO SUITES, and the order is the cheap one first. `npm test` is Node's own test runner over
# `src/lib` — the dock preference codec, the duration formatting and the pending-upload queue,
# all of which decode whatever localStorage happens to hold — and it needs no toolchain and no
# build. `cargo test` is the crate: the resampler argument order that compiles wrong and then
# feeds silence into transcription, the PCM16 endianness, and the assertions that a live session
# credential stays out of `Debug`.
#
# `npm run build` before cargo, and it is not optional: tauri-build resolves `frontendDist`
# (../dist) inside the BUILD SCRIPT, so a crate with no dist/ fails before compiling a line of
# Rust. ci.yml's `desktop-rust` job builds the frontend first for exactly this reason.
#
# Name the cost: a cold `cargo test` compiles the whole Tauri dependency graph and takes
# minutes. `make desktop-test` after the first run is fast; the first run is not.
.PHONY: desktop-test
desktop-test: desktop-setup ## Run the desktop tests — frontend (node --test) and the Tauri crate (cargo test)
	cd apps/desktop && npm test
	cd apps/desktop && npm run build
	cd apps/desktop/src-tauri && cargo test --locked

# `web-test` is back, and this time the script it calls exists. It was removed because the
# target invoked `npm test` against a package.json that did not define it, so `make test`
# failed for that reason alone — advertising a target that cannot work hid the gap rather
# than showing it. ADR 0042 closed the gap: `apps/web` has a Vitest suite under `src/`.
#
# `npm run test:coverage`, not `npm test`: the coverage run is what applies the per-module
# thresholds, so `make web-test` and the CI step assert the same thing. It takes about four
# seconds and needs no browser.
.PHONY: web-test
web-test: web-setup ## Run the web unit tests with coverage (Vitest; does not run Playwright)
	cd apps/web && npm run test:coverage

# --- Quality ---

# WHAT "every package" MEANT AND WHAT IT COVERED. This target said "every package" and ran
# three of the five: `apps/admin` — which has the same eslint setup as `apps/web` and is the
# operator console — and `apps/desktop` were both silently outside it, so `make lint` was green
# on a console that eslint had never seen. admin is in now.
#
# apps/desktop is in now too, but only half of it, and the halves are not arbitrary.
# `cargo fmt --check` is in: it reads the source and answers in seconds, and the crate was
# reformatted so that it passes — before that, the check would have failed on code nobody had
# touched, which is how a lint target teaches people to stop running it.
# `cargo clippy` stays out: it compiles the whole Tauri graph, which is minutes on a command
# people run between edits. It belongs in CI's `desktop-rust` job, not here.
.PHONY: lint
lint: web-setup admin-setup ## Lint web, admin, worker, api and the desktop crate's formatting
	cd apps/web && npm run lint
	cd apps/admin && npm run lint
	cd services/nlp-worker && ruff check .
	cd services/api && mvn spotless:check
	cd apps/desktop/src-tauri && cargo fmt --check

.PHONY: format
format: web-setup admin-setup ## Format web, admin, worker, api and the desktop crate (modifies files)
	cd apps/web && npm run format
	cd apps/admin && npm run format
	cd services/nlp-worker && ruff format .
	cd services/api && mvn spotless:apply
	cd apps/desktop/src-tauri && cargo fmt

# Not "the full test suite" in the sense of everything CI runs: the Playwright e2e specs are
# deliberately out, because they need a production build plus a chromium download and would turn
# a command people run between edits into a multi-minute one. `make web-test` is the unit half.
#
# `api-test` is `mvn verify` since the JaCoCo gate lives in that phase (see its note above), so
# this target is no longer a fast command: it wants Docker for the backend's Testcontainers.
# That is the honest shape — a `make test` that skipped the repository's only backend coverage
# gate was measuring less than it claimed.
#
# `admin-test` and `desktop-test` are here because they finally exist. This target ran three of
# the five packages and did not say so, which is the same defect the `lint` note above records:
# the operator console and the desktop client had no unit tests at all, then they got some, and
# a `make test` that keeps skipping them reports a suite narrower than the one CI runs.
# `desktop-test` is what makes this expensive on a cold checkout — read its note before blaming
# this line.
.PHONY: test
test: api-test worker-test web-test admin-test desktop-test ## Run every unit suite CI runs — backend (with the JaCoCo gate), worker, web, admin and desktop (not Playwright)
