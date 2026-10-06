# Этап 1: серверная часть и веб-интерфейс

Дата обновления: 2026-10-06. Статус этапа: in_progress.

Актуальный результат: финансовый серверный пакет S3-02A/B/C, sync пакет S3-04A/B/C, первый веб-поток S3-06A и вертикальная приемка S3-07A завершены. Pull: 26 HTTP ответов / 3 OpenAPI schemas; snapshots/bootstrap/cleanup: 54 / 5; advanced financial: 1652 / 5, basic: 179 / 11. Реальные PostgreSQL regression и Go проверки проходят. S3-03A/B завершены: все 35 API операций, 33 private guards, POST/PUT scopes и revoke races приняты на PostgreSQL/TLS (203 HTTP ответа / 10 OpenAPI schemas, 28 / 3; Go race detector PASS). S3-06B начата: категории/теги, архив справочников и разбиение доходов/расходов реализованы, Chrome E2E 2/2 PASS. Следующий шаг — переводы/комиссии и редакторы операций в S3-06B. Клиентское mirror/outbox/staging, расширенный веб и production выпуск еще не завершены.

Ниже сохранена хронология работ; прежние not_run относятся к состоянию на момент соответствующей записи.

## Принятый результат

S2-01 завершена: [сценарии](../decisions/001-mvp-scenarios.md) и [спецификации экранов](../decisions/002-screen-specifications.md). Определены 20 сценариев с ожидаемым поведением и ошибками, текстовый макет основного пути, состояния сети и конфликтов, восемь независимых финансовых примеров. Отделены функции будущих этапов. Реализация велась последовательно.

## Доказательства проверки

Запущен Python 3: проверены все локальные ссылки двух спецификаций, наличие 20 идентификаторов сценариев, пять контрольных расчетов через decimal.Decimal. Результат: PASS. Проведено ручное сопоставление с S2-01: регистрация/сессии, счета, расходы/доходы/переводы/возвраты/части, категории, поиск, массовые правки, повторные запросы, конфликты, офлайн и будущие функции описаны.

Сервер, веб и миграции еще не созданы. OpenAPI создан в S2-03. Сборки, серверные тесты и сквозная приемка не запускались. Рабочая папка не является Git-репозиторием. Стандартный запуск команд не работает из-за ошибки bwrap; чтение, запись и проверка выполнены через разрешенный запуск вне песочницы.

## Открытые зависимости

Проектные решения S2-01–S2-05 готовы. Перед кодом S2-06 и S3-01 готовят структуру, карточки реализации и окружение. Банковских зависимостей нет. Публичная регистрация и реальный restore имеют отдельные зависимости выпуска.

## Финансовая модель S2-02

S2-02 завершена: [модель](../decisions/003-financial-model.md) включает ER-диаграмму, словарь, точные минимальные денежные единицы, дробный курс, формы движений, возвраты, комиссии, мягкие удаления и блокировки конкурентных действий. Валюта счета фиксируется при создании; начальный остаток неизменяем, меняется отдельной корректировкой. Представления остатков разделены на posted и pending.

Запущена команда `python3 contracts/fixtures/check_financial_model.py`: PASS, 13 арифметических примеров, 2 точных курса и расчет конкурентных возвратов. Локальные ссылки модели проверены. Десять сценариев отказа описаны как будущие интеграционные проверки; против сервиса и PostgreSQL они не запускались. Межстрочные ограничения должны быть доказаны при реализации S3-02.

## HTTP-контракт S2-03

S2-03 завершена: [решение API](../decisions/004-api-contract.md), [основной OpenAPI](../../contracts/openapi.json) (30 операций), [отдельный контракт будущих этапов](../../contracts/openapi.future.json) (9 операций), [схема событий](../../contracts/events/workspace-change.schema.json), примеры и воспроизводимая команда проверки. Уточнена версия счета: любое изменение его синхронизируемого представления увеличивает version, финансовое изменение дополнительно balance_version.

`python3 contracts/check_contracts.py`: PASS по сохраненной официальной структурной схеме OpenAPI; все ссылки и компоненты, параметры путей, обязательные action keys, cookie/CSRF security, совпадение общих схем; 20 допустимых и 16 отвергаемых примеров API, 5 событий, 3 примера будущих функций. Дополнительно отвергается несовпадение entity_type конверта и payload для upsert/delete. Денежные границы покрыты примерами ±9000000000000000 и отказом выше лимита. `python3 contracts/fixtures/check_financial_model.py`: PASS, 13 расчетов, 2 точных курса.

Среда: Python 3, предустановленный jsonschema 4.10.3; официальный структурный валидатор сохранен для повторных проверок без сети. pip в системном Python отсутствует, установка пакетов не выполнялась. Сервер, PostgreSQL, CSRF enforcement и работающий протокол синхронизации не проверялись и не объявлены готовыми.

## Синхронизация S2-04

S2-04 завершена: [протокол](../decisions/005-sync-protocol.md), пять новых операций OpenAPI (changes, snapshot creation/metadata/pages, action outcome), ordinal/group_size в событиях и [контрольные примеры](../../contracts/fixtures/sync-examples.json). Основной контракт теперь содержит 35 операций; будущий — 9. Модель и решение API согласованы с протоколом.

Приняты: транзакционный mutex и счетчик групп на workspace; целые группы на страницах; immutable snapshot TTL 15 минут; журнал минимум 90 суток; полный результат действия 30 суток, компактная квитанция до удаления пространства. Reset cursor заменяет только confirmed mirror, сохраняет outbox и локальный overlay; старые ответы не перезаписывают новые версии или tombstone. Receipt applied не снимает overlay до появления соответствующего подтвержденного состояния.

`python3 contracts/check_contracts.py`: PASS обоих OpenAPI по официальной структурной схеме; всех предыдущих примеров; 10 допустимых и 6 отвергаемых схем синхронизации; 12 семантических отказов (неполные/смешанные группы, разрыв sequence, неверный has_more, некорректные снимки/квитанции). Дополнительно проверены 5 границ retention, 3 расписания writer commit/rollback/reject и небезопасная перестановка commit, 8 случаев квитанций, граница страницы, bootstrap с сохранением outbox, 5 случаев старых версий, 3 случая overlay и 3 пары канонических hash. `python3 contracts/fixtures/check_financial_model.py`: PASS 13 расчетов и 2 курсов. Все локальные ссылки решений и README проверены.

Расписания и восстановление здесь — модели и проверка спецификации. Они не подтверждают фактическое поведение PostgreSQL locks, commit, Room и авторизации. Эти проверки остаются в S3-04 и этапе Android.

## Проверка проектирования S2-05

S2-05 завершена: [результаты проверки](../decisions/006-design-review.md), [матрица 34 сценариев](../../contracts/fixtures/design-review-cases.json). Исправления согласованы в сценариях, модели, API, протоколе, схемах, примерах и README контрактов.

Найденные и исправленные проблемы: подмена исходного расхода возврата; изменение денежной валюты при смене счета; обход зависимостей комиссии через PUT перевода; гонка с отзывом сессии; применение старой истории после reset/restore; отчетные суммы больше лимита одной операции; блокировка правки заметки из-за неизмененной архивной категории/тега. Удалено ложное обещание скрывать существование email при мгновенной регистрации: local/test режим разрешается явно, публичная регистрация выключена до verification-flow.

Контракт draft 0.2.0 содержит 35 основных и 9 будущих операций. Изменяющие workspace-команды (кроме создания read-only snapshot) требуют X-Sync-Generation; generation_id есть в результатах/группах/событиях/снимках, Workspace содержит sync_generation_id. Applied receipt сохраняет исходное поколение. AggregateMoney до 40 цифр отделен от лимитированного Money отдельных сумм/счетов. Существующих клиентов 0.1.0 не было.

`python3 contracts/check_contracts.py`: PASS официальной структурной схемы обоих OpenAPI, ссылок/компонентов/headers/security; 20 допустимых/16 отвергаемых прежних API examples, 5 событий, 3 будущих примера; 10 допустимых/6 отвергаемых sync schemas, 13 семантических отказов; матрицы 34 маршрутов/статусов/кодов/владельцев, 7 новых schema cases, 3 независимых финансовых расчетов, 4 сценариев поколения. `python3 contracts/fixtures/check_financial_model.py`: PASS 13 расчетов/2 курсов. Локальные ссылки всех решений и README проверены.

Все 34 интеграционных сценария имеют not_run. Реальные доступ/CSRF/Argon2/отзыв/locks/rollback/PostgreSQL/Room не проверялись. Открытые зависимости: PUBLIC-REGISTRATION (S6-02), RECOVERY-RPO (S6-03). Они не закрыты бумажной проверкой и не блокируют локальную разработку.

## Структура и задачи S2-06

S2-06 завершена: [структура репозитория](../decisions/007-repository-structure.md), корневой README, 15 карточек реализации и 6 агрегирующих карточек. Проверены ссылки, отсутствие циклов зависимостей и перенос всех 34 DR-сценариев к владельцам реализации. Базовый сквозной путь выделен перед расширенными финансовыми функциями.

## Go-каркас S3-01A

S3-01A выполняется: созданы API, worker и migration runner, Go-модуль с pgx/v5 v5.11.0, конфигурация с закрытой по умолчанию регистрацией, отдельные liveness/readiness, контрактные ошибки с request_id, безопасные логи и graceful shutdown. Runner проверяет последовательность и checksum SQL, сериализует применение транзакционной advisory lock. Финансовых SQL и бизнес-маршрутов пока нет; worker ожидает завершения и не исполняет задания.

Фактически выполнены `go test ./...`, `go vet ./...` и сборка всех трех команд из backend/: PASS. Тесты покрывают конфигурацию/TLS, отсутствие утечек credentials, request_id и ошибки, panic recovery до/после начала ответа, последовательность/checksum миграций и отсутствие фиктивных миграций. Ошибка шаблона go:embed обнаружена проверкой и исправлена до успешной сборки.

Запущен собранный HTTP-процесс с недоступной тестовой БД: live 200, ready 503, неизвестный API-маршрут 404 с проверкой Error по OpenAPI; корректное завершение и отсутствие тестового пароля в логах — PASS. Эти проверки не подтверждают работу финансовых функций.

PostgreSQL, Node/npm и контейнерный runtime отсутствуют в текущем окружении. Реальные SQL/locks/migration rollback и успешный запуск worker с БД: not_run. Контракты в этой итерации не менялись; прежние проверки не запускались повторно. Следующий шаг — закончить проверку S3-01A с PostgreSQL, подготовить локальное окружение S3-01C и веб-каркас S3-01B. Первый этап целиком не завершен.

## Уточнение архитектуры: Docker

По запросу пользователя закреплен Docker Compose: веб и PostgreSQL в отдельных контейнерах вместе с API/worker и миграциями. Обновлены общий план, решение 007, план этапа и S3-01B/C. Определены общий HTTPS origin, внутренняя сеть, постоянный DB volume, изолированное test-окружение и порядок запуска. Production verify-full сохранен. Docker-файлы и контейнерные проверки пока не выполнены; статусы задач не повышены.

## Реализация Docker и веб-каркаса S3-01B/C

Созданы compose.yaml, dev/test/production overlays, Dockerfile backend/web, React-каркас, lockfile, nginx HTTPS proxy, локальный TLS script, Makefile и env example. PostgreSQL 18 использует volume на /var/lib/postgresql; нет публичного DB-порта. API/worker ожидают успешных миграций. Dev использует Vite за общим HTTPS proxy; test проект/БД/volume отделены от local. Production overlay требует явных TLS-конфигурации и origin, остается заготовкой до настоящей приемки.

Проверенный SHA-256 Node 24.21.0 и Compose v5.6.0 размещены в /tmp без системной установки. npm ci, typecheck и production build: PASS (React 19.3.0, Vite 8.3.2, TypeScript 7.0.2). Все четыре Compose-конфигурации прошли официальный CLI; зависимости миграций, отсутствие DB-порта и разные local/test volume names проверены. Локальный TLS-сертификат создан в игнорируемом ops/.runtime.

Docker build/up, настоящий PostgreSQL, migration repeat/rollback, проверка persistence, production TLS и браузерные cookies/CSRF: not_run, Docker daemon отсутствует. S3-01A/B/C остаются in_progress. Веб не подменяет финансовые функции фиктивными данными. Следующий шаг — проверить созданное окружение на доступном Docker runtime, затем реализовать общую схему S3-02A.

## Проверка после установки Docker

Docker CLI 29.8.2 и Compose v5.6.0 установлены. `make compose-check`: PASS всех конфигураций и изоляции volumes. Доступ к Docker daemon блокирован: сокет /var/run/docker.sock принадлежит root:docker, текущий процесс igolubev не входит в группу docker; sudo -n требует пароль. Права сокета и системные группы агентом не менялись. Контейнеры/миграции/persistence остаются not_run до обновления доступа и сеанса.

## Docker runtime и PostgreSQL: фактическая приемка платформы

После добавления пользователя в docker доступ получен через sg docker без изменения прав сокета. Создан .env со случайным локальным паролем и mode 0600 (секрет не выводился). Собраны backend/web/dev/static образы; local и test окружения запущены. PostgreSQL 18 healthy, runner успешно завершился, API/worker работают; повтор migrate возвращает count=0.

HTTPS с проверкой локального сертификата: dev 8443 и static 8444, страницы/health live/ready 200, неизвестный API 404 с совпадающим request_id — PASS. TestPostgresMigrationTransactions на настоящей accounting_test: конкурентное применение один раз, повтор без изменений, отказ измененного checksum, rollback частичного SQL и истории — PASS. Команда make platform-integration добавлена в Makefile; тест без TEST_DATABASE_URL явно skipped.

В тестовом проекте контрольная строка пережила force-recreate db, probe schema очищена. Сбой migrate (/bin/false в временном test override) блокирует запуск API/worker; затем нормальная конфигурация восстановлена — PASS. Тестовый стек остановлен make test-down без удаления volume; local оставлен работающим. make compose-check и backend-check после изменений — PASS.

S3-01A завершена. S3-01B/C сохраняют in_progress: browser cookie/CSRF и production TLS еще not_run. Финансовой схемы и бизнес-маршрутов нет. Следующий пакет — S3-02A (общая схема) с дальнейшей настоящей приемкой auth/финансов/веба.

## Общая схема PostgreSQL S3-02A

S3-02A завершена: созданы 0001_identity.sql, 0002_ledger.sql и 0003_sync.sql (19 прикладных таблиц), справочник шести валют, индексы и локальные CHECK/FK/UNIQUE. Composite workspace FK исключают межпространственные ссылки. UUID, positive versions, opening_account_id для нулевого opening, одна opening за историю и одна активная комиссия, неизменная валюта счета/scale используемой валюты, точные money/целочисленный FX ограничены в БД. Сессии хранят token/CSRF hashes. Sync head согласован с поколением workspace отложенным FK; actions и snapshot markers сохраняют старое поколение независимо от очищаемого журнала.

make schema-check на настоящей PostgreSQL: первое применение count=3, повтор count=0, 19 негативных сценариев — PASS (чужие account/category/tag, нулевая/слишком большая сумма, валюта/scale, opening/fee, version, дробный FX, границы head/generation, self-cycle, group size, snapshot id/TTL, rejected receipt generation). Дополнительно проверены валютный справочник, разрешенная замена удаленной комиссии, сохранение receipt после удаления группы и атомарной rotation поколения. Временная схема очищена. Новый Compose schema-check и Makefile target делают проверку воспроизводимой; обычный go test без TEST_DATABASE_URL явно пропускает integration.

make migrate применил 3 файла к локальной accounting; повтор не применяет новых файлов. История SQL версий и HTTPS readiness 200 проверены. Dev-контейнеры обновлены и оставлены работающими; test остановлен без удаления volume. Backend-check и Compose config — PASS; contracts не менялись.

Формы движений, суммы частей, лимиты возвратов, финансовая валютная неизменность агрегата, полное дерево категорий, согласованность группы/снимка, авторизация, DB-права и сервисный порядок блокировок еще требуют S3-02B/C, S3-03A и S3-04A. Эти межстрочные инварианты не объявляются защищенными текущими CHECK. HTTP DR-F01/F05/DR-A02 остаются not_run. Production TLS и браузерные cookie/CSRF — not_run. Следующий готовый пакет — S3-03A (identity/sessions/guards), затем CommandRunner S3-04A и основной ledger S3-02B.

## Identity и сессии S3-03A

S3-03A завершена: атомарная local/test регистрация user/workspace/membership/generation/head/session; login, restore/renew/logout/revoke, profile read/update, sessions/workspaces keyset lists. Cookie __Host-accounting_session Secure/HttpOnly/SameSite=Lax/Path=/; bearer и cookie ответы разделены. CSRF/Origin и неоднозначные credentials проверяются HTTP adapter. Argon2id x/crypto v0.57.0, literal password, случайные salt, constant-time compare, dummy hash для неизвестного email и ограничение hashing memory реализованы.

WithSession держит users→sessions locks до callback/commit, RequireMembership добавляет membership SHARE. Identity mutations получают user UPDATE; expiry проверяется по DB clock после locks, last_seen обновляется отдельно. Idle 7 суток / absolute 30 суток, GET не продлевает, renew не меняет credential. Конфигурируемые concurrency и auth лимиты; 429 с Retry-After. JSON отклоняет unknown/case-mismatched fields, duplicates, неверный UTF-8 и unpaired surrogates; password не trim/normalize.

make auth-check на настоящей accounting_test в временной схеме через httptest TLS: PASS регистрации и rollback duplicate, cookie flags/CSRF restore, Origin/CSRF, password literal и общего отказа unknown/wrong password, bearer, mixed auth 400, profile/renew, lists/cursors, foreign session 404 без отзыва, own revoke/idempotent revoke/logout, idle/absolute expiry, закрытой регистрации, revoked membership guard, disabled и отсутствия credentials в логах. Реальный limiter 429 проверен. Unit tests Argon2/strict JSON/config limits, backend-check и Compose config: PASS. Integration без TEST_DATABASE_URL явно skipped.

Найден и исправлен nginx 502 после пересоздания API: proxy_pass использует внутренний Docker DNS resolver с обновлением, [основание nginx](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass). nginx -t/reload и повторное обновление API — PASS; HTTPS proxy auth register 403, me/session 401 валидированы по Error OpenAPI. Local остается работающим, test остановлен без удаления volume. Регистрация local остается false.

Benchmark на текущем Intel Core Ultra 7 155H: 3 Argon2id hash, около 59.5 ms/op и 67.1 MB allocated/op; это локальное измерение, не production capacity. AUTH_HASH_CONCURRENCY=2 по умолчанию. Limiter пока in-memory одного процесса, peer IP за nginx является адресом proxy; trusted proxy/client IP и распределенные limits требуют S6 production настройки.

DR-A03/A04/A06/A08/A09 покрыты identity тестом; A05 подтвержден для identity middleware, финансовые action lookup/handlers еще not_run. DR-A07 и полные финансовые resource negatives — S3-03B. Browser cookie/CSRF, verification-flow/public registration и production TLS не объявлены выполненными. Contracts/дизайн-фикстуры не изменялись. Следующий готовый пакет — CommandRunner S3-04A, затем ledger S3-02B.

## CommandRunner и атомарный журнал S3-04A

S3-04A завершена: generic transactional callback без ledger import; guards users/session/membership → head mutex → receipt/hash → generation → domain savepoint → группа/события/аудит/head/receipt → commit. Applied/terminal rejection и transient abort разделены. Rejection 409/422 откатывает domain writes и сохраняет неизменяемый отказ; nonterminal/uncertain commit не публикуют успех. Deadlock/serialization из callback имеют максимум 3 попытки, без повторов внешних эффектов.

Canonical UTF-8 JSON/hash реализован без float: Unicode/key order, LF framing, original generation, integer-only numbers, порядок массивов; duplicate keys/malformed UTF-8/surrogates отвергаются. Независимые canonical fixtures, точные escapes/<>&/U+2028/29 и негативные unit cases — PASS. MutationResult, ReceiptEntity и change envelopes формируются из общего changes набора; unique aggregates, ordinal/group_size, лимиты 200/1 MiB и int64 head overflow проверяются до commit.

make command-check на настоящей PostgreSQL: PASS initial/replay/hash conflict, terminal rollback/повтор отказа, полный abort/повтор ключа, concurrent same key с одним callback, реальный head wait и rollback без sequence дырки, GET 404 до commit, настоящий deadlock с bounded retry. HTTP adapter через TLS: Idempotency-Replayed, bearer/cookie, CSRF/Origin до replay, own Outcome 200/foreign actor 404 — PASS. Тестовые write handlers существуют только в тесте; продуктовые финансовые handlers не открыты.

Срок 30 суток проверяется при replay/Outcome; expired response возвращается null/409 без повторного выполнения. Worker compaction удаляет только full response под head lock, batch<=1000, сохраняя hash/status/metadata/compact outcome. Очистка и повтор после нее — PASS. Old-generation replay, new old-generation rejection без journal, revoked membership до replay/outcome — PASS. Oversized byte payload — fault injection, не валидная финансовая операция; он не публикуется. Аудит не содержит финансовый payload.

Backend-check и Compose config — PASS. Local API/worker обновляются с новыми службами; SQL/контракты не менялись. DR-S05–S09 подтверждены на уровне runner и actor-scoped outcome/тестового HTTP adapter; полноценные financial HTTP retries DR-F10/F12 остаются not_run. Pull/cursors/snapshots, 90-day journal cleanup, ledger-инварианты, browser acceptance и production DB роли остаются отдельными задачами. Следующий готовый пакет — S3-02B (деньги, счета и основной ручной учет); также готовы S3-04B/C.

## S3-02B: денежный фундамент (частичная реализация)

Начата S3-02B. Добавлены Money и AggregateMoney в backend/internal/ledger/money.go. Строгий канонический decimal формат совпадает с OpenAPI: без -0, ведущих нулей, плюса, пробелов, дробей, экспоненты или Unicode цифр. Money ограничен ±9 000 000 000 000 000; PositiveMoney исключает ноль и отрицательные суммы; AggregateMoney допускает до 40 цифр. JSON сериализация всегда строковая, float не используется.

Суммирование использует big.Int для промежуточных сумм и проверяет итоговый диапазон: порядок движений не вызывает ложного переполнения. Balances проверяет posted, pending и projected независимо, включая ситуацию, когда projected скрывает превышение posted/pending. Денежные значения неизменяемы через публичный API; нулевые значения типов валидны.

Unit tests: границы диапазонов, неканонический ввод, JSON строки, положительные суммы, взаимное сокращение 4096 движений с промежуточной суммой за int64, баланс 100000 − 1234 = 98766, pending/projected и 40-digit aggregate — PASS. make backend-check (gofmt/vet/tests/build) — PASS. PostgreSQL integration tests этой командой без TEST_DATABASE_URL skipped; финансовые endpoints, account/opening, expense/income и классификация остаются not_run. S3-02B остается in_progress. Следующий шаг — account/opening service внутри CommandRunner и реальные PostgreSQL проверки.

## S3-02B: счета, opening и архивирование (2026-10-05)

Реализованы account service и HTTP: POST/GET list/GET aggregate/PUT accounts, GET currencies. Создание через CommandRunner после guards/head атомарно добавляет account + одну posted opening: нулевой остаток без entries, ненулевой с одним signed entry. Оба полных агрегата входят в одну journal group и MutationResult. Currency/opened_at остаются неизменяемыми через API. PUT блокирует счет, сравнивает expected_version и публикует новую account.version; metadata/archive не меняют balance_version или opening. Конфликт возвращает текущую version/balance_version после проверки доступа.

Account GET/list собирают aggregate и posted/pending/projected через один SQL statement из entries с учетом status/deleted_at родительской transaction. Списки имеют UUID keyset, limit 1..100, archived exclude/include/only и HMAC cursor, привязанный к session credential, endpoint/workspace и archived. Malformed/duplicate/unknown query отклоняются. Account input проверяет точную required shape, unknown/null/type, UUID, minor units, UTC instant и IANA timezone; будущая дата и неизвестная валюта дают terminal rejection. UUID collision не переиспользуется. HTTP adapter требует application/json, сохраняет совместимый generic MutationHandler и добавляет request-aware factory для domain routes. SQL миграции и OpenAPI не менялись.

make ledger-check — PASS на настоящей PostgreSQL accounting_test в отдельной временной схеме через TLS HTTP: положительная, нулевая и отрицательная opening на границе 9e15; journal из двух агрегатов; исходный/конкурентный replay без второго движения; duplicate account; invalid amount без receipt; future date и replay terminal rejection; unsupported currency; metadata/archive/restore без изменения остатка и balance_version; stale/concurrent expected_version; keyset pagination, query rejection и cursor scope/session binding; currencies/auth; чужое пространство/чужой account 404; Origin/CSRF перед cookie replay; инъекция DB сбоя после account insert откатывает account/receipt, повтор после устранения сбоя успешен; revoked membership блокирует GET и replay. Временная схема удалена.

make backend-check (format/vet/unit/build), make command-check (PostgreSQL regression общего runner) и Compose config — PASS. Настоящая browser acceptance остается not_run: cookie tests выполняются TLS HTTP-клиентом. S3-02B остается in_progress; expense/income/allocations, категории/теги и полная финансовая matrix еще не выполнены. Следующий шаг — ручные expense/income с частями и классификацией внутри CommandRunner.

Local API/worker/web пересобраны и запущены. При проверке хостового HTTPS найден реальный конфликт VPN route 172.18.0.0/16 через cscotun0 с Docker bridge: внутри nginx HTTPS и API ready работали, на localhost TLS handshake зависал. Dev/test получили настраиваемые IPAM подсети ACCOUNTING_DEV_SUBNET=192.168.240.0/24 и ACCOUNTING_TEST_SUBNET=192.168.241.0/24. Сети пересозданы через down без -v; том PostgreSQL сохранен. .env.example, архитектура и README обновлены. Compose checker проверяет переопределение переменных на synthetic подсетях.

После исправления HTTPS localhost:8443 с проверкой локального сертификата: страница/live/ready 200, currencies/accounts без сессии 401 unauthenticated — PASS. make ledger-check повторно на новой test-сети — PASS. Test остановлен с сохранением volume; local dev остается запущенным.

## S3-02B завершена: базовый финансовый API (2026-10-05)

Добавлены posted expense/income с 1..100 allocations и до 50 tags; расход имеет один отрицательный entry, доход — положительный. Сумма частей проверяется через big.Int без округления; UUID частей/тегов нормализуются и проверяются на уникальность. Полная PUT правка самостоятельных базовых операций сохраняет UUID entry/прежних частей и денежную валюту; затронутые счета блокируются в UUID порядке перед transaction. Amount/account/date изменения обновляют account.version/balance_version один раз для каждого затронутого счета. Note/payee/timezone/классификация без изменения движений публикуют только transaction. Нулевую и ненулевую opening нельзя превратить в другой kind или править через этот маршрут.

Создание/правка блокируются архивным исходным/новым счетом; occurred_at проверяется относительно opened_at и DB clock. После записи проверяются posted, pending и projected каждого затронутого счета, затем публикуются полные account/transaction aggregates в одной группе CommandRunner. Ошибка в allocation UUID/sum/references/range откатывает финансовые изменения до terminal receipt. Чужие resource references дают 404 без финансового payload. Старый финансовый receipt возвращает исходный ответ после последующих правок и архивирования классификации.

Категории/теги имеют GET/list/POST/PUT и version guards. Циклы проверяются по всему дереву (включая архив) под workspace head. Теги нормализуются trim + Unicode lowercase; активные names уникальны в workspace. Архивное имя разрешено использовать повторно, восстановление конфликтующего тега отклоняется. Неизмененный архивный parent/category/tag допускается на PUT; новый архивный assignment запрещен, категория сравнивается по allocation UUID.

Transaction GET/история materialize агрегат, children и версии одним SQL statement. История: AND filters account/category/tag/from/to/kind/status/q, точная category без потомков, from включительно/to исключительно, literal case-insensitive substring; keyset occurred_at DESC/id DESC. HMAC cursor связан с credential/workspace/filters, hash filters сохраняет длину cursor <=2048 даже при 200 Unicode символах q. Учтена нулевая opening без entries через opening_account_id. Strict nested shapes, nullability, Unicode text, canonical UTC RFC3339 и ручной HTTP limit 256 KiB реализованы. SQL миграции/OpenAPI не менялись.

make ledger-check на accounting_test через реальные TLS HTTP routes — PASS: baseline 100000 − 1234 = 98766; independent simultaneous expenses; positive income; exact split sum mismatch на 1; duplicate/colliding allocation UUID; posted overflow на обоих границах; projected overflow при валидной pending DB fixture (manual API остается posted-only); date limits; archived account после чтения; note-only edit с архивными assignments без изменения account; новые архивные assignments; stale versions; amount edit и same-currency account move с обновлением обеих версий; cross-currency отказ; stable child UUIDs; category cycle race/archive/self-cycle; tag normalization/restore collision; foreign category/tag/account; 256 KiB body.

Конкурентная серия GET во время 20 финансовых PUT проверяет, что version/entry/allocations принадлежат одному снимку. Проверены filters, half-open boundary, literal search, pagination/ties/filter binding/Unicode bound и полнота journal/receipt. 179 захваченных синтетических HTTP ответов по 11 schemas прошли JSON Schema/format validation из неизмененного OpenAPI через scripts/check-ledger-responses.py. Артефакт ops/.runtime/checks/ledger-responses.json содержит только synthetic responses, без credentials.

make backend-check (format/vet/unit/build), PostgreSQL regressions make command-check и make auth-check, Compose config и checks спецификаций/финансовых fixtures — PASS. Базовые DR-F02/F03/F05/F11/F13 подтверждены ledger acceptance; design-review fixtures сохраняют исходный not_run и не объявляются целиком выполненными. SQL прямые write permissions/deferred межстрочные проверки, advanced transfer/refund/fee/adjustment/delete/bulk, sync pull/snapshots, browser acceptance, production TLS остаются отдельными задачами.

S3-02B: done, родитель S3-02: in_progress (S3-02C todo). Следующий пакет — S3-06A: регистрация/login local/test, счет EUR opening 100000 и расход 1234 с итогом 98766 в браузере; затем S3-07A с реальными reload/retry до расширения advanced операций.

Local Docker API/worker обновлены итоговыми образами. HTTPS localhost:8443 с проверкой сертификата: страница/ready 200, transactions/categories/tags без сессии 401 unauthenticated — PASS. Регистрация local остается выключенной; публичный выпуск не включался. Test-проект остановлен без удаления volume, synthetic response artifact сохранен; dev остается запущенным.

## S3-06A завершена: первый настоящий веб-поток (2026-10-05)

React заглушка заменена приложением: cookie register/login/session/logout, workspace/generation, создание EUR bank account с opening, posted expense/income с одной неклассифицированной частью, остатки и последние 100 операций. Формы и отображение денег используют BigInt/string minor units без float и округления; примечания/получатели выводятся обычным React текстом. API клиент использует same-origin credentials include; CSRF только в памяти, пароль/cookie/CSRF не сохраняются в browser storage.

Команда получает неизменяемые Idempotency-Key, JSON body и X-Sync-Generation. Один незавершенный запрос сохраняется в sessionStorage вкладки с привязкой к profile/session/workspace, переживает reload и повторяется с тем же ключом. При неопределенном результате формы/выход блокируются, success не объявляется. После подтверждения выполняется GET текущих aggregates, поэтому старый replay не затирает актуальный остаток. Generation rejection блокирует новые записи до успешного обновления; offline запрещает новые операции и помечает показанные данные как потенциально устаревшие. Action-result expiry/key conflict требуют сверки и не создают новый ключ. Запоздавшие данные старой сессии не устанавливаются после смены пользователя.

Добавлены закрепленный Playwright 1.63.0, web/tests/e2e/basic.spec.ts, make e2e и scripts/e2e.sh. Отдельный accounting-test запускается с настоящими web/API/PostgreSQL на HTTPS 8444; compose.e2e.yaml включает регистрацию только test API. Скрипт проверяет ready и запускает установленный Chrome с Node 24, затем down без удаления volume. Browser trace/auth dump отключены; синтетические профили остаются только в тестовой БД. Обычная local регистрация false.

make e2e — PASS (Chrome 154.0.8037.57, Node 24.21.0, 1 последовательный сценарий): browser register/cookie HttpOnly+Secure/SameSite Lax; opening 100000; expense 1234; настоящий server commit с последующим намеренным обрывом ответа; reload вкладки и same-key/body/generation retry с Idempotency-Replayed:true; ровно одна expense и остаток 98766. Повторный reload и logout/login сохраняют результат. Чужой account в команде другого настоящего пользователя дает 404; stale generation дает 409, неверный CSRF перед replay 403; реальный generation rejection в UI блокирует формы до обновления. Offline блокирует запись. Примечание с HTML/onerror отображается буквально, img/исполнение отсутствуют (начало DR-A10).

make web-check (окончательный Docker target build, tsc/Vite) и make compose-check — PASS. Контракты/SQL/Go не изменены; Go regression повторно не запускался. Полный DR-A10, advanced financial flows, persistent offline outbox/pull/snapshot, UI пагинация и автоматическое разрешение expired receipts остаются отдельными задачами. S3-06A: done; S3-06: in_progress (S3-06B todo). S3-07A остается todo: браузерный reload проверен, но API/web container restart с DB snapshot еще не выполнен.

На этом хосте npm registry недоступен из Docker build bridge при VPN, а host network возвращает 200. Добавлен опциональный DOCKER_BUILD_NETWORK=host для web/web-dev и make web-check; default сохранен. Проверки выполнены с `PATH=/tmp/node-v24.21.0-linux-x64/bin:$PATH`, `DOCKER_BUILD_NETWORK=host`, `sg docker -c 'make e2e'`, затем `sg docker -c 'make web-check'`, `sg docker -c 'make compose-check'` и `sg docker -c 'make dev'`. Runtime IPAM/DB изоляция не менялись.

Local dev обновлен и остается запущенным на https://localhost:8443. HTTPS с проверкой локального CA: страница/ready 200, session без cookie 401 — PASS. Дополнительная настоящая Chrome проверка local: регистрация 403 с понятным сообщением, экран на ширине 390px без горизонтального overflow — PASS; синтетический screenshot ops/.runtime/local-web-mobile.png просмотрен. Test остановлен, volume сохранен. Следующий пакет — S3-07A: перезапуск API/веба и фиксация результата вертикального пути, затем S3-02C/S3-06B.


## S3-07A завершена: сохранность первого вертикального пути (2026-10-05)

Добавлен backend/tests/integration/vertical_test.go и сервис vertical-check/команда make backend-integration. Проверка создает отдельную временную схему только в accounting_test, применяет текущие миграции, регистрирует синтетических пользователей и выполняет настоящий TLS account/expense HTTP. Затем закрывает TLS server/pool и создает новые pool, handler и identity/command services. Прежний credential восстанавливает session; receipt исходного расхода сохраняется и replay возвращает тот же полный JSON со статусом 201. JSON сравнивается по содержимому: jsonb может менять порядок ключей/пробелы. После чужого GET и foreign account assignment 404 контрольные финансовые показатели владельца неизменны. Схема удаляется после теста.

В browser basic acceptance добавлен настоящий docker compose restart api web в фиксированном accounting-test. Он выполняется после реального commit расхода 1234 и намеренной потери ответа, до reload и same-key retry. StartedAt/PID api и web изменились; PostgreSQL StartedAt/PID/контейнер остались прежними. Ready ожидается через настоящий HTTPS API. Cookie session ID после рестарта прежний; pending JSON/key/generation пережили reload, повтор получил Idempotency-Replayed:true. Остаток 100000 − 1234 = 98766, одна expense, один expense receipt; повторный reload и logout/login тоже успешны. Foreign resource/CSRF/generation/offline/text rendering из S3-06A продолжают проходить.

Контрольный SQL снимок до рестарта, после рестарта и после replay совпадает: posted_balance_minor="98766", account_version="2", balance_version="2", opening_count="1", expense_count="1", entry_count="2", allocation_count="1", receipt_count="2", group_count="2", change_count="4", head_sequence="2", generation неизменна. Снимок — selected ledger measurements, не полный PostgreSQL backup и не protocol sync snapshot. Он не заменяет restore drill S6-03.

Артефакты сохраняются вне исходников с правами 0600: ops/.runtime/checks/vertical-backend.json (контейнерный runner/root) и ops/.runtime/e2e/basic-real-cookie-session--e68ee--restart-and-text-rendering/vertical-browser.json (текущий пользователь). Browser JSON содержит before/after измерения, service/container ID/PID/StartedAt и allowlist событий API (2 api_started, 26 request_completed). Полные inspect/env/users/sessions/request bodies/credentials/password hashes не сохраняются. Проверено, что before_db=after_db, same_command/replayed=true и browser file mode=0600. Экспорт backend artifact тоже использует 0600; его владелец остается runner.

Фактические команды:

```bash
GOCACHE=/tmp/accounting-go-build GOMODCACHE=/tmp/accounting-go-mod make backend-check
sg docker -c 'make backend-integration'
PATH=/tmp/node-v24.21.0-linux-x64/bin:$PATH DOCKER_BUILD_NETWORK=host sg docker -c 'make e2e'
DOCKER_BUILD_NETWORK=host sg docker -c 'make web-check'
sg docker -c 'make compose-check'
```

Результат: все PASS. Go vertical — настоящая PostgreSQL, TestVertical PASS; без TEST_DATABASE_URL обычный backend-check явно skips integration. Chrome 154.0.8037.57 / Playwright 1.63.0 / Node 24.21.0: один E2E сценарий PASS с настоящими перезапусками (6.7 s). Контракты, миграции и production domain code не менялись. Local dev продолжает работать на HTTPS 8443; test остановлен без удаления volume, регистрация local остается false.

Автоматическая проверка отклонила расширение прав синтетического контрольного файла с 0600 на 0644; применен безопасный вариант без расширения доступа. Проверка и сохранение артефактов завершены, блокирующих действий не осталось.

S3-07A: done, S3-07: in_progress (S3-07B todo). Принят graceful API/web restart; аварийный kill на границе commit, production роли/TLS, полный DR-A10, advanced ledger и PostgreSQL backup/restore не объявляются завершенными. DR-F02/F03 остаются покрытием прежнего ledger-check, в этом пакете не повторялись. Следующий готовый пакет — S3-02C; далее S3-03B, S3-04B/C, S3-06B и полная S3-07B.


## S3-02C: создание переводов и атомарная комиссия (2026-10-05)

Реализован первый законченный блок S3-02C: POST /api/v1/workspaces/{workspace_id}/transactions принимает TransferCreate из неизмененного OpenAPI. Ledger вычисляет сокращенную дробь (target_minor × 10^source_scale)/(source_minor × 10^target_scale) через big.Int; необязательный client rate принимается только при точной эквивалентности cross multiplication. Обе суммы — PositiveMoney strings, positive rate integers ограничены 40 digits. Разные счета обязательны; same-currency суммы должны совпадать, сохраненный курс — 1/1. FX rate 1/3 не заменяется округленным decimal. SQL миграции, currencies и контракт не менялись.

Создание transfer делает два posted entries (source negative / target positive), без allocations. Необязательная комиссия — отдельная posted expense с parent_transaction_id нового transfer, своим UUID/note/tags/allocations; date/timezone наследуются от transfer, payee — от родителя. Ее account может совпадать с source/target или быть третьим счетом другой валюты. Nested wire shapes проверяются строго, включая исходные allocation objects без потери unknown fields при повторной сериализации. Fee amount равен сумме частей без округления; новые archived/foreign category/tag/account assignments отклоняются.

Весь command выполняется внутри существующего CommandRunner: guards → head → accounts в UUID порядке → transfer/fee/entries/parts → проверка итоговых posted/pending/projected → один account.version/balance_version bump для каждого затронутого account → полные transaction/account aggregates одной journal group и receipt. Комиссия на том же счете не вызывает двойного bump. Итоговая проверка допускает временную gross сумму выше лимита, если transfer+fee дают допустимый net balance; на target=max 9e15 incoming 1 и fee 1 проходят, самостоятельный incoming 1 отклоняется. GET и keyset history используют существующее materialization и возвращают transfer, linked fee и обе валютные стороны.

Добавлены transfer.go/transfer_test.go и TestLedgerAdvanced в ledger_advanced_test.go. Unit PASS: 1/3, scale 0/2/3, большой exact ratio, эквивалентная 40-digit дробь, отказ rounded/invalid rate, обязательные nullable поля и строгие fee allocation shapes. make backend-check (format/vet/unit/build) — PASS; PostgreSQL integration этим host target без TEST_DATABASE_URL skipped.

make advanced-ledger-check — PASS на настоящей accounting_test/TLS HTTP во временной схеме: equal-currency transfer, original/concurrent same-key replay, DR-F01 A→A и 10000/9999, supplied 2/6→stored 1/3, отказ rounded 0.33333333, JPY→KWD scales, source-shared fee и точный +1 version, third JPY fee, parent/fee UUID/date/aggregate linkage и полная journal group, net-zero target на верхнем лимите, upper/lower overflow, fee split mismatch на 1, fee UUID/allocation collisions с откатом parent, foreign accounts/fee account, future date, archived fee account, real category/tags и foreign/archive classification отказ.

Инъекция DB exception при fee allocation insert (после записей transfer, fee и entries) дала 503 и полный rollback: accounts/versions прежние, parent/fee/actions отсутствуют. После удаления trigger тот же key/body успешно применился. GET fee совпадает с опубликованным aggregate; kind/account history с cursor отделяет transfer от expense fee. Схема и fault trigger удалены после теста. 140 synthetic HTTP responses по 5 schemas прошли FormatChecker/JSON Schema validation неизмененного OpenAPI.

Новый advanced-check выполняется под UID/GID запускающего пользователя с временным GOCACHE, чтобы записать ops/.runtime/checks/ledger-advanced-responses.json с 0600 и сохранить возможность локальной проверки без расширения доступа. Файл принадлежит igolubev/docker; mode 0600 и отсутствие auth response schemas проверены. Response checker принимает optional path, default для старого ledger-check сохранен; credentials/password/cookie/CSRF в артефакт не попадают.

Регрессии: make ledger-check — PASS, 179 TLS HTTP responses/11 schemas; make backend-integration (vertical reopen/receipt) — PASS; make e2e (реальный Chrome, lost-response → Docker API/web restart → same-key retry) — PASS, 1 сценарий, исходный 98766 и один расход сохранены. make compose-check — PASS. Это повторная приемка базового пути, не browser transfer form: новые веб-формы относятся к S3-06B.

Фактические команды:

```bash
GOCACHE=/tmp/accounting-go-build GOMODCACHE=/tmp/accounting-go-mod make backend-check
sg docker -c 'make advanced-ledger-check'
sg docker -c 'make ledger-check'
sg docker -c 'make backend-integration'
PATH=/tmp/node-v24.21.0-linux-x64/bin:$PATH DOCKER_BUILD_NETWORK=host sg docker -c 'make e2e'
sg docker -c 'make compose-check'
DOCKER_BUILD_NETWORK=host sg docker -c 'make dev'
```

Local Docker API/worker обновлены; verified HTTPS localhost:8443: страница/ready 200, currencies без session 401 — PASS. Local регистрация остается false. Test остановлен без удаления volume; advanced synthetic artifact сохранен.

S3-02C остается in_progress. Создание transfer/fee принято; PUT transfer/fee, per-part refund CRUD/limits, adjustment, delete/bulk и dependency checks еще not_run/not implemented. DR-F04/F06/F08/F09 не объявляются закрытыми; cross-currency basic edit DR-F07 остается прежним ledger regression. Следующий шаг — возвраты по исходным частям и reservations, затем правка/удаление переводов и комиссий с зависимостями. После завершения пакета — S3-03B/S3-06B/S3-07B.


## S3-02C: возвраты, reservations и удаление зависимых операций (2026-10-05)

Найдены уже подготовленные refund.go/refund_test.go, POST refund adapter, защита правки возвращенного расхода и расширение TestLedgerAdvanced, не отраженные в прежнем отчете. Эта реализация проверена и продолжена. Теперь POST/PUT refund выполняются через CommandRunner: posted исходный expense, immutable parent/currency, положительные суммы с точным равенством частей, ссылки только на исходные allocations, наследование категории (включая архивную), даты расхода/открытия/DB clock, UUID и ожидаемые версии. Активные pending и posted возвраты резервируют лимит каждой части. PUT исключает собственную прежнюю reservation, сохраняет UUID retained parts и не разрешает переназначить их оригиналы или подменить UUID части для того же original. Финансовая правка публикует расход и, для комиссии, transfer; обычная правка note/payee/tags меняет только refund. Перераспределение частей меняет версии родителя без account bump, если движение неизменно. Смена счета обновляет оба счета один раз и сохраняет валюту.

Добавлен edit_delete.go и DELETE adapter. JSON body требует expected_version и полный уникальный related_versions для затронутых fee/parents. Accounts блокируются в UUID порядке перед transactions под head mutex. Soft delete сохраняет дочерние данные для истории, публикует versioned tombstones, пересчитывает остатки и обновляет parents. Удаление refund освобождает лимит; pending deletion меняет pending_delta, не posted. Transfer+fee удаляются атомарно; отдельное удаление fee публикует обновленный transfer. Активный refund запрещает удаление expense/fee и transfer с такой fee. Opening не удаляется. Новый action для уже удаленной записи дает conflict; исходный key возвращает исходную receipt. Архив, межпространственные ссылки, version overflow и выход остатка за лимит откатывают команду.

make backend-check — PASS (gofmt/vet/unit/build); PostgreSQL integration в этом target без TEST_DATABASE_URL skipped. Advanced acceptance текущих исходников на настоящей accounting_test/TLS — PASS: 446 HTTP responses / 5 OpenAPI schemas. Проверены создание/правка/удаление refund, DR-F04/F06/F09, pending reservations, исходные архивные категории, concurrent create/PUT, exact per-part limits, parent и fee/transfer версии, identity-preserving parts, same-key replay после последующих изменений/удаления, archived/foreign/date/currency/overflow rejections. DB faults во время create/PUT/delete полностью откатывают записи, остатки и версии; повтор того же key после устранения fault успешен. Удаление положительного offset, ведущее к balance ниже −9e15, отклоняется без tombstone. Cascade transfer+fee на одном source увеличивает balance_version один раз.

Регрессии: make ledger-check — PASS, 179 HTTP responses / 11 schemas; make backend-integration — PASS (session/receipt после нового API/pool); make check-contracts и make compose-check — PASS. DR-F08 остается not_run: PUT transfer/fee еще не реализован. Матрица design-review fixtures сохраняет исходные not_run; подтвержденное service coverage фиксируется здесь, без объявления всей матрицы завершенной.

Обычный Docker build встретил DNS отказ registry-1.docker.io. Промежуточный make advanced-ledger-check прошел (442 ответа); финальная версия с усиленным DR-F06 проверена тем же сервисом с current backend mounted read-only и проверкой 446 ответов отдельным response checker. Точные команды — в S3-02C.md. Artifact ledger-advanced-responses.json сохранен с 0600 под текущим UID; auth schemas не записываются. SQL migration/OpenAPI не менялись. HTTP route ownership расширена координатором для подключения команд.

S3-02C остается in_progress. Следующий шаг — PUT transfer/fee с immutable currencies/UUID и защитой возвращенной комиссии, затем adjustment/bulk. Новые браузерные формы остаются S3-06B.


Финальная проверка также покрывает попытку PUT refund к обычному expense: 422 validation_error без nullable-parent Scan error. Browser basic E2E на финальных API binaries — PASS: Chrome/Playwright, 1 сценарий (cookie session, lost response → API/web restart → same-key retry, ровно одна expense и исходный остаток 98766). Новые refund/transfer формы этим тестом не покрываются.

Из-за отказа Docker Hub локальные runtime binaries собраны с текущими исходниками из имеющегося Go build image и прежнего runtime image (cached multistage build, без изменения backend/Dockerfile). Обновлены local API/worker; HTTPS page/ready 200, currencies без session 401. Для browser regression использованы эти же финальные binaries и существующий web image, test-only registration из compose.e2e.yaml; npm dependencies уже установлены. Эквивалент scripts/e2e.sh выполнен без повторного download/build: compose up --no-build --wait, HTTPS readiness, npm exec playwright test, compose down. Временный runner: /tmp/accounting-refund-e2e.sh; Dockerfile: /tmp/accounting-refund-runtime.Dockerfile. Test остановлен без удаления volume; local регистрация не включалась. Artifact ledger-advanced-responses.json остается 0600, текущий UID. Блокирующих действий нет.


## S3-02C: PUT переводов и комиссий, DR-F08 (2026-10-05)

Добавлены strict TransferReplace decoder и transfer_edit.go. Существующий transfer меняется одной командой с immutable source/target currencies, exact reduced FX ratio, optional equivalent client rate и сохранением entry UUIDs. Счета заменяются только внутри валюты своей стороны; same-currency source/target могут поменяться местами без unique collision и смены UUID debit/credit. Accounts блокируются до ordered transactions под CommandRunner head; expected_version и обязательный nullable expected_fee_version проверяются до записи. UUID активной fee неизменен; fee=null публикует versioned tombstone после guards; добавление после удаления требует нового UUID. Финальная проверка balances выполняется после root и fee together, с одним bump каждого account с измененными движениями, даже при net-zero на границе 9e15. Изменение только metadata/классификации account version/balance_version не меняет.

Расширен ReplaceBasic для самостоятельного expense PUT комиссии: обязательный expected_parent_version, проверка kind/date родительского transfer, ordered transaction locks, parent version bump и aggregate publication вместе с fee. Общие edit_parts.go helpers используются обоими expense paths: per-UUID historical category/tag сохранение, refund guards, защита removal частей с historical refund references и in-place обновление retained allocation UUIDs. Soft-delete helper вынесен для общей обработки DELETE и fee=null без изменения контракта. Общие OpenAPI/SQL миграции не менялись.

DR-F08 подтвержден настоящим parent PUT: смена даты перевода с active fee refund отклоняется 409 dependent_transactions; original transfer/fee/receipts/balances не меняются. Также проверены amount/account/parts/remove guards, сохранение archived categories/tags, разрешенные metadata и перестановка неизменных частей. Parent и fee PUT с одинаковыми observed versions конкурируют: один 200, другой 409 version_conflict, обе версии увеличиваются ровно один раз. Legacy POST receipt и PUT replay после последующих изменений остаются неизменными. Cross-workspace resources/commands дают 404. DB exception при UPDATE fee allocations после записи root/fee возвращает 503 и откатывает все aggregates/accounts/versions; action receipt отсутствует. Same-key после снятия trigger проходит.

Проверки текущих исходников: make backend-check — PASS (format/vet/unit/build; PostgreSQL без TEST_DATABASE_URL skipped только в этом target). Реальные PostgreSQL/TLS tests из cached Go image с backend mounted read-only: TestLedgerAdvanced — PASS, **860 responses / 5 OpenAPI schemas**; TestLedgerBasic — PASS, **179 responses / 11 schemas**; TestVertical — PASS (session/receipt и баланс после нового API/pool). make check-contracts compose-check — PASS. Exact FX 1/3→2/6 accepted/reduced, rounded client rate rejected, currency/fee UUID replacement rejected; old/new account moves и shared-account version bumps, source/target swap, fee lifecycle и net-zero boundary также PASS. Подробные команды — S3-02C.md. Advanced artifact mode 0600, текущий UID; credentials не записываются.

Local runtime binaries собраны из актуальных исходников через cached Go/runtime images, без изменения backend/Dockerfile; local API/worker обновлены. Обнаруженная пользовательская local registration=true сохранена; .env и профили пользователя не менялись. Новые transfer/refund browser forms относятся к S3-06B. Design-review fixture statuses остаются исходными not_run, а service acceptance DR-F08 фиксируется здесь. S3-02C остается in_progress: дальше adjustment и bulk classification.


Финальный Chrome/Playwright basic E2E на новых binaries — PASS, 1 сценарий: реальная cookie session, расход 1234 после opening 100000, намеренная потеря ответа → restart API/web → reload/same-key retry, ровно одна expense и итог 98766. Использован эквивалент scripts/e2e.sh через /tmp/accounting-refund-e2e.sh с cached test images и установленными npm dependencies; backend build — docker build --pull=false -f /tmp/accounting-refund-runtime.Dockerfile -t accounting-transfer-runtime:local backend. После теста accounting-test остановлен без удаления volume. Local page/ready 200, currencies без credential 401; registration=true сохранена. Новые формы transfer/fee этим browser тестом не покрываются и остаются S3-06B.

## Финансовый пакет S3-02C завершен (2026-10-05)

Корректировки и bulk classification приняты на реальном PostgreSQL/TLS; результаты и команды зафиксированы в [S3-02C](S3-02C.md). DR-F12 подтвержден, bulk 100-item success и oversized journal rollback проверены. Контракты и миграции сохранены. S3-02 закрыта по проверенным дочерним результатам, весь этап остается in_progress.

Local API/worker обновлены текущими binaries из cached runtime images. HTTPS page/ready — 200/ready, currencies без session — 401; пользовательская настройка REGISTRATION_ENABLED=true сохранена. Настоящий Chrome/Playwright basic E2E на новых binaries — PASS (1 сценарий: lost response → API/web restart → same-key replay, одна expense и остаток 98766). Выполнен cached-image вариант scripts/e2e.sh через /tmp/accounting-refund-e2e.sh; test Compose остановлен без удаления volume. Новые adjustment/bulk формы браузером не проверялись: они еще относятся к S3-06B.

## Pull S3-04B (2026-10-06)

HMAC cursor/key rotation, HTTP adapter и REPEATABLE READ чтение целых групп реализованы и проверены: [S3-04B](S3-04B.md). Byte boundary, access, retention cutoff, generation reset и concurrent commit приняты на PostgreSQL. Начальный cursor выдаст S3-04C; Android client atomic apply/Room/outbox не объявляются принятыми.

Local API/worker собраны из финальных исходников через cached runtime images. Локальный ключ создан в .env без вывода значения; прежние пользовательские параметры сохранены (REGISTRATION_ENABLED=true). HTTPS page/ready — 200/ready, pull без session — 401. Регрессия Chrome/Playwright — PASS, 1 сценарий lost response → API/web restart → same-key replay; одна expense и ожидаемый остаток. Cached запуск scripts/e2e.sh использует существующие npm dependencies и compose up --no-build; test остановлен без удаления volume. Нового sync UI этот сценарий не проверяет.

После сохранения прежних lock_timeout=3s/statement_timeout=10s в новом beginIsolation повторены финальные проверки: backend-check и TestPull — PASS; общая PostgreSQL регрессия TestCommand/TestLedgerAdvanced/TestLedgerBasic/TestVertical — PASS. Local active signing key проверен как настроенный без вывода значения.

## Snapshot/bootstrap и retention S3-04C (2026-10-06)

Серверная materialization, actor quota/replay, immutable pages/TTL, подписанные page tokens и worker cleanup завершены: [S3-04C](S3-04C.md). 54 HTTP ответа / 5 схем OpenAPI приняты на PostgreSQL/TLS. Добавочная миграция 0004 сохраняет key ID, предыдущие SQL неизменны; локальный upgrade применен и повторен без изменений. Пакет S3-04 закрыт по результатам всех дочерних задач, этап 1 остается in_progress. DR-S04 подтвержден на сервере; Android Room/staging/outbox и DR-S10 restore drill не выполнялись.

Финальная Chrome/Playwright basic регрессия на новых API binaries и migration 0004 — PASS (1 сценарий, lost response → API/web restart → same-key replay, одна expense и ожидаемый остаток). Cached-image вариант scripts/e2e.sh использует имеющиеся npm dependencies; test Compose остановлен без удаления volume. Snapshot UI/Android atomic apply этим browser сценарием не проверяются. Local REGISTRATION_ENABLED=true сохранен.

## S3-03B: ресурсный доступ и отзыв (2026-10-06)

[Пакет негативной приемки](S3-03B.md) завершен: 203 HTTP ответа resource tests и 28 ответов deterministic session/membership races валидированы по OpenAPI. Все 35 операций/33 private guards покрыты; pg_blocking_pids подтверждает фактический порядок locks. Go race detector и повтор TestAuth — PASS. Runtime код и миграции не менялись; браузер не перезапускался ради серверных tests. Пакет S3-03 закрыт по проверенным дочерним результатам; этап остается in_progress. Следующая готовая задача — S3-06B, включая DR-A10 UI rendering.

## S3-06B: категории, теги и части операций (2026-10-06)

Добавлен первый блок расширенного интерфейса: дерево категорий, создание/редактирование/архив/восстановление категорий и тегов; выбор категории каждой из 1–100 частей дохода/расхода и тегов операции. Проверка minor units через BigInt не допускает округления, нулевых частей и несовпадения суммы. История отображает распределение и архивные ссылки. Полная загрузка справочников использует next_cursor, не обрезает их первой страницей. Незавершенные POST/PUT сохраняют исходный метод/тело/ключ после reload; понятное сообщение восстановления не показывает generation.

`make web-check` — PASS; Chrome с production bundle, настоящим TLS API и PostgreSQL — 2/2 E2E PASS: новый advanced с реальным потерянным PUT-ответом и basic с API/web restart. Выполнен cached runner `/tmp/accounting-pull-e2e.sh`; исходная реализация API не подменялась. Проверены расход 10,01 с частями 4,00/6,01 и остаток 89,99, отклонение несовпадения до команды, архив без изменения истории/остатка, восстановление категории и script-like имя тега как текст. Исправлен найденный выбор первого счета после создания. Local Vite получает изменения из source mounts; регистрация сохранена включенной.

S3-06B остается in_progress: переводы/комиссии/возвраты/корректировки, редакторы существующих операций, архив счетов, устройства, фильтры/страницы истории, bulk и conflict diff еще не реализованы. Полная DR-F/A/S матрица, Android mirror/outbox и выпуск не приняты.

## S3-06B: создание переводов (2026-10-06)

Добавлена форма переводов между разными счетами, точные суммы и серверный расчет курса; расширенные типы в истории названы по-русски. `make web-check` — PASS; Chrome E2E на настоящем TLS API/PostgreSQL — 3/3 PASS через cached runner `/tmp/accounting-pull-e2e.sh` с актуальным production bundle. Перевод EUR 10,01 дает остатки 89,99/110,01 и сохраняется после reload как один transfer, без expense/income. PUT replay тест теперь ожидает завершения ответа, устраняя гонку проверки. Комиссия/валютные счета — следующий шаг; весь S3-06B остается in_progress.

## S3-06B: валютные счета и комиссия перевода (2026-10-06)

Создание счетов поддерживает все шесть валют схемы и bank/cash/card. Перевод может включать комиссию с отдельного счета и категорией, сохраняемую атомарно с переводом; история показывает комиссию и названия счетов движений. `make web-check` и npm build — PASS. Финальный Chrome E2E production bundle на настоящем TLS API/PostgreSQL — **4/4 PASS** через `/tmp/accounting-pull-e2e.sh`: новый FX 3 EUR → 1 USD (точный rate 1/3), fee 0,123 KWD, запрет нулевой fee, потеря реального ответа → reload → исходный key/body replay, одна пара transfer/fee и точные остатки 97,00/101,00/99,877. Basic restart и прежние category/split/PUT replay регрессии также PASS. Test Compose остановлен без удаления volume; local Vite использует текущие source mounts, регистрация не менялась. Категория fee отдельно браузером not_run, части/теги переводов и комиссии пока отсутствуют. Следующий шаг — редакторы существующих операций с версионными guards; S3-06B остается in_progress.

## S3-06B: редактор расходов и доходов (2026-10-06)

Добавлен versioned редактор самостоятельных expense/income с сохранением UUID частей, даты/таймзоны и исторической классификации; повтор неопределенного PUT поддерживается после reload. Новые операции используют активные счета, исторические ссылки видны через archived=include. Сборки PASS, финальный Chrome E2E на настоящих API/PostgreSQL — **5/5 PASS**: новая правка 10→12, UUID parts/entry/date сохранены, lost-response replay и реальная concurrent version rejection без потери введенного текста. Отдельная browser приемка income/date edit/fee/conflict diff еще не выполнена. Следующий шаг — переводы и связанные комиссии; S3-06B in_progress.

## S3-06B: редактирование переводов и комиссий (2026-10-06)

Transfer PUT использует observed root/fee versions, сохраняет currencies/entry UUIDs и existing fee UUID/parts. Поддержаны добавление/изменение/удаление fee вместе с переводом и отдельный fee PUT с parent version. Fee поддерживает 1–100 точных частей. `make web-check`/npm build — PASS; Chrome на настоящем TLS API/PostgreSQL — **5/5 PASS**, включая transfer+fee финансовую правку, части комиссии, standalone fee parent bump, удаление fee и income edit. Остальные регрессии PASS. Далее — редактор и архив счетов; S3-06B остается in_progress.

## S3-06B: редактор и архив счетов (2026-10-06)

Добавлены versioned rename/type/archive/restore. Архив сохраняет видимый остаток и историю, новые операции используют только активные счета. Сборки PASS; Chrome на настоящем API/PostgreSQL — **6/6 PASS**, включая сохранение balance_version при архивировании и новую запись после восстановления. Следующий шаг — устройства и отзыв; пакет in_progress.

## S3-06B: устройства и отзыв сессий (2026-10-06)

Добавлен полный список активных устройств с отзывом, повтором того же session ID при потере ответа и завершением текущей сессии. Сборки PASS; **7/7 Chrome E2E PASS** на настоящем API/PostgreSQL. Отзыв второй cookie подтвержден ее 401, собственная cookie сохраняется до отдельного отзыва; HTML в device name отображается текстом. Pending финансовая команда блокирует кнопки отзыва. Далее — история/filters/pagination, пакет in_progress.

## S3-06B: полная история, фильтры и страницы (2026-10-06)

История вынесена в компонент с серверными фильтрами и next_cursor; даты включительны по браузерному timezone, старые запросы отменяются при сбросе. `make web-check`/npm build — PASS; **8/8 Chrome E2E PASS**. Реальные 12 расходов читаются страницами 10+2 без дублей; account/category/tag/type/text/date дают 6, pending 0, reset возвращает все 13 с opening. Следующий шаг — сравнение при конфликте, затем остальные финансовые команды и bulk. Пакет in_progress.

## S3-06B: сравнение при конфликте операции (2026-10-06)

Version conflict показывает отправленную правку и актуальное серверное состояние рядом, сохраняя форму; серверная версия загружается в редактор только по кнопке, финансового auto-resubmit нет. Сборки PASS; **8/8 Chrome E2E PASS**, включая реальную concurrent expense правку и явную замену редактора. Transfer diff пока без отдельной browser конфликтной приемки; остальные resource/bulk diff остаются открыты. Далее — возвраты и корректировки, пакет in_progress.

## S3-06B: создание и редактор возвратов (2026-10-06)

Возвраты создаются из расхода по исходным частям и доступным reservations; наследуют категории, используют immutable UUID/original links и parent/transfer guards. Сборки PASS; **9/9 Chrome E2E PASS** на настоящем API/PostgreSQL. Refund 5 к expense 10 с архивной category принят после exact replay потерянного POST; edit до 3 сохраняет IDs, balance 93/remaining 7. Отдельный browser fee-refund/concurrency сценарий остается not_run. Далее — корректировки и удаления, пакет in_progress.

## S3-06B: корректировки подтвержденного остатка (2026-10-06)

Корректировка создается с target balance/reason/expected balance version, после создания редактируется только note. Сборки PASS; **10/10 Chrome E2E PASS**. Конкурентный доход защищен от stale adjustment, exact replay после reload дает один delta −21, note edit сохраняет финансовые поля. Далее — удаление и bulk, пакет in_progress.

## S3-06B: удаление операций и зависимостей (2026-10-06)

DELETE использует актуальные root/related versions, показывает движения и каскадную fee перед явным подтверждением; pending DELETE переживает reload с исходным key/body. Сборки PASS; **10/10 Chrome E2E PASS**. Active refund блокирует expense deletion; удаление refund повторяется безопасно, освобождает лимит и позволяет удалить expense. Transfer+fee deletion возвращает исходные остатки трех счетов и 404 обоих aggregates. Далее — atomic bulk и полнота metadata editor, пакет in_progress.
