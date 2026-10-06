# Android Capital

Основа APP-01: Kotlin/Compose, Room и WorkManager. В APP-02 реализованы HTTPS bearer login/register/restore/renew/logout и Keystore. Финансовые экраны еще реализуются, mirror/outbox относятся к S3-05. APK пока не является завершенным клиентом учета.

Требуется JDK17–25 (приемка выполнена на JDK25), Android SDK platform36/build-tools36.0.0/platform-tools, доступ к Google Maven и Maven Central. Укажите SDK через ANDROID_HOME либо игнорируемый local.properties. Gradle wrapper9.1.0 проверяет SHA-256 дистрибутива; AGP9.0.1 использует встроенный Kotlin2.2.10 и совместимый Compose compiler plugin; KSP2.3.10 поддерживает эту комбинацию ([таблица совместимости](https://kotlinlang.org/docs/ksp-overview.html)).

```bash
make android-check
# Запустите эмулятор API36 или подключите тестовое устройство с разрешенным adb.
make android-device-check
```

Device tests проверяют реальную Room DB после закрытия/открытия, инициализацию WorkManager и Compose экран после recreation. Они пока не подтверждают sync, background retry или финансовые команды. Backup выключен, cleartext traffic запрещен; секреты не сохраняются в настройках.

Версии и совместимость: [AGP9.0](https://developer.android.com/build/releases/agp-9-0-0-release-notes), [Room](https://developer.android.com/jetpack/androidx/releases/room), [WorkManager](https://developer.android.com/jetpack/androidx/releases/work). SDK/AVD, Gradle caches и local.properties не входят в Git.

Приемка APP-01: APK, 2/2 unit и 2/2 device tests на Android16/API36 — PASS. Lint без ошибок с 11 warnings о версиях, иконке и правилах device-transfer; они отражены в карточке APP-01.

## APP-02: хранение сессии

CredentialVault выполняет IO/Keystore операции вне main thread. AES256-GCM шифрует token и owner/workspace/generation/session IDs; HTTPS origin включен в authenticated data. AtomicFile находится в noBackupFilesDir, manifest и data-extraction rules исключают cloud backup/device transfer. Повреждение/потеря ключа возвращаются как Invalid без удаления исходного файла; другой origin не получает credential. Пароль не хранится, string representations скрывают значения для защиты от случайного вывода.

Основание: [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [правила Auto Backup](https://developer.android.com/identity/data/autobackup). Login/register/renew/logout и восстановление процесса приняты следующим шагом APP-02; список устройств/revoke принят следующим шагом APP-02.

Шаг CredentialVault принят: `make android-check android-device-check` — APK/unit/lint PASS, 4/4 device tests на API36. Фактический перенос устройства/backup drill пока не выполнялся.

## Приемка HTTPS auth

```bash
JAVA_HOME=<JDK> ANDROID_HOME=<SDK> DOCKER_BUILD_NETWORK=host make android-auth-e2e
```

Нужен отдельный запущенный test emulator (по умолчанию emulator-5556, можно задать ANDROID_TEST_SERIAL). Скрипт выбирает его явно, поднимает accounting-test, временно включает test registration, настраивает adb reverse8444 и выполняет 6 базовых + 5 реальных API/UI tests. Затем проверяет force-stop/start, реальную остановку API, persisted logout intent и SQL revoke/отсутствие лишних sessions. Останавливает test Compose без удаления volume; local окружение/.env не меняются. Артефакты 0600 в ops/.runtime/checks не содержат token/password.

Приемка: **11/11 instrumentation PASS без skips**, actual TLS/PostgreSQL, runtime restart/outage/logout PASS. Без api_origin пять network tests явно skip, их нельзя считать принятыми обычным standalone android-device-check. Release unsigned APK собирается для проверки ресурсов через aapt2, без публикации; local CA не входит в release. Настройка TLS: [официальная Network Security Configuration](https://developer.android.com/privacy-and-security/security-config).

Устройства: все страницы сессий, текущая метка, отзыв другого и текущего устройства. Encrypted pending target сохраняется до DELETE, блокирует другие действия и переживает process restart. Acceptance проверяет limit=1, чужие UUID/404, Compose other/self revoke, идемпотентный повтор, реальный API outage и cold restart с SQL подтверждением выбранного UUID. Финансовые экраны и mirror/outbox еще в работе.

Финансовый transport: DTOs Account/MutationResult и зависимости генерируются из shared OpenAPI; command requests передают парные UUID headers Idempotency-Key/X-Sync-Generation. Money использует BigInteger для 0/2/3 decimal scales и проверяет лимит 9e15 minor. Приняты 4/4 unit и real Android AccountApiTest: KWD opening, idempotent replay без второй записи/удвоения остатка, rename/type/archive/restore, версии и generation conflicts. UI счетов и durable financial commands — следующий шаг.

Durable commands: Room v2 с миграцией 1→2, сохранением исходных command ID/body/generation до отправки и confirmed receipt до удаления. Unique origin/owner/workspace допускает одну неразобранную команду; terminal rejection сохраняет черновик, 401/unknown оставляют pending. Real CommandApiTest проверяет replay незаписанного подтверждения после DB reopen, фактический остаток/историю, terminal conflict и сохранение pending при revoked bearer. UI счетов и полный S3-05 mirror/outbox/staging еще не приняты.
