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
