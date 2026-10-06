# Accounting

Приложение учета личных финансов: Go API, PostgreSQL и веб на React/TypeScript; Android — следующий этап. Первая версия использует ручной учет, затем CSV; банковские подключения выполняются в конце.

Проектирование завершено: сценарии, финансовая модель, OpenAPI 0.2.0, синхронизация и проверка инвариантов. Этап сервера и веба завершен: все 35 API операций и полный online ручной учет приняты на настоящем API/PostgreSQL/Chrome (14/14 E2E). Следующий этап — Android. Готовые функции и фактически выполненные проверки указаны в [отчете этапа](docs/agent-tasks/STAGE-01-REPORT.md), задачи — в `docs/agent-tasks/`.

- [Общий план](FINANCE_APP_PLAN.md)
- [Структура, инструменты и порядок реализации](docs/decisions/007-repository-structure.md)
- [HTTP-контракт](contracts/openapi.json) и [инструкции проверки](contracts/README.md)
- [Финансовая модель](docs/decisions/003-financial-model.md)
- [Синхронизация](docs/decisions/005-sync-protocol.md)
- [Проверка проектирования](docs/decisions/006-design-review.md)

Проверка имеющихся контрактов в Python 3 с jsonschema:

```bash
python3 contracts/check_contracts.py
python3 contracts/fixtures/check_financial_model.py
```

Эти команды проверяют спецификации и контрольные примеры. Они не подтверждают работу сервера, БД и браузера. Реализованы identity, CommandRunner, финансовый API, pull и snapshots. Веб поддерживает счета/архивы, расходы/доходы с частями, переводы и комиссии, возвраты, корректировки, удаления, категории/теги, устройства, фильтры/страницы, массовую классификацию и сравнение конфликтов. Детали — в [web/README.md](web/README.md).

Регистрация по умолчанию выключена; local/test окружение включает ее явно. Публичная регистрация и реальный backup/restore имеют отдельные задачи перед выпуском. Секреты и временные данные окружения не должны попадать в документы или логи.

## Go-каркас

Из каталога `backend/`:

```bash
go test ./...
go vet ./...
go build ./cmd/api ./cmd/worker ./cmd/migrate
DATABASE_URL='postgres://accounting:local_password@127.0.0.1:5432/accounting?sslmode=disable' go run ./cmd/api
```

Замените пример подключения параметрами своей локальной БД. API слушает `127.0.0.1:8080`; `GET /health/live` проверяет процесс, `GET /health/ready` — подключение к PostgreSQL. Auth, профиль, сессии и список пространств реализованы. Реализованы /api/v1/currencies, GET/POST/PUT счетов, категорий и тегов, GET/POST/PUT базовых операций. Расширенные команды переводов/возвратов/корректировок, удаления и bulk классификации реализованы в S3-02C. Worker запускается через `go run ./cmd/worker`, миграции — через `go run ./cmd/migrate` с тем же `DATABASE_URL`. Worker выполняет очистку просроченных полных ответов; Начальная схема identity/ledger/sync реализована в S3-02A. Migration runner проверен на PostgreSQL в изолированной тестовой схеме; начальные миграции identity/ledger/sync применены.



## Docker-окружение

Созданы Compose и Dockerfile для веба, PostgreSQL, API, worker и миграций. Требуются Docker Engine и Compose с поддержкой `!override` (2.24.4 или новее), make и openssl.

```bash
cp .env.example .env
# Задайте POSTGRES_PASSWORD в .env: случайное значение из букв/цифр/_/-.
make compose-check
make dev
```

Веб: https://localhost:8443. Локальный сертификат самоподписанный; системное хранилище доверия автоматически не меняется. Vite обновляет файлы web/src без пересборки образа. Для изменений backend выполняйте повторно make dev.

`make down` останавливает окружение, сохраняя volume БД. `make test-up` / `make test-down` используют отдельный Compose-проект и БД accounting_test, веб на https://localhost:8444. `make web-check` проверяет сборку веб-образа; `make backend-check` запускает проверки Go. Не запускайте down с удалением volumes для сохраненных финансовых данных.

Compose ожидает readiness db и успешный migrate перед API/worker. Runner применяет четыре миграции identity/ledger/sync/snapshot-key; реализованы счета/opening и базовые expense/income/классификация; расширенные команды учета S3-02C реализованы. Worker очищает просроченные полные ответы, payload истекших snapshots и непрерывный префикс журнала старше 90 дней. Веб предоставляет online интерфейс ручного учета.

Production overlay compose.prod.yaml — заготовка, не проверенный выпуск. Он требует явных PRODUCTION_DATABASE_URL, PRODUCTION_PUBLIC_ORIGIN, PRODUCTION_HTTPS_BIND и каталогов PRODUCTION_DB_TLS_DIR / PRODUCTION_DB_CA_DIR / PRODUCTION_WEB_TLS_DIR. Сертификат БД должен соответствовать hostname в URL; server.key должен быть доступен только пользователю PostgreSQL внутри контейнера. Во всех TLS-каталогах используются имена файлов из Compose/nginx-конфигурации. Публичная регистрация выключена. Production TLS и браузерная приемка еще не проверены; local/test контейнеры и HTTPS проверены.

Проверены Compose-конфигурации, npm/Go сборки, Docker build/up, HTTPS dev/static/proxy, настоящий PostgreSQL, повтор/конкурентное применение миграций, checksum и rollback, сохранение данных при пересоздании db и блокировка API/worker при сбое migrate. Браузерные cookie/CSRF и production TLS пока not_run. [Схема окружения](docs/decisions/007-repository-structure.md#docker-окружение).

Реальные платформенные PostgreSQL-тесты: `make platform-integration` (отдельная accounting_test, временная схема очищается). При отсутствии TEST_DATABASE_URL обычный go test пропускает этот integration-тест. Если группа docker уже назначена, но текущий сеанс еще не обновился, команды можно выполнить через `sg docker -c 'make dev'`.

Локальный dev-стек оставлен запущенным: https://localhost:8443. Тестовый стек после проверок остановлен без удаления volume. Первая загрузка использует закрытую регистрацию и показывает состояние разработки.

Проверка общей схемы: `make schema-check` — применение/повтор трех миграций в отдельной временной схеме accounting_test, 19 негативных DB-сценариев и сохранение исторических receipts при retention/reset. Проверка не подтверждает готовность финансового API. [Ограничения схемы](backend/migrations/README.md).

## Авторизация

Реализованы /api/v1/auth/register, login, session, renew, logout; /api/v1/me; /api/v1/sessions и отзыв устройства; /api/v1/workspaces. `make auth-check` проверяет их на отдельной PostgreSQL через TLS и очищает временную схему.

REGISTRATION_ENABLED=false сохраняется по умолчанию. Для локальной регистрации задайте true в .env и выполните make dev; production не разрешает публичную регистрацию. Веб поддерживает вход и создание профиля, если регистрация включена. Cookie-запросы используют общий PUBLIC_ORIGIN и X-CSRF-Token; bearer credential возвращается только bearer-клиенту. Пароль сохраняет пробелы/Unicode буквально.

AUTH_HASH_CONCURRENCY=2 ограничивает одновременно работающие Argon2id вычисления; допустимы 1–8. AUTH_IP_ATTEMPTS_PER_MINUTE=100 и AUTH_EMAIL_ATTEMPTS_PER_MINUTE=20 ограничивают auth попытки, ответ 429 содержит Retry-After. Лимитер хранится в памяти процесса и использует адрес непосредственного соединения. За nginx это адрес proxy; доверенное определение клиентского IP и распределенные production-лимиты еще требуют настройки перед выпуском. Произвольным X-Forwarded-For API не доверяет.

Сессии: idle 7 суток, absolute 30 суток; GET не продлевает срок, renew сохраняет credential. Logout/revoke вступают в силу после commit. Финансовые handlers выполняют команды внутри транзакционного guard; оба порядка гонки command/revoke проверены на PostgreSQL.

## Выполнение команд и квитанции

CommandRunner готов к подключению финансовых сервисов: atomic callback, Idempotency-Key/hash, generation guard, terminal 409/422, журнал/аудит и ответ в одной транзакции. Реальный callback повторного key не выполняется снова. API читает собственный outcome через `GET /api/v1/workspaces/{workspace_id}/actions/{action_id}`.

Проверка: `make command-check` (настоящая PostgreSQL, отдельная временная схема). Worker раз в минуту очищает до 1000 просроченных полных ответов одного доступного workspace; компактные квитанции сохраняются. Чтение уже учитывает 30-дневный deadline независимо от запуска cleanup.

Финансовые write endpoints счетов/expense/income/категорий/тегов подключены к runner. Pull и immutable snapshots реализованы; клиентское применение mirror/outbox относится к Android. [Гарантии и границы CommandRunner](backend/internal/sync/README.md).

Счета S3-02B: `GET /api/v1/currencies`, `GET/POST /api/v1/workspaces/{workspace_id}/accounts`, `GET/PUT .../accounts/{id}`. Создание счета атомарно публикует счет и opening в одной группе CommandRunner; нулевая opening не создает entry. PUT меняет имя/тип/архив с expected_version, сохраняя валюту, дату открытия и balance_version. GET и списки вычисляют остатки из активных entries в одном SQL-снимке. Пагинация UUID keyset, cursor связан с credential/endpoint/archived; archived=exclude (по умолчанию), include, only.

`make ledger-check` запускает реальные TLS HTTP/SQL тесты на accounting_test во временной схеме и проверяет захваченные синтетические ответы по OpenAPI через scripts/check-ledger-responses.py. Артефакт — ops/.runtime/checks/ledger-responses.json (без credentials). `make backend-check` без TEST_DATABASE_URL явно пропускает PostgreSQL integration. Первый веб-поток проверяется настоящим браузером через `make e2e` (Node 24 + Chrome); детали и ограничения — в [web/README.md](web/README.md).

Dev/test сети используют подсети 192.168.240.0/24 и 192.168.241.0/24, чтобы избежать конфликта Docker 172.x с VPN. Они настраиваются ACCOUNTING_DEV_SUBNET / ACCOUNTING_TEST_SUBNET в .env; выбирайте свободные диапазоны своей LAN/VPN. После изменения подсети выполните make down и make dev (без удаления volume); test — make test-down перед следующей проверкой. Production overlay сохраняет автоматический выбор Docker-сети.

Базовые операции: `POST /api/v1/workspaces/{workspace_id}/transactions` принимает posted expense/income, одну положительную сумму и 1..100 частей с уникальными UUID, сумма которых равна amount_minor; расход пишет отрицательный entry, доход — положительный. `PUT .../transactions/{id}` заменяет editable поля с expected_version (expense также требует expected_parent_version=null для самостоятельной операции). UUID entry и сохраняемых частей остаются прежними; новый счет должен иметь ту же валюту. Финансовая правка обновляет account.version/balance_version каждого затронутого счета один раз. Изменение note/payee/timezone/классификации без изменения движения не меняет счета. Архивный счет блокирует записи; прежние архивные category/tag назначения сохраняются на PUT, новые запрещены.

`GET .../transactions/{id}` читает aggregate одним SQL statement. `GET .../transactions` имеет AND фильтры account_id/category_id/tag_id/from/to/kind/status/q; from включительно, to исключительно, категория без потомков, q — подстрока note/payee без учета регистра. Курсор (occurred_at DESC, id DESC) связан с credential/workspace/всеми фильтрами; limit 1..100. Нулевая opening включается в историю своего счета через opening_account_id.

Категории/теги: `GET/POST .../categories`, `GET/PUT .../categories/{id}` и аналогично tags. Списки поддерживают archived и UUID keyset. Циклы проверяются по полному дереву под head mutex. Активные теги уникальны по strings.ToLower(strings.TrimSpace(name)) внутри workspace; архивное имя можно использовать повторно, восстановление конфликтующего тега отклоняется. Ручные JSON-команды ограничены 256 KiB.

`make e2e` временно включает регистрацию только в accounting-test через compose.e2e.yaml, проверяет реальный ledger/reload/lost-response retry и останавливает тестовый проект без удаления volume. Не запускайте одновременно другие проверки accounting-test. Local регистрация определяется REGISTRATION_ENABLED в .env. Если VPN блокирует npm внутри Docker build, используйте `DOCKER_BUILD_NETWORK=host` перед make dev/web-check/e2e; это меняет только сборочную сеть, runtime сети остаются изолированными.

Вертикальная приемка S3-07A завершена. `make backend-integration` сейчас запускает TestVertical в отдельной временной схеме accounting_test: новый API/pool восстанавливает session/receipt и не дублирует расход. `make e2e` перезапускает настоящие test API/web между потерей ответа и повтором, сверяет PID/StartedAt и SQL показатели; артефакты — ops/.runtime/checks/vertical-backend.json и ops/.runtime/e2e/*/vertical-browser.json (0600). Это не полный backup или restore drill. S3-02C завершена: расширенные команды учета приняты на PostgreSQL.

S3-02C завершена: `POST .../transactions` принимает transfer с обеими суммами и необязательной fee. Сервер вычисляет сокращенную точную дробь в major units с учетом scales; same-currency суммы равны, курс 1/1. Transfer и posted fee/части/версии/журнал/receipt фиксируются одной командой; fee account может быть третьим счетом другой валюты. GET/history уже возвращают эти aggregates. `make advanced-ledger-check` запускает настоящую PostgreSQL acceptance создания переводов, создания/правки/удаления возвратов и удаления операций с зависимостями, а также проверку captured HTTP responses по OpenAPI. Responses сохраняются с 0600 под UID запускающего пользователя. POST/PUT возвратов и DELETE операций реализованы: лимиты по исходным частям учитывают posted/pending reservations, финансовая правка/удаление обновляет версии расхода и связанных fee/transfer. Исходный расход и UUID сохраняемых частей возврата неизменны. Удаление transfer атомарно удаляет fee; активные возвраты блокируют удаление исходной операции и transfer с возвращенной fee. PUT transfer/fee реализованы: валюты сторон и UUID существующей комиссии неизменны; версии parent и fee обязательны. Отдельный PUT комиссии требует expected_parent_version; ее дата остается датой transfer. Active refund блокирует финансовую правку/удаление комиссии через оба пути. Fee=null удаляет комиссию атомарно, следующая требует нового UUID. Metadata без изменения движения не меняет account versions; итоговые balances проверяются после transfer+fee целиком. POST accounts/{id}/adjustments устанавливает целевой posted остаток с expected_balance_version; PUT adjustment меняет только note, DELETE откатывает движение. POST transactions/classification атомарно заменяет категорию и теги в 1–100 одиночных expense/income без активных возвратов; деньги и account versions сохраняются. Advanced acceptance: 1652 HTTP ответа / 5 схем OpenAPI. Новые веб-формы запланированы в S3-06B.

Pull S3-04B реализован: `GET /api/v1/workspaces/{workspace_id}/sync/changes?cursor=...&limit=50` возвращает целые committed группы из одного REPEATABLE READ snapshot, до 100 групп и 4 MiB. Нужны `SYNC_CURSOR_ACTIVE_KEY` и `SYNC_CURSOR_KEYS` (JSON map key ID → base64 случайного ключа минимум 32 байта); храните прежние ключи минимум срок журнала при rotation. Без ключей pull отвечает 503. Локальный ключ хранится в .env и переживает перезапуск API. Проверка — `make pull-check`. Начальный resume_cursor теперь выдает snapshot API S3-04C.

S3-04C завершена: `POST .../sync/snapshots` с JSON `{"id":"UUID"}` и совпадающим `Idempotency-Key` создает snapshot (без financial action и без X-Sync-Generation). Ответ содержит `first_page_token`, `base_sequence` и `resume_cursor`. `GET .../sync/snapshots/{id}` повторяет metadata; `GET .../sync/snapshots/{id}/pages?page_token=...` возвращает неизменные страницы до 100 items. Только после финальной страницы клиент устанавливает staged mirror и resume_cursor, затем вызывает pull. TTL 15 минут, два живых снимка на actor/workspace, максимум 50 000 items/64 MiB; expired/stale generation дает 410, новый bootstrap требует нового UUID. Snapshot/page tokens требуют настроенных cursor keys. Проверка — `make snapshot-check` (54 HTTP ответа / 5 OpenAPI schemas). Миграция 0004 сохраняет ID ключа для стабильных токенов во время rotation; предыдущие SQL файлы неизменны. Полные веб-формы и клиентское staging остаются отдельными задачами.

S3-03B завершена: `make access-check` проверяет доступ на всех 35 операциях OpenAPI (33 private guards), смешанные вложенные ссылки при POST/PUT, CSRF/mixed auth, scoped receipts/snapshots и отсутствие unauthorized metadata. Реальные PostgreSQL lock-order races проверяют DELETE session/logout/membership revoke в обоих порядках; Go race detector проходит. Артефакты `ops/.runtime/checks/access-responses.json` (203 ответа) и `session-race-responses.json` (28) сохраняются с 0600; тела проверяются по OpenAPI, отдельный validator контролирует route coverage. Runtime API и локальные параметры в этом пакете не менялись. Следующая готовая задача — S3-06B, полный интерфейс ручного учета.

S3-06B начата: в локальном вебе доступны дерево категорий, теги, их редактирование/архивирование/восстановление и распределение дохода/расхода на несколько категорий. Суммы частей проверяются точно; архивные категории/теги сохраняются в истории. Незавершенное редактирование справочника повторяется с исходным ключом и телом после перезагрузки. `make web-check` и два настоящих Chrome E2E (advanced + basic) проходят. Переводы, возвраты, корректировки, редакторы операций, устройства, фильтры истории и массовые правки остаются в S3-06B.
