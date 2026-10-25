# Runs the whole system locally: the backend and the web app on the host, the infrastructure (PostgreSQL, RabbitMQ and
# the observability stack: Prometheus, Loki, Alloy, Grafana) in Docker Compose. `make` lists the targets.
#
# Needs JDK 21, Node.js 22.17+ and Docker (see README.md). Written for the GNU Make 3.81 that macOS ships.

COMPOSE ?= docker compose
GRADLEW ?= ./gradlew
NPM     ?= npm

# The Compose services `up` and `logs` act on; empty means all of them, e.g. make up SERVICES="postgres rabbitmq".
SERVICES ?=

.DEFAULT_GOAL := help
.PHONY: help dev up down ps logs backend web gov-api test

help: ## List the targets
	@awk -F ':.*## ' '/^[a-z-]+:.*## / { printf "  %-10s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

dev: up web/node_modules ## Run everything: infrastructure in Docker, backend, web app and government mock here (stop with Ctrl-C or q)
	@printf '%s\n' '' \
	  '  Web app       http://localhost:5173  (check-in; /board; /staff)' \
	  '  Backend       http://localhost:8080' \
	  '  Government    http://localhost:4000  (mock of the government prescription system: try GOV-1001)' \
	  '  RabbitMQ      http://localhost:15672 (pharmacy / pharmacy)' \
	  '  Grafana       http://localhost:3000  (pharmacy / pharmacy)' \
	  '  Prometheus    http://localhost:9090' ''
# The web app and the government mock run in the background and the backend in front, so the backend keeps the terminal
# (Quarkus dev mode reads keys from it) and stopping it stops them too. They are exec'd so the signal reaches them, not a shell.
	@trap 'kill $$web $$gov' EXIT; \
	  (cd web && exec $(NPM) run dev) & web=$$!; \
	  (cd gov-api && exec $(NPM) start) & gov=$$!; \
	  $(GRADLEW) --console=plain :infra:quarkusDev

up: ## Start the infrastructure in Docker and wait until it is ready
# Alloy reads the backend's log file from here, and Docker would create the directory as root.
	@mkdir -p logs
	$(COMPOSE) up --wait $(SERVICES)

down: ## Stop the infrastructure (the data is kept in Docker volumes)
	$(COMPOSE) down

ps: ## Show the infrastructure's containers
	$(COMPOSE) ps

logs: ## Follow the infrastructure's logs
	$(COMPOSE) logs --follow --tail=100 $(SERVICES)

backend: ## Run the backend only: Quarkus dev mode with live reload (needs `make up`)
	$(GRADLEW) --console=plain :infra:quarkusDev

web: web/node_modules ## Run the web app only: Vite dev server with hot reload
	cd web && $(NPM) run dev

gov-api: ## Run the government prescription system mock only: http://localhost:4000 (no install needed)
	cd gov-api && $(NPM) start

test: web/node_modules ## Run the backend tests (they start their own PostgreSQL: Docker must run), the web type checks and tests, and the mock's tests
	$(GRADLEW) test
	cd web && $(NPM) run check && $(NPM) test
	cd gov-api && $(NPM) test

web/node_modules: web/package.json web/package-lock.json
	cd web && $(NPM) install
	@touch $@
