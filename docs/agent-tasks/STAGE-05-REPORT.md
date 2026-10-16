# Этап 5: приемка базового кандидата

Дата:2026-10-16. Статус: in_progress — технические задачи реализованы, область пересмотрена: личное использование без публикации. Предыдущая приемка сохранена в [отчете2026-10-15](STAGE-05-REPORT-2026-10-15.md).

## Проверенная версия

База Git e81fadd8b2b735fb79640ed9edc245d05b0e9ed4 плюс сохраненная незакоммиченная рабочая копия; исходные изменения пользователя не сбрасывались, stash/commit не выполнялись. Source SHA256 последней кодовой приемки (до корректировки документации личного использования) `d7f77c43062192a5325825d646bbf4b785c61dda813f4b23fde9242006a91e64`,413файлов: backend/web/Android/contracts/ops/scripts/tests, Compose, Makefile, .gitignore/.env.example. Документы отчета не входят в digest. Restricted manifest: ops/.runtime/checks/implementation-source.json.

Linux/Docker/PostgreSQL18/TLS, Go1.26, Node24/Chrome, JDK17/Android SDK36. Данные синтетические. Android device/emulator execution не запускалось по прямому поручению пользователя и не объявляется пройденным.

## Результат продолжения

Закрыт прежний незавершенный кодовый пакет PUBLIC-REGISTRATION. Реализованы:

- Email verification регистрации и восстановление пароля в API/вебе/Android, миграция0007, OpenAPI0.4.0. До подтверждения mailbox одинаковый202 для существующих/новых адресов, без user/session. Регистрация требует код и исходный пароль; это исключает подтверждение заранее созданного посторонним запроса с известным ему паролем.
- Код256бит/30минут/одноразовый, digest в challenge; durable SMTP очередь AES256-GCM, verified TLS1.2+/STARTTLS без plaintext fallback. Ограничение попыток и отправки, persistent cooldown60секунд, максимум10000живых challenges, до5delivery retries с исходным кодом. Успешное тело удаляется, expiry cleanup работает и после выключения verification. Password recovery атомарно меняет хеш и отзывает все устройства. Коды/пароли/письма не выводятся в журналы.
- Production конфигурация разрешает регистрацию только с явным verification и защищенной SMTP/key конфигурацией; постоянные cursor signing keys обязательны, чтобы production не запускался с неработающим snapshot/pull. Legacy local/test регистрация сохранена для существующих сценариев.
- Удаление профиля стирает связанные email proofs; recovery restored DB очищает все восстановленные proofs и mail, помимо смены поколений/отзыва сессий. Мониторинг email backlog/failures/возраста добавлен к CSV metrics.
- Подпись Android APK/AAB через четыре защищенные переменные и ops/android-release.sh; частичная конфигурация отклоняется, ключи не входят в Git. Процедура реально проверена временным тестовым ключом; после проверки он удален, signed test APK удален, unsigned APK/AAB восстановлены. Это не подпись владельца и не публикация.

Бюджеты/отчеты с pending, правила, CSV durable import/export, пользовательские данные и предыдущие финансовые/offline изменения сохранены; API финансового sync v1 не меняет четыре типа сущностей. Детали: STAGE-03/04-REPORT.md, ADR008/009, PUBLIC-REGISTRATION.md.

## Приемка

| Gate | Результат |
|---|---|
| Полный `go test ./... -count=1` с реальной PostgreSQL/TLS | PASS; integration58.996s; отдельный browser host исполняется специальным runner |
| `go vet ./...` | PASS |
| Email PostgreSQL tests | PASS: expiry/reuse/purpose, same responses, original password, concurrent proof use, same-code SMTP retry, reset/revoke, delete cleanup, storage503→same-code retry |
| Реальный локальный TLS SMTP / STARTTLS downgrade | PASS; encrypted-message tamper/AAD/key-length tests PASS |
| `make check-contracts compose-check` | PASS:55core/0draft operations,27valid/18invalid examples,5events; Compose isolation/config |
| Android DTO generator `--check` | PASS |
| Access HTTP coverage/body validation |55operations/50private guards;261HTTP bodies/12schemas PASS |
| Email captured response bodies |17responses/4schemas PASS |
| Reports/planning/CSV/user-data bodies |27/3,21/7,29/6,14/7 PASS |
| Основной `make e2e` Chrome |21/21 PASS,1.9min; реальный API/PostgreSQL/TLS |
| `make email-auth-e2e` Chrome |1/1 PASS,10.3s; registration→recovery→other-browser401→new-password login |
| Web production build/types, E2E typecheck | PASS |
| Android debug/release/test APK, bundleRelease,53unit,lintDebug/lintRelease | PASS,8m21s,140tasks;0unit failures/errors |
| APK/AAB signing self-test | PASS: настоящий disposable key, apksigner/jarsigner, system-only release trust; ключ удален, unsigned artifacts проверены повторно |
| Actual backup→isolated restore→recovery | PASS:26таблиц; сохранены финансы/версии/квитанции после rotation; clone-only email canary действительно удален |
| Aggregated monitor | email/CSV queue/failures/age и expired_snapshots=0 на момент проверки |
| `git diff --check`, ops Bash syntax | PASS |

Новые сценарии email browser используют только compiled Go test host и capture sender; endpoint чтения synthetic кода не входит в production API. Это реальный клиент/API/PostgreSQL, но не доказательство доставки внешним SMTP провайдером. Transport проверяется отдельно локальным TLS SMTP сервером. Emulators/instrumentation не выполнялись.

Ранее принятый независимый integer oracle отчетов также PASS; pilot10000synthetic transactions321.579604ms относится к предыдущей локальной приемке и не является production SLA.

## Воспроизведение и артефакты

Сервер: `docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml --profile checks run --rm -v "$PWD/backend:/src" -v "$PWD/contracts:/contracts:ro" reports-check sh -c 'go test ./... -count=1 && go vet ./...'`. Для access captured artifact передать `-e ACCESS_RESPONSE_ARTIFACT=/output/access-responses.json`; новые email captured bodies входят в reports-check. DB-owning gates запускать последовательно с make e2e, который останавливает test stack.

Android из android/: `ANDROID_HOME=/opt/accounting-sdk ./gradlew --no-daemon -Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' --max-workers=1 assembleDebug assembleRelease bundleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug lintRelease`. Подпись тестовым ключом: `ANDROID_HOME=/opt/accounting-sdk scripts/check-android-signing.sh`; ключ владельца не используется. Исходный keystore отсутствует после теста; JSON proof в ops/.runtime/checks/android-test-signing.json.

Backup: ops/.runtime/backups/test-20261016T060818Z.dump и SHA256. Actual restore26таблиц и recovery доказаны в ops/.runtime/checks/restore-result.json. После сравнения clone удален; исходная БД процедурой не изменялась. Проверка специально вставляет synthetic proof только в clone, затем доказывает его удаление. Backup содержит финансовые данные, не включается в Git.

Unsigned APK: android/app/build/outputs/apk/release/app-release-unsigned.apk, SHA256 `fcac27a3a0daa3b923a4ae4eb01ee9c137b4069a4f14962ad266c3e9624fd029`. Unsigned AAB: android/app/build/outputs/bundle/release/app-release.aab, SHA256 `cdd2c0bb8df40ca5d13f4f550e1aee49678711a32644e4fabe44db3cb91f1d89`. Debug APK также собран. Release trust: system CA only, cleartext disabled, debug CA отсутствует. Permissions: INTERNET/ACCESS_NETWORK_STATE, служебные WorkManager WAKE_LOCK/RECEIVE_BOOT_COMPLETED/FOREGROUND_SERVICE и внутреннее signature receiver permission. SMS/notification-listener разрешений нет.

Инструкции: [USER_GUIDE.md](../USER_GUIDE.md), [RELEASE_MATERIALS.md](../RELEASE_MATERIALS.md), [release runbook](../../ops/runbooks/release.md).

## Исторические зависимости публичного выпуска (пересмотрены ниже)

1. Реальный SMTP provider/credentials, DNS/SPF/DKIM/квоты и фактическая доставка. Код verification/recovery готов; deployment registration по умолчанию выключена до приемки конфигурации.
2. Production host/domain/HTTPS/DB credentials, защищенное внешнее backup хранилище, расписание/alerts/контакт дежурного и измеренный RPO. Локальный restore с rotation/session revoke доказан; production настройка и данные после момента backup не обещаны восстановленными.
3. Ключ Android владельца и аккаунт площадки, фактическая подпись/публикация, пилот и production smoke. Test signing этого не заменяет.
4. Проверки физических устройств, background/offline/cross-client APP-02/S3-05/APP-03 и других браузеров не перепроверены в этом цикле. User waiver исключает эмуляторы из текущей реализации, но не доказывает совместимость перед публичным выпуском.
5. Банковский этап по FINANCE_APP_PLAN.md начинается после фактического базового выпуска. Реальные банки/уведомления/SMS/API/сверка не реализованы и не заявляются; нельзя начинать этот этап до завершения его зависимости.

Прежний следующий пакет S6-06 пересмотрен решением о личном использовании; публикация исключена. PUBLIC-REGISTRATION больше не является незавершенным кодовым пакетом; внешняя delivery приемка не подменяется локальными тестами. Банковский этап пока не реализован; опубликованный выпуск больше не входит в критерии завершения.

После общего gate уточнен только текст выключенной возможности в клиентах (регистрация и recovery используют общий отказ). Web build и Android debug/release/bundle повторно собраны на последнем исходнике:3m37s. Повторный полный Go suite/vet включает инъекцию сбоя INSERT user:503 не потребляет proof, следующий запрос тем же кодом дает201. Финальный локальный стенд обновлен, readiness200; email delivery остается выключенной без реального провайдера.


## Дополнительная эксплуатационная приемка2026-10-16

OPS-AUTOMATION завершена по коду.17unit tests PASS; actual signed/encrypted PostgreSQL backup, key rotation, restore26таблиц, recovery guards, failure/retry, no-overwrite concurrency PASS. Ephemeral private keys удалены; API/worker восстановлены, local verified HTTPS smoke PASS. Доказательство: ops/.runtime/checks/signed-backup-result.json. Systemd templates проверены статически, не установлены. Production preflight корректно отказывает без действующей конфигурации; реальный deploy не выполнялся. Инструкция ops/runbooks/automation.md содержит настройки retention, offline age identity, online signature, trusted key rotation, timers, alerts и deployment failure recovery. Прежние application/Android gates относятся к неизмененному коду приложения; этот пакет меняет только эксплуатацию и ее тесты.


## Изменение области2026-10-16: личное использование

По прямому поручению владельца публичное распространение исключено: Google Play, другие магазины, публичный веб-релиз, пилот внешних пользователей и store account не требуются. Предыдущие требования публичного выпуска выше являются историческими и больше не блокируют реализацию. S6-06 переопределена как техническая подготовка личной установки и закрыта в этом объеме; actual device install не заявляется выполненным. Материалы личного запуска: docs/RELEASE_MATERIALS.md.

Банковский этап зависит от технической приемки базовой версии для личного использования, а не от ее публикации. Личные банки/страна/обезличенные образцы и настоящие device проверки остаются фактическими зависимостями соответствующих сценариев. Открытая регистрация и SMTP опциональны; постоянное защищенное хранение, backup, стабильная подпись устанавливаемого APK и ограничения незапущенных проверок сохраняются. Изменение плана не означает прохождения device приемки или реализации банков.
