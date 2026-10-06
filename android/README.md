# Android Capital

Основа APP-01: Kotlin/Compose, Room и WorkManager. Пока доступно сохранение HTTPS origin сервера; авторизация и финансовые экраны относятся к APP-02, mirror/outbox — S3-05. APK не является завершенным клиентом учета.

Требуется JDK17–25 (приемка выполнена на JDK25), Android SDK platform36/build-tools36.0.0/platform-tools, доступ к Google Maven и Maven Central. Укажите SDK через ANDROID_HOME либо игнорируемый local.properties. Gradle wrapper9.1.0 проверяет SHA-256 дистрибутива; AGP9.0.1 использует встроенный Kotlin2.2.10 и совместимый Compose compiler plugin; KSP2.3.10 поддерживает эту комбинацию ([таблица совместимости](https://kotlinlang.org/docs/ksp-overview.html)).

```bash
make android-check
# Запустите эмулятор API36 или подключите тестовое устройство с разрешенным adb.
make android-device-check
```

Device tests проверяют реальную Room DB после закрытия/открытия, инициализацию WorkManager и Compose экран после recreation. Они пока не подтверждают sync, background retry или финансовые команды. Backup выключен, cleartext traffic запрещен; секреты не сохраняются в настройках.

Версии и совместимость: [AGP9.0](https://developer.android.com/build/releases/agp-9-0-0-release-notes), [Room](https://developer.android.com/jetpack/androidx/releases/room), [WorkManager](https://developer.android.com/jetpack/androidx/releases/work). SDK/AVD, Gradle caches и local.properties не входят в Git.

Приемка APP-01: APK, 2/2 unit и 2/2 device tests на Android16/API36 — PASS. Lint без ошибок с 11 warnings о версиях, иконке и правилах device-transfer; они отражены в карточке APP-01.
