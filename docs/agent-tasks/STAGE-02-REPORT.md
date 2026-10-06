# Этап 2: приложения

Дата: 2026-10-06. Статус: in_progress.

Сервер/веб приняты в S3-07B. APP-01 завершена. В APP-02 приняты Keystore/AES-GCM, реальные login/register/restore/renew/logout, process restart и durable logout intent (6/6 device tests). Devices/финансовые экраны еще реализуются. S3-05 не начат. Банковские источники остаются финальным этапом.

## APP-01 принята — 2026-10-06

Создан Android проект Kotlin/Compose с Room, WorkManager и Gradle wrapper9.1.0 (SHA-256). `make android-check android-device-check` с JDK25: APK, **2/2 unit**, lint без ошибок и **2/2 device tests PASS** на Android16/API36 с KVM. Реальная Room persistence и Compose recreation приняты; WorkManager только инициализирован, background sync еще не реализован. Схема DB v1 экспортирована, destructive migration отсутствует. Визуальная проверка показала и помогла исправить цвета system bars и keyboard/scroll behavior.

Ограничения: lint сохраняет 11 warnings о версиях/иконке/data-extraction; полные правила переноса данных нужны до сохранения credential в APP-02. Начальный SystemUI ANR эмулятора устранен до успешной device приемки. Следующий шаг — APP-02 auth/bearer/Keystore и online учет, затем S3-05 outbox/mirror/staging и APP-03 Android→server→web.

## APP-02: Keystore credential принят — 2026-10-06

CredentialVault сохраняет token и идентификаторы principal/session/workspace/generation через AES256-GCM/AtomicFile/noBackupFilesDir; HTTPS origin включен в AAD. Ключ AndroidKeyStore non-exportable, reader не генерирует новый ключ при потере и сохраняет damaged evidence. Crypto/IO вне main thread, string output редактирован. Manifest/extraction rules задают исключения backup/device-transfer.

`make android-check android-device-check` с JDK25 — PASS: APK, 2/2 unit, lint без ошибок и **4/4 device tests** на API36, включая genuine Keystore, ciphertext/tag/origin tampering, absent key и interrupted atomic replacement. Lint теперь 10 warnings (версии/иконка), предупреждение extraction rules устранено. Runtime backup/physical device transfer и реальная серверная auth с восстановлением процесса пока не проверены; следующий шаг — bearer HTTP и auth UI. APP-02 остается in_progress.

## APP-02: real HTTPS auth принята — 2026-10-06

Реализованы формы регистрации/входа, серверная проверка сохраненной сессии, renew и logout с durable encrypted intent. Пароль передается буквально и очищается из UI; DTOs генерируются и проверяются по OpenAPI. Network client блокирует token другого origin, cookie/redirect/auto-retry отключены, ошибки JSON не выводят bearer body. Debug localhost CA генерируется из публичного сертификата только в debug variant, release XML остается system-only.

Штатный `make android-auth-e2e` (JDK25/SDK/API36, DOCKER_BUILD_NETWORK=host) — PASS: сборки/debug/release, 2/2 unit, lint, **6/6 real instrumentation**, actual API/PostgreSQL/TLS. Force-stop/start восстанавливает тот же server session; отключение API оставляет durable logout intent, он переживает process restart, после явного повтора сервер отзывает session и app удаляет credential. SQL: revoked_at и ровно 2 sessions (registration + отдельный UI login), без создания sessions при renew/restore. Compiled release resource table/XML подтверждает system CA only и отсутствие debug trust. Ограниченные runtime artifacts не содержат token/password.

APP-02 остается in_progress: devices/revoke и финансовый Android UI еще не приняты; S3-05 mirror/outbox/staging и APP-03 полная Android→server→web финансовая приемка остаются следующими пакетами.

## APP-02: устройства и отзыв приняты — 2026-10-06

Android показывает все страницы активных устройств и текущую сессию. Отзыв сохраняет encrypted target UUID до DELETE, переживает offline/process restart и требует явного повтора. Реальный `make android-auth-e2e`: **7/7 instrumentation**, unit/build/lint и release trust PASS. limit=1 проверяет pagination; cross-user revoke дает 404, другой bearer после отзыва получает 401 при сохраненной текущей сессии, повтор DELETE идемпотентен; self-revoke возвращает вход. Runtime API outage/cold restart/retry подтвержден SQL для выбранного другого UUID, затем logout drill отзывает текущую сессию и удаляет vault. Артефакты без token/password — ops/.runtime/checks/android-devices-instrumentation.txt и android-auth-runtime.json.

APP-02 in_progress: финансовые экраны еще не приняты; далее счета, операции, классификация/фильтры, затем S3-05 и APP-03.
