# Этап 2: приложения

Дата: 2026-10-06. Статус: in_progress.

Сервер/веб приняты в S3-07B. APP-01 завершена. В APP-02 принято локальное Keystore/AES-GCM хранение bearer session; реальные вход/renew/revoke и финансовые экраны еще реализуются. S3-05 не начат. Банковские источники остаются финальным этапом.

## APP-01 принята — 2026-10-06

Создан Android проект Kotlin/Compose с Room, WorkManager и Gradle wrapper9.1.0 (SHA-256). `make android-check android-device-check` с JDK25: APK, **2/2 unit**, lint без ошибок и **2/2 device tests PASS** на Android16/API36 с KVM. Реальная Room persistence и Compose recreation приняты; WorkManager только инициализирован, background sync еще не реализован. Схема DB v1 экспортирована, destructive migration отсутствует. Визуальная проверка показала и помогла исправить цвета system bars и keyboard/scroll behavior.

Ограничения: lint сохраняет 11 warnings о версиях/иконке/data-extraction; полные правила переноса данных нужны до сохранения credential в APP-02. Начальный SystemUI ANR эмулятора устранен до успешной device приемки. Следующий шаг — APP-02 auth/bearer/Keystore и online учет, затем S3-05 outbox/mirror/staging и APP-03 Android→server→web.

## APP-02: Keystore credential принят — 2026-10-06

CredentialVault сохраняет token и идентификаторы principal/session/workspace/generation через AES256-GCM/AtomicFile/noBackupFilesDir; HTTPS origin включен в AAD. Ключ AndroidKeyStore non-exportable, reader не генерирует новый ключ при потере и сохраняет damaged evidence. Crypto/IO вне main thread, string output редактирован. Manifest/extraction rules задают исключения backup/device-transfer.

`make android-check android-device-check` с JDK25 — PASS: APK, 2/2 unit, lint без ошибок и **4/4 device tests** на API36, включая genuine Keystore, ciphertext/tag/origin tampering, absent key и interrupted atomic replacement. Lint теперь 10 warnings (версии/иконка), предупреждение extraction rules устранено. Runtime backup/physical device transfer и реальная серверная auth с восстановлением процесса пока не проверены; следующий шаг — bearer HTTP и auth UI. APP-02 остается in_progress.
