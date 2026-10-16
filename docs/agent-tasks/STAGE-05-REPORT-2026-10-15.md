# Этап 5: техническая приемка базового кандидата

Дата: 2026-10-15. Статус: in_progress. Публичный выпуск не выполнен; весь проект завершенным не считается.

## Проверенная версия

База Git: e81fadd8b2b735fb79640ed9edc245d05b0e9ed4 плюс незакоммиченная рабочая копия. Исходные изменения пользователя сохранены, reset/stash/commit не выполнялись. Проверенный набор исходников backend/web/Android/contracts/ops/scripts/Makefile/Compose: SHA256 `2810dc96191f5cc3ac3d39bfb1bcc04ca4fa15d5e52433e95855e2d5f1df038d`,383файла. Manifest в restricted `ops/.runtime/checks/implementation-source.json`; документация отчета не входит в digest.

Среда: Linux, Docker Compose, PostgreSQL18/TLS, Go из build image, Node24/Chrome, JDK17/Android SDK36. Тестовые данные синтетические. Эмуляторы и device execution исключены прямым поручением пользователя; их отсутствие не считается успешной приемкой Android.

## Реализовано и принято в текущем цикле

- Бюджеты, отчеты, предупреждения80/100%, отдельные валюты и pending, фильтры и переходы в историю, правила с приоритетом и явным preview/apply. Pending включен по выбору пользователя. ADR008, миграция0005, сервер и оба клиента; финансовый sync v1 совместим.
- CSV preview/решения/подтверждение, durable worker, исходные ключи строк, ручное сопоставление и повтор без дублей, сохранение пользовательских правок, экспорт и собственный roundtrip для всех видов операций. Миграция0006, сервер и оба клиента. Web сохраняет большие незавершенные запросы в IndexedDB до HTTP; Android использует AtomicFile. Лимит10MiB/1000строк, preview24h, экспорт10000движений/10MiB.
- Полный JSON экспорт без credentials; password/DELETE/CSRF/rate-limited удаление личных данных и сессий. Shared active membership отклоняет удаление. Чужая история сохраняет обезличенный actor UUID, приватные CSV reservations/drafts удаляются. Worker не продолжает удаленный профиль. Actual401 в дополнительных экранах скрывает финансовые данные.
- Backup/restore, мониторинг и runbook, пользовательская инструкция и материалы выпуска. Recovery новой БД меняет поколения, отзывает восстановленные сессии, очищает старый журнал/snapshot payload и останавливает CSV до явного решения. Финансовые версии/данные и исторические квитанции сохраняются.

## Итоговые проверки

| Проверка | Результат |
|---|---|
| `make check-contracts compose-check` | PASS:52 core/0 draft operations;23 valid/16 invalid examples,5 events; Compose local/test/prod isolation |
| `go test ./... -count=1` на PostgreSQL/TLS | PASS; последний полный integration run55.211s |
| `go vet ./...` | PASS |
| OpenAPI captured bodies reports/planning/CSV/user-data | PASS:27/3,21/7,29/6,14/7 responses/schemas |
| Access matrix и captured bodies | PASS:52operations/50private guards;258HTTP bodies/12schemas |
| Independent reporting arithmetic | PASS: точные валюты, pending, возвраты, splits, transfers, границы |
| Reports pilot10000 synthetic transactions |321.579604ms в test среде; не production SLA |
| `make e2e` | PASS:21/21 Chrome tests,2.3min; настоящий API/PostgreSQL/TLS |
| Web production build и `npm --prefix web run typecheck:e2e` | PASS |
| Android assembleDebug/assembleRelease/assembleDebugAndroidTest/testDebugUnitTest/lintDebug | PASS:53unit tests,0failures/errors; Gradle10m24s,129tasks |
| Compiled release trust | PASS: system CA only, cleartext disabled, debug CA отсутствует |
| Actual backup → isolated restore → comparison | PASS:24таблицы, затем recovery rotation/session revoke и повторная финансовая сверка |
| Aggregated monitor | queued/failed/unapplied/oldest/expired_snapshot =0 на момент приемки |
| `git diff --check`, Bash syntax ops scripts | PASS |

Сервер запускался воспроизводимо через `docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml --profile checks run --rm -v "$PWD/backend:/src" -v "$PWD/contracts:/contracts:ro" reports-check sh -c 'go test ./... -count=1 && go vet ./...'`. Проверки, владеющие test DB, выполнялись последовательно с E2E; старый конкурентный сбой среды не используется как доказательство приемки.

Android команда из android/: `ANDROID_HOME=/opt/accounting-sdk ./gradlew --no-daemon -Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' --max-workers=1 assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug`. Test APK только собран; instrumentation не исполнялась. Итоговый release manifest содержит INTERNET/ACCESS_NETWORK_STATE, служебные WorkManager WAKE_LOCK/RECEIVE_BOOT_COMPLETED/FOREGROUND_SERVICE и внутреннее signature receiver permission. SMS/notification listener permissions отсутствуют.

Новые Chrome сценарии проверяют бюджеты/правила/переходы, CSV identical purchases/repeated file/export, потерю confirmation reply с reload и повтором,8MiB upload с потерей reply/reload и тем же key/SHA256 тела, JSON export/delete и настоящий session revoke/401 из analytics/CSV/privacy. Исправлены сериализация authorization и ограничения большого CSV; финансовый1MiB лимит сохранен отдельным regression.

## Артефакты

Restricted runtime: `ops/.runtime/checks/restore-result.json`, captured response artifacts, `ops/.runtime/backups/test-20261015T183431Z.dump` и SHA256. Восстановление действительно выполнено в отдельную временную БД, затем она удалена; исходная БД процедурой не изменялась. Backup содержит финансовые данные и не включается в Git.

Android: `android/app/build/outputs/apk/debug/app-debug.apk`; unsigned release `android/app/build/outputs/apk/release/app-release-unsigned.apk`, SHA256 `be080f3e29d248e49daa8a949f34f62d5c310bf135d46a48290b5842e69888ec`. Debug SHA256 `03e20050c3369db27bb2abe720e9c2969378081a3d5cf7653c0e6afe5ea8cec4`. Подпись владельца не выполнена.

Инструкции: [USER_GUIDE.md](../USER_GUIDE.md), [RELEASE_MATERIALS.md](../RELEASE_MATERIALS.md), [release runbook](../../ops/runbooks/release.md). Детали реализации: STAGE-03/04-REPORT.md и карточки S5/CSV/S6.

## Открытые условия полного завершения

1. PUBLIC-REGISTRATION: production регистрация принудительно закрыта. Verification-flow/anti-enumeration не реализованы; password reset по email также отсутствует. Это функциональная зависимость, не закрытая тестами управления данными. Следующий незавершенный кодовый пакет — [PUBLIC-REGISTRATION.md](PUBLIC-REGISTRATION.md). Нельзя объявлять готовым сервис с открытой регистрацией.
2. Android runtime/cross-client offline и background scenarios APP-02/S3-05/APP-03 не перепроверены в этом цикле по поручению пользователя. Остальные браузеры и физические устройства не проверены. Сборка/unit/lint не заменяют эти доказательства.
3. S6-03/RECOVERY-RPO: production external backup, расписание, alert delivery, фактический RPO и дежурный не настроены. Локальный restore и безопасная rotation доказаны; данные после момента backup не обещаны восстановленными.
4. S6-06: production domain/host/HTTPS credentials, Android release signing/store access, контакт поддержки/политика фактического размещения, пилот и публичная публикация отсутствуют. Локальный Docker запуск не является выпуском.
5. По FINANCE_APP_PLAN.md банковские исследования и подключения начинаются только после базового выпуска. Банковская поддержка не реализована и не заявляется; нет реальных банковских samples/доступов/выбранного покрытия.

Этапы3/4 приняты в согласованном объеме реализации без device исполнения. Этап5 остается in_progress, этап6 не начат. Внешнее одобрение и незапущенные проверки не подменяются наличием кода или инструкций.
