COMPOSE = docker compose --project-name accounting-local -f compose.yaml
TEST_COMPOSE = docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml
.PHONY: check-contracts backend-check web-check tls db-up migrate dev down test-up test-down compose-check
check-contracts:
	python3 contracts/check_contracts.py
	python3 contracts/fixtures/check_financial_model.py
backend-check:
	cd backend && test -z "$$(gofmt -l .)" && go vet ./... && go test ./... && go build ./cmd/api ./cmd/worker ./cmd/migrate
web-check:
	docker build --network "$${DOCKER_BUILD_NETWORK:-default}" --target build -t accounting-web-check web
tls:
	./scripts/local-tls.sh
db-up:
	$(COMPOSE) up -d db
migrate:
	$(COMPOSE) run --build --rm migrate
dev: tls
	$(COMPOSE) -f compose.dev.yaml up --build -d
down:
	$(COMPOSE) -f compose.dev.yaml down
test-up: tls
	$(TEST_COMPOSE) up --build -d
test-down:
	$(TEST_COMPOSE) down
compose-check:
	python3 scripts/check-compose.py
.PHONY: platform-integration
platform-integration:
	$(TEST_COMPOSE) --profile checks run --build --rm platform-check
.PHONY: schema-check
schema-check:
	$(TEST_COMPOSE) --profile checks run --build --rm schema-check
.PHONY: auth-check
auth-check:
	$(TEST_COMPOSE) --profile checks run --build --rm auth-check
.PHONY: command-check
command-check:
	$(TEST_COMPOSE) --profile checks run --build --rm command-check
.PHONY: ledger-check
ledger-check:
	mkdir -p ops/.runtime/checks
	rm -f ops/.runtime/checks/ledger-responses.json
	$(TEST_COMPOSE) --profile checks run --build --rm ledger-check
	python3 scripts/check-ledger-responses.py
.PHONY: e2e
e2e:
	./scripts/e2e.sh

.PHONY: backend-integration
backend-integration:
	mkdir -p ops/.runtime/checks
	rm -f ops/.runtime/checks/vertical-backend.json
	$(TEST_COMPOSE) --profile checks run --build --rm vertical-check

.PHONY: advanced-ledger-check
advanced-ledger-check:
	mkdir -p ops/.runtime/checks
	rm -f ops/.runtime/checks/ledger-advanced-responses.json
	$(TEST_COMPOSE) --profile checks run --build --rm --user "$$(id -u):$$(id -g)" advanced-check
	python3 scripts/check-ledger-responses.py ops/.runtime/checks/ledger-advanced-responses.json

.PHONY: pull-check
pull-check:
	mkdir -p ops/.runtime/checks
	rm -f ops/.runtime/checks/pull-responses.json
	$(TEST_COMPOSE) --profile checks run --build --rm --user "$$(id -u):$$(id -g)" pull-check
	python3 scripts/check-ledger-responses.py ops/.runtime/checks/pull-responses.json

.PHONY: snapshot-check
snapshot-check:
	mkdir -p ops/.runtime/checks
	rm -f ops/.runtime/checks/snapshot-responses.json
	$(TEST_COMPOSE) --profile checks run --build --rm --user "$$(id -u):$$(id -g)" snapshot-check
	python3 scripts/check-ledger-responses.py ops/.runtime/checks/snapshot-responses.json

.PHONY: access-check
access-check:
	mkdir -p ops/.runtime/checks
	rm -f ops/.runtime/checks/access-responses.json ops/.runtime/checks/session-race-responses.json
	$(TEST_COMPOSE) --profile checks run --build --rm --user "$$(id -u):$$(id -g)" access-check
	python3 scripts/check-ledger-responses.py ops/.runtime/checks/access-responses.json
	python3 scripts/check-ledger-responses.py ops/.runtime/checks/session-race-responses.json
	python3 scripts/check-access-responses.py
