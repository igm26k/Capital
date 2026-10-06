# Этап 2: приложения

Дата: 2026-10-06. Статус: in_progress.

Сервер/веб приняты в S3-07B. Начат APP-01: Kotlin/Compose, Room и WorkManager, Android SDK/API36 и эмулятор. Пока сборка и device checks не приняты; APP-02 и S3-05 не реализованы. Банковские источники остаются финальным этапом.

## APP-01 принята — 2026-10-06

Создан Android проект Kotlin/Compose с Room, WorkManager и Gradle wrapper9.1.0 (SHA-256). `make android-check android-device-check` с JDK25: APK, **2/2 unit**, lint без ошибок и **2/2 device tests PASS** на Android16/API36 с KVM. Реальная Room persistence и Compose recreation приняты; WorkManager только инициализирован, background sync еще не реализован. Схема DB v1 экспортирована, destructive migration отсутствует. Визуальная проверка показала и помогла исправить цвета system bars и keyboard/scroll behavior.

Ограничения: lint сохраняет 11 warnings о версиях/иконке/data-extraction; полные правила переноса данных нужны до сохранения credential в APP-02. Начальный SystemUI ANR эмулятора устранен до успешной device приемки. Следующий шаг — APP-02 auth/bearer/Keystore и online учет, затем S3-05 outbox/mirror/staging и APP-03 Android→server→web.
