# APP-02: Android online клиент ручного учета

Статус: in_progress
Исполнитель: координатор, последовательно
Зависимости: APP-01
Входы: contracts/openapi.json, docs/decisions/002-screen-specifications.md, docs/implementation/07_APPLICATIONS_PLAN.md
Разрешенные файлы: android/, scripts/ Android acceptance, собственная карточка, docs/agent-tasks/STAGE-02-REPORT.md.
Задача: HTTPS bearer регистрация/вход/renew/revoke, Keystore credential, accounts/opening/archive, полные ручные операции, классификация/фильтры и явные ошибки/конфликты по готовому серверному контракту.
Критерии приемки: реальные API/PostgreSQL; exact integer minor/versions/generation, никакого финансового auto-resubmit при конфликте; credential не в Room/логах/backup; Android запись видна в вебе.
Проверки: Gradle unit/build/lint и instrumentation на устройстве/эмуляторе с реальным TLS API/PostgreSQL; сквозная web сверка. Mock не подменяет приемку.
Результат: начато хранение bearer credential через Android Keystore/AES-GCM и noBackupFilesDir; проверки завершены для этого шага (4/4 device PASS). Сначала auth/Keystore/read bootstrap, затем самостоятельные проверяемые финансовые шаги; каждый завершенный шаг — отдельный commit/push.
Следующий шаг: S3-05 durable atomic outbox/mirror/staging; APP-03 общая приемка Android→server→web.

## Шаг 1: CredentialVault — 2026-10-06

AES256-GCM ключ создается в AndroidKeyStore, encrypted session сохраняется AtomicFile в noBackupFilesDir. HTTPS origin связан через AAD; owner/workspace/generation/session IDs шифруются вместе с token. Reader возвращает Empty/OtherOrigin/Invalid/Available; повреждение и потеря ключа не стирают исходный файл и не создают новый key при чтении. IO/crypto выполняются вне main thread, string representations скрывают credential. Clear проверяет удаление atomic files. Manifest исключает backup и задает cloud/device-transfer exclusions.

`JAVA_HOME=<JDK25> make android-check android-device-check` — PASS: APK, 2/2 unit, lint без ошибок (10 warnings), **4/4 instrumentation** на API36. Настоящий AndroidKeyStore проверен на round-trip после нового экземпляра vault, non-exportable key, randomized IV, отсутствии token/owner в файле, origin mismatch и forged origin, измененном GCM tag, missing key, clear и interrupted AtomicFile replacement. Тесты используют отдельные файлы/aliases и не очищают рабочую сессию приложения.

Пока проверена локальная защита credential, без real login/renew/revoke или process restart auth. Backup/device-transfer drill не выполнялся; XML exclusions приняты сборкой/lint, credential находится в исключаемом платформой noBackupFilesDir. Следующий шаг — real HTTPS bearer API, формы login/register и восстановление сессии с серверной проверкой. APP-02 остается in_progress.
