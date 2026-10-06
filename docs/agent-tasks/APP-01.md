# APP-01: Основа Android-приложения

Статус: done
Исполнитель: координатор, последовательно
Зависимости: S3-07B done
Входы: docs/implementation/07_APPLICATIONS_PLAN.md, contracts/openapi.json
Разрешенные файлы: android/, Makefile, .gitignore, docs/agent-tasks/APP-01.md, docs/agent-tasks/STAGE-02-REPORT.md; структура Android в решении 007.
Задача: Kotlin/Compose приложение, Room, WorkManager, воспроизводимая сборка и запуск на эмуляторе.
Критерии приемки: APK собирается, UI запускается, настройки сохраняются через перезапуск Room, WorkManager инициализирован; финансовые и offline функции не выдаются за реализованные.
Проверки: Gradle assembleDebug/testDebugUnitTest/lintDebug/connectedDebugAndroidTest; реальный эмулятор API36.
Результат: создан и принят исходный проект. Gradle9.1.0/AGP9.0.1/Kotlin2.2.10, KSP2.3.10, Room2.8.5/WorkManager2.11.1. Command-line tools и Gradle проверены SHA-256; SDK/AVD находятся вне репозитория.
Следующий шаг: APP-02 регистрация/сессии/Keystore/учет; S3-05 sync/outbox после online клиента.

## Приемка — 2026-10-06

- `JAVA_HOME=<JDK25> make android-check android-device-check` — PASS через committed Gradle wrapper9.1.0: assembleDebug, 2/2 unit, lintDebug, **2/2 instrumentation** на реальном KVM эмуляторе Android16/API36 (accounting-api36, emulator-5556).
- ADB install и cold start MainActivity — PASS. Экран визуально проверен; исправлены системные цвета light/dark, safe insets и прокрутка с клавиатурой.
- Реальная Room DB сохраняет origin после close/reopen; Compose сохраняет его через Activity recreation; WorkManager инициализируется. Экспортирована схема v1; destructive migration не включена.
- URI допускает только HTTPS origin без userinfo/query/fragment/path; unit проверяет отказ утечки credentials и небезопасных адресов. Backup flag выключен, cleartext запрещен. Keystore и полные правила исключения device-transfer — APP-02.
- Lint без ошибок, 11 warnings: доступные обновления AGP/dependencies, data extraction rules и launcher icon. Предупреждения не скрыты baseline/suppression. Старый KSP заменен из-за реального AGP9 sourceSets failure, затем проверки повторены успешно. Первичная загрузка эмулятора показала SystemUI ANR под нагрузкой; после загрузки и закрытия диалога оба device tests прошли.

APP-01 завершена; auth/финансовые команды/background sync этой приемкой не объявляются готовыми.
