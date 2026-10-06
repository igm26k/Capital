# APP-02: Android online клиент ручного учета

Статус: in_progress
Исполнитель: координатор, последовательно
Зависимости: APP-01
Входы: contracts/openapi.json, docs/decisions/002-screen-specifications.md, docs/implementation/07_APPLICATIONS_PLAN.md
Разрешенные файлы: android/, scripts/ Android acceptance/contract generation, Makefile (Android gates), собственная карточка, docs/agent-tasks/STAGE-02-REPORT.md.
Задача: HTTPS bearer регистрация/вход/renew/revoke, Keystore credential, accounts/opening/archive, полные ручные операции, классификация/фильтры и явные ошибки/конфликты по готовому серверному контракту.
Критерии приемки: реальные API/PostgreSQL; exact integer minor/versions/generation, никакого финансового auto-resubmit при конфликте; credential не в Room/логах/backup; Android запись видна в вебе.
Проверки: Gradle unit/build/lint и instrumentation на устройстве/эмуляторе с реальным TLS API/PostgreSQL; сквозная web сверка. Mock не подменяет приемку.
Результат: начато хранение bearer credential через Android Keystore/AES-GCM и noBackupFilesDir; проверки завершены для этого шага (4/4 device PASS). Сначала auth/Keystore/read bootstrap, затем самостоятельные проверяемые финансовые шаги; каждый завершенный шаг — отдельный commit/push.
Следующий шаг: S3-05 durable atomic outbox/mirror/staging; APP-03 общая приемка Android→server→web.

## Шаг 1: CredentialVault — 2026-10-06

AES256-GCM ключ создается в AndroidKeyStore, encrypted session сохраняется AtomicFile в noBackupFilesDir. HTTPS origin связан через AAD; owner/workspace/generation/session IDs шифруются вместе с token. Reader возвращает Empty/OtherOrigin/Invalid/Available; повреждение и потеря ключа не стирают исходный файл и не создают новый key при чтении. IO/crypto выполняются вне main thread, string representations скрывают credential. Clear проверяет удаление atomic files. Manifest исключает backup и задает cloud/device-transfer exclusions.

`JAVA_HOME=<JDK25> make android-check android-device-check` — PASS: APK, 2/2 unit, lint без ошибок (10 warnings), **4/4 instrumentation** на API36. Настоящий AndroidKeyStore проверен на round-trip после нового экземпляра vault, non-exportable key, randomized IV, отсутствии token/owner в файле, origin mismatch и forged origin, измененном GCM tag, missing key, clear и interrupted AtomicFile replacement. Тесты используют отдельные файлы/aliases и не очищают рабочую сессию приложения.

Пока проверена локальная защита credential, без real login/renew/revoke или process restart auth. Backup/device-transfer drill не выполнялся; XML exclusions приняты сборкой/lint, credential находится в исключаемом платформой noBackupFilesDir. Следующий шаг — real HTTPS bearer API, формы login/register и восстановление сессии с серверной проверкой. APP-02 остается in_progress.

## Шаг 2: real bearer auth и восстановление процесса — 2026-10-06

В приложении доступны регистрация, вход, восстановление и продление сессии, выход и повтор неизвестного результата выхода. AuthViewModel сохраняет операцию при Activity recreation, защищает concurrent clicks, не нормализует пароль и не сохраняет его в Bundle/Room. CredentialVault сохраняет encrypted logout intent **до** POST; startup с таким intent блокирует вход/обычные действия и предлагает повтор. GET auth/session проверяет owner/session/token/workspace; generation обновляется из сервера без создания новой сессии. 401 завершает локальную сессию, сетевой сбой сохраняет credential для явного восстановления. Failure сохранения после успешного auth показывает отдельный retry-save.

Auth DTOs генерируются из OpenAPI; android-check проверяет drift. Const/enum checks и redacted string output генерируются вместе с ними. OkHttp4.12 не использует cookie jar, redirects или автоматические network retries; token отправляется только клиенту с тем же HTTPS origin. JSON diagnostics очищаются от тела ответа. Debug trust resource генерируется через AGP variant API только для localhost/127.0.0.1 и публичной local CA; release сохраняет system trust.

`JAVA_HOME=<JDK25> ANDROID_HOME=<SDK> DOCKER_BUILD_NETWORK=host make android-auth-e2e` — **PASS**: real Docker API/PostgreSQL/TLS, API36 emulator; APK/debug/release, 2/2 unit, lint без ошибок, **6/6 instrumentation без skips**. Доказаны registration/login (exact UTF-8/whitespace password), same-token renew/restore, foreign workspace 404, origin guard, текстовое SVG-like device name, Compose recreation. Затем ADB force-stop/start восстанавливает реальный bearer; остановка API вызывает pending logout, intent переживает еще один force-stop/start, повтор после старта API отзывает session. SQL подтверждает revoked_at и ровно 2 sessions (register + явный login), vault/atomic remnants удалены, следующий restart показывает вход.

Артефакты без token/password: ops/.runtime/checks/android-*-instrumentation.txt и android-auth-runtime.json (0600). Compiled release trust проверен aapt2 по resource table и XML (включая оптимизированное имя): system CA only, cleartext false, local CA отсутствует. Test project остановлен без удаления volume; local регистрация/.env сохранены.

Первый запуск выявил неверный return type JUnit coroutine test; после Unit correction реальные API tests прошли. Затем исправлена проверочная ADB команда очистки (shell quoting); финальный полный pipeline повторен успешно. Это проверка auth, а не финансовых операций/Room mirror/outbox. APP-02 остается in_progress; далее devices/revoke и accounts/операции/классификация/filters.

## Шаг 3: устройства и durable revoke — 2026-10-06

Добавлены список всех страниц активных сессий, текущая метка, имя устройства и последняя активность, отзыв другой/текущей сессии. SessionList генерируется из OpenAPI; reader проверяет UUID/current marker, объединяет страницы и отклоняет повторяющийся cursor. До DELETE vault атомарно шифрует точный target UUID. Незавершенный отзыв блокирует остальные действия и переживает холодный restart; повтор использует тот же UUID. После подтверждения другой сессии текущая проверяется сервером и список обновляется при сохраненном busy guard. Отзыв текущей завершает вход; 401 при отзыве другого устройства не выдается за подтвержденный отзыв.

Проверка: `make android-auth-e2e` (JDK25/API36, реальный TLS API/PostgreSQL) — сборки/unit/lint и **7/7 instrumentation без skips**. Native reader проверен с limit=1; чужое устройство отсутствует в списке, cross-user DELETE дает 404 в обе стороны. Compose отзывает другое устройство, его bearer получает 401, текущий продолжает работать; повтор DELETE идемпотентен, затем отзыв текущего возвращает вход и 401. Runtime drill останавливает API, сохраняет pending revoke, делает force-stop/start, повторяет отзыв после восстановления и проверяет revoked_at выбранного UUID в SQL. Далее аналогичный logout drill/cleanup; ровно 3 sessions соответствуют registration и двум явным login. Release system-only trust проверен повторно. Доказательства: android-devices-instrumentation.txt и android-auth-runtime.json (без credential, 0600).

APP-02 остается in_progress. Далее — счета/opening/archive, операции и классификация/фильтры; S3-05 и APP-03 еще не приняты.

## Шаг 4: контракт счетов и точные суммы — 2026-10-06

Добавлены генерируемые Account/Create/Update/List и полный MutationResult с зависимыми transaction/category/tag/tombstone DTOs. Все суммы/версии остаются строками. ApiClient передает парные Idempotency-Key/X-Sync-Generation, проверяет UUID и наличие bearer для команд; автоматического повтора нет. Money преобразует decimal/comma ввод через BigInteger, поддерживает EUR/USD/GBP/RUB (2), JPY (0), KWD (3), сохраняет знак и проверяет серверный предел 9e15 minor. Float/Double не используются.

`make android-auth-e2e` — PASS: build/debug/release/lint, **4/4 unit** и **8/8 real instrumentation без skips**. Новый AccountApiTest создает KWD счет с 123.456 (=123456 minor) и одной opening операцией; повтор той же команды возвращает структурно тот же MutationResult, история остается из одной записи и фактический остаток не удваивается. Rename/type/archive/restore сохраняют balance и balance_version, resource version меняется; устаревшая версия и чужое поколение дают 409. Existing auth/device runtime outage/restart/SQL/cleanup и release system-only trust проходят вместе с новым тестом.

При реализации тест исправлен по серверному контракту: mutation возвращает общий результат, а JSONB replay меняет порядок ключей, поэтому проверяется структурное равенство, фактическая история и остаток. Это приемка Android transport/DTO/money, **не приемка экрана счетов**. Финансовые формы пока отсутствуют; следующий шаг — durable command storage и UI счетов, затем остальные ручные операции. APP-02 in_progress.
