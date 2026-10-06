# 007. Структура проекта и порядок реализации

Дата: 2026-10-04. Статус: S2-06, принято для реализации.
Входы: [проверка модели](006-design-review.md), [сервер и веб](../implementation/03_PRODUCT_FOUNDATION_PLAN.md), [API](../../contracts/openapi.json).

## Структура и границы

```text
backend/
  cmd/api/                 HTTP-процесс
  cmd/worker/              процесс фоновой работы
  cmd/migrate/             применение миграций
  internal/platform/       конфигурация, PostgreSQL, HTTP, безопасные логи
  internal/identity/       пользователи, сессии, guards
  internal/ledger/         деньги, счета, агрегаты операций, классификация
  internal/sync/           действия, журнал, курсоры, materialized snapshots
  migrations/             SQL в едином порядке
  tests/integration/       реальные PostgreSQL/HTTP сценарии DR-*
web/
  src/api/                 типы и HTTP-клиент общего контракта
  src/features/            auth, accounts, transactions, categories, settings
  src/shared/              денежные строки, формы и общие компоненты
  tests/e2e/               браузерная приемка с настоящим сервером
contracts/                 единственный API/события/контрольные примеры
ops/                       локальное окружение, процессы и конфигурация
  .runtime/                игнорируемые временные файлы, PID, сертификаты
scripts/                   воспроизводимые проверки и запуск окружения
```

Каталоги backend/web/ops/scripts созданы в S3-01; функциональность и реальные проверки отражены в отчете этапа. Android и банковские каталоги пока не создаются. Структура — модульный монолит; identity/ledger/sync не запускаются отдельными сервисами.

## Инструменты и зависимости

Go: установлен 1.26.4; выбирается ветка 1.26, стандартный net/http и slog, pgx/v5 для PostgreSQL. Go-модуль `accounting/backend` не заявляет существование публичного GitHub-репозитория. Версии библиотек фиксируются в go.mod/go.sum при фактическом разрешении зависимостей в S3-01.

PostgreSQL: ветка 18; SQL-migrations с runner из того же Go-модуля, отдельный migration process. Runner создает свою таблицу версий, сериализует применение, проверяет checksum уже примененных SQL и не выполняет произвольное откатывание production данных. Фиксация версии сервера/образа и запуск — S3-01C. [Официальные выпуски](https://www.postgresql.org/docs/release/), [pgx](https://pkg.go.dev/github.com/jackc/pgx/v5).

Веб: React + TypeScript + Vite, npm package-lock, Node.js 24 LTS. Версии React/Vite/TypeScript фиксируются в package.json/package-lock.json при создании приложения, без непроверенного предположения о latest. Выбор Node подтвержден [официальными выпусками](https://nodejs.org/en/about/previous-releases), установка и сборка по [Vite guide](https://vite.dev/guide/).

Проверки веба: TypeScript, сборка Vite, Vitest только для значимых денежных/конфликтных функций, Playwright для браузерной приемки с настоящим API ([webServer](https://playwright.dev/docs/test-webserver)). Контрактные моки допустимы при разработке UI, не закрывают приемку PostgreSQL/веба.

Python 3/jsonschema 4.10.3 уже доступны для существующих проверок contracts. В папке отсутствует Git; инициализацию не считать выполненной и не создавать коммиты от имени пользователя.

## Docker-окружение

Основной способ запуска — Docker Compose. Веб и PostgreSQL работают в отдельных контейнерах; API и worker также входят в общий Compose-проект. Node.js и PostgreSQL на хосте для запуска не требуются.

| Сервис | Назначение | Доступ |
|---|---|---|
| web | Dev: Vite; production: собранная статика и reverse proxy | Общий публичный HTTPS origin, proxy `/api/v1` |
| api | Go HTTP API | Внутренняя сеть, `HTTP_ADDR=0.0.0.0:8080` |
| worker | Фоновые задания, тот же backend-образ | Внутренняя сеть, без опубликованных портов |
| db | PostgreSQL 18 | Внутренняя сеть, hostname db, постоянный именованный volume |
| migrate | Одноразовый migration runner | Тот же backend-образ; завершение до API/worker |

Браузер использует внешний origin веба; proxy направляет API в `api:8080`. Имена api/db используются только между контейнерами. PUBLIC_ORIGIN — внешний HTTPS origin. Local/test допускает DB sslmode=disable; production сохраняет verify-full: PostgreSQL получает серверный сертификат, Go-контейнеры — доверенный CA. Существующие проверки конфигурации не ослабляются.

Файлы реализации: compose.yaml (базовые сервисы), compose.dev.yaml (bind mounts/dev), compose.test.yaml (изолированная тестовая БД), compose.prod.yaml (production). Dockerfile и .dockerignore находятся в backend/ и web/; proxy/TLS — в ops/. Версии базовых образов фиксируются при реализации; сборки используют lockfiles. Production-образы собираются отдельными стадиями без dev-зависимостей, исходники монтируются только в dev.

Запуск: readiness db → успешный migrate → API/worker → веб. Ошибка миграции блокирует зависимые процессы. Только веб публикуется наружу; local/test диагностические порты допускаются явно на loopback. HTTPS, cookie и CSRF проверяются в настоящем браузере.

DB volume сохраняется при пересоздании контейнеров и обычной остановке. Остановка не удаляет volumes. Очистка тестовых данных — отдельная явно названная команда только для тестового Compose-проекта. Test использует отдельные project name, БД и volume без fallback на local/production. Volume не заменяет backup; реальный restore остается S6-03.

Секреты/.env/сертификаты не входят в образы или репозиторий; example содержит placeholders, production получает секреты при запуске. Docker-файлы созданы в S3-01B/C: веб-образ, Compose, backend-образ и proxy/TLS. CLI-проверка конфигураций выполнена; запуск контейнеров еще not_run. Приемка: сборка образов, запуск local/test, health, применение/повтор миграций, отказ запуска при ошибке migrate, сохранение данных после пересоздания db, изоляция test и браузерная проверка общего origin. Без Docker эти проверки имеют not_run.

## Окружения и секреты

APP_ENV=local/test/production, DATABASE_URL, HTTP_ADDR, PUBLIC_ORIGIN, REGISTRATION_ENABLED, CURSOR_SIGNING_KEY, auth/hash concurrency и timeouts — серверная конфигурация. Регистрация по умолчанию false во всех режимах; local/test example включает true явно. Production не получает секреты/example defaults автоматически и не запускается с публичной регистрацией до закрытия PUBLIC-REGISTRATION.

Приватный cursor key — случайный минимум 32 байта; .env/.runtime/private keys игнорируются. DATABASE_URL и request body не выводятся в лог. Конфигурация клиента содержит только публичный API prefix `/api/v1`, без DB/секретов. Локальный веб и API используют один origin через dev proxy; проверка Origin/CSRF/Secure cookie обязана пройти на фактическом браузере. При необходимости local HTTPS сертификаты генерируются в ops/.runtime, системные сертификаты/сервисы автоматически не меняются.

Тестовая БД задается отдельным TEST_DATABASE_URL и имеет явное имя/namespace для тестов. Интеграционный runner отказывает без тестовой конфигурации; никаких fallback на production URL или удаления чужих БД. Миграции запускаются отдельной командой до API/worker.

Фактическая среда сейчас: Go/make/git/openssl есть; node/npm/docker/podman/psql/postgres/initdb/pg_ctl не найдены. S3-01 может подготовить изолированные инструменты в workspace или /tmp, не устанавливая системные пакеты без необходимости. При недоступной установке продолжать Go/контракты, но не объявлять сборку веба или проверку PostgreSQL успешной.

## Порядок миграций и владение

Один владелец `backend/migrations/` — координатор схемы (роль Go). Новые номера выдаются последовательно; примененные SQL не редактируются. План порядка: 0001 identity/workspace/currencies/сессии; 0002 ledger/accounts/entries/allocations/categories/tags; 0003 actions/sync_heads/groups/changes/snapshots/audit. Поскольку ledger использует head/action для записи, ручные финансовые HTTP writes не открываются до 0003 и S3-04A.

S3-01A создает runner без фиктивной финансовой миграции. S3-02A — единственный владелец всей начальной схемы, с требованиями identity и sync; это снимает конкуренцию за FK/номера. Финансовые инварианты и доступ проверяются после миграций на реальной БД. Для каждого примененного файла runner хранит SHA-256 и порядковый номер.

Общие contracts принадлежат архитектору/координатору. Изменение версии оформляется с влиянием на Go/веб/fixtures. Клиенты не создают второй источник типов вручную: типы выводятся из OpenAPI либо сверяются автоматической проверкой. Неизвестный денежный float и подмена version/generation запрещены.

## Команды и статус

Существуют и выполнены ранее:

```bash
python3 contracts/check_contracts.py
python3 contracts/fixtures/check_financial_model.py
```

Команды ниже — цели S3-01, еще не заявляются работающими до создания соответствующих файлов:

```bash
make check-contracts
make backend-check        # gofmt check, go vet, go test, go build API/worker/migrate
make web-check            # npm ci/typecheck/test/build из lockfile
make db-up                # локальный db-контейнер через Compose
make migrate              # текущая DATABASE_URL, явная конфигурация
make backend-integration  # только TEST_DATABASE_URL
make dev                  # Compose: db/migrate/API/worker/web, общий HTTPS origin
make e2e                  # реальный Go API/PostgreSQL и браузер
```

Без PostgreSQL integration target должен явно отказать; без браузера e2e остается not_run. Проверка готовности процесса не заменяет завершение финансовой функции.

## Декомпозиция и первый сквозной результат

[S3-01A](../agent-tasks/S3-01A.md) — каркас Go/runner; [S3-01B](../agent-tasks/S3-01B.md) — каркас React; [S3-01C](../agent-tasks/S3-01C.md) — локальное окружение и общие команды. Затем схема S3-02A, финансовые функции S3-02B/C и identity S3-03A/B. S3-04A обеспечивает транзакционный command guard, receipts и head до подключения HTTP writes; S3-04B/C — pull/snapshots.

Первый путь интеграции: local/test registration → Workspace.sync_generation_id → создать EUR счет с 100000 minor → расход 1234 → posted_balance=98766 → перезагрузка веба/GET → тот же остаток. Повтор исходного key сохраняет одну операцию, запрос пользователя B отвергается, конкурентная правка возвращает conflict. S3-06A показывает путь в вебе; S3-07A закрывает его на настоящих компонентах до расширения типов операций.

[S3-06B](../agent-tasks/S3-06B.md) завершает редакторы/устройства/категории/фильтры; S3-07B закрывает все требования этапа. S3-05 не создается: Android перенесен в этап приложений.

Сценарии DR-* закреплены в карточках владельцев; DR-S10 относится к S6-03 (реальный restore), а не закрывается каркасом. Публичная регистрация и RPO остаются зависимостями выпуска. Все пользовательские действия/ошибки финансовой модели сохраняются; новые технические решения не заменяют обязательный этап настоящих интеграционных проверок.

Порядок запуска Compose сверяется с [официальной документацией Docker](https://docs.docker.com/compose/how-tos/startup-order/); dev proxy — с [документацией Vite](https://vite.dev/config/server-options).

### Подсети разработки и тестов

Dev overlay задает private IPAM subnet через ACCOUNTING_DEV_SUBNET (по умолчанию 192.168.240.0/24), test overlay — через ACCOUNTING_TEST_SUBNET (192.168.241.0/24). Проекты имеют отдельные сети и volumes. Значения можно переопределить в .env при пересечении с LAN/VPN. После смены диапазона старую сеть удаляют через соответствующий make down / test-down без удаления volumes, затем запускают окружение снова. Base/production сохраняют автоматический выбор сети Docker.

Основание: на текущем dev-хосте VPN устанавливает маршрут 172.18.0.0/16 через cscotun0, совпадающий с автоматически выбранной сетью Docker. Из-за этого host docker-proxy не достигает nginx, хотя nginx/API/БД внутри сети исправны. Отдельный dev диапазон устраняет подтвержденное пересечение без изменения VPN или системных маршрутов.

### Браузерная приемка первого потока

`make e2e` собирает accounting-test из compose.yaml + compose.test.yaml + compose.e2e.yaml. Последний overlay явно включает регистрацию только для test API. Playwright на хосте (Node 24 и установленный Chrome, CHROME_PATH при необходимости) обращается через HTTPS 8444 к настоящим web/API/PostgreSQL контейнерам. Скрипт завершает тестовый проект через down без удаления volume. Credentials не сохраняются в trace; fault injection потери ответа выполняется после настоящего server commit. Local/production регистрация не меняется.

Сборочная сеть web/web-dev настраивается DOCKER_BUILD_NETWORK (по умолчанию default). На этом хосте VPN блокирует npm registry из build bridge, тогда как host network работает; DOCKER_BUILD_NETWORK=host служит явным обходом только при сборке. Runtime сети и отсутствие внешнего DB порта сохраняются. Проверки local/test запуска, миграций и первого браузерного потока выполнены; production TLS/роли/restore остаются отдельными этапами, актуальный статус — STAGE-01-REPORT.md.
