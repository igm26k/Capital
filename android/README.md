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

Основание: [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [правила Auto Backup](https://developer.android.com/identity/data/autobackup). Login/register/renew/logout и восстановление процесса приняты следующим шагом APP-02; список устройств/revoke другой сессии остается дальнейшей задачей.

Шаг CredentialVault принят: `make android-check android-device-check` — APK/unit/lint PASS, 4/4 device tests на API36. Фактический перенос устройства/backup drill пока не выполнялся.

## Приемка HTTPS auth

```bash
JAVA_HOME=<JDK> ANDROID_HOME=<SDK> DOCKER_BUILD_NETWORK=host make android-auth-e2e
```

Нужен отдельный запущенный test emulator (по умолчанию emulator-5556, можно задать ANDROID_TEST_SERIAL). Скрипт выбирает его явно, поднимает accounting-test, временно включает test registration, настраивает adb reverse8444 и выполняет 4 базовых + 2 реальных API/UI tests. Затем проверяет force-stop/start, реальную остановку API, persisted logout intent и SQL revoke/отсутствие лишних sessions. Останавливает test Compose без удаления volume; local окружение/.env не меняются. Артефакты 0600 в ops/.runtime/checks не содержат token/password.

Приемка: **6/6 instrumentation PASS без skips**, actual TLS/PostgreSQL, runtime restart/outage/logout PASS. Без api_origin два network tests явно skip, их нельзя считать принятыми обычным standalone android-device-check. Release unsigned APK собирается для проверки ресурсов через aapt2, без публикации; local CA не входит в release. Настройка TLS: [официальная Network Security Configuration](https://developer.android.com/privacy-and-security/security-config).
