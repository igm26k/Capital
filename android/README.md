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

Нужен отдельный запущенный test emulator (по умолчанию emulator-5556, можно задать ANDROID_TEST_SERIAL). Скрипт выбирает его явно, поднимает accounting-test, временно включает test registration, настраивает adb reverse8444 и выполняет 6 базовых + 13 реальных API/UI tests. Затем проверяет force-stop/start, реальную остановку API, persisted logout intent и SQL revoke/отсутствие лишних sessions. Останавливает test Compose без удаления volume; local окружение/.env не меняются. Артефакты 0600 в ops/.runtime/checks не содержат token/password.

Приемка: **20/20 instrumentation PASS без skips**, actual TLS/PostgreSQL, runtime restart/outage/logout PASS. Без api_origin тринадцать network tests явно skip, их нельзя считать принятыми обычным standalone android-device-check. Release unsigned APK собирается для проверки ресурсов через aapt2, без публикации; local CA не входит в release. Настройка TLS: [официальная Network Security Configuration](https://developer.android.com/privacy-and-security/security-config).

Устройства: все страницы сессий, текущая метка, отзыв другого и текущего устройства. Encrypted pending target сохраняется до DELETE, блокирует другие действия и переживает process restart. Acceptance проверяет limit=1, чужие UUID/404, Compose other/self revoke, идемпотентный повтор, реальный API outage и cold restart с SQL подтверждением выбранного UUID. Финансовые экраны и mirror/outbox еще в работе.

Финансовый transport: DTOs Account/MutationResult и зависимости генерируются из shared OpenAPI; command requests передают парные UUID headers Idempotency-Key/X-Sync-Generation. Money использует BigInteger для 0/2/3 decimal scales и проверяет лимит 9e15 minor. Приняты 4/4 unit и real Android AccountApiTest: KWD opening, idempotent replay без второй записи/удвоения остатка, rename/type/archive/restore, версии и generation conflicts. UI счетов и durable financial commands — следующий шаг.

Durable commands: Room v2 с миграцией 1→2, сохранением исходных command ID/body/generation до отправки и confirmed receipt до удаления. Unique origin/owner/workspace допускает одну неразобранную команду; terminal rejection сохраняет черновик, 401/unknown оставляют pending. Real CommandApiTest проверяет replay незаписанного подтверждения после DB reopen, фактический остаток/историю, terminal conflict и сохранение pending при revoked bearer. UI счетов и полный S3-05 mirror/outbox/staging еще не приняты.

UI счетов принят отдельным шагом: создание с валютой/initial balance/датой/timezone, список с архивными, изменение имени/типа и archive/restore. Global busy/financial pending блокирует session mutation до обработки результата. При version conflict сохраненный черновик сравнивается с сервером; обновленная версия применяется явной кнопкой, auto-resubmit отсутствует. Actual AccountsScreenTest подтверждает KWD 123456 minor, архив/конкурентную правку/restore, неизменный balance_version, очередь и Activity recreation. Финансовый outage/cold restart и новый login с явным rebind сохраненной команды приняты следующим шагом; операции/классификация/full mirror/outbox еще в работе.

Финансовое восстановление: host harness запускает дополнительный FinancialOutageTest с явным outage_harness=true, останавливает real API перед нативным Create, затем проверяет cold restart offline/restore/same-key retry через UI и SQL. Итог 13/13 device и 6/6 unit PASS; один account/opening/action и 123456 minor с исходной датой/zone. Эта fixture без host gate явно skip в standalone connected tests. Actual UI новый login подтверждает explicit rebind неизменного command ID/body. Артефакты 0600 не содержат bearer/password. Серверный restore/epoch drill, прочие операции, mirror/outbox и общая Android→web приемка еще не завершены.

Доходы/расходы: создание с точной положительной minor суммой, активным счетом, датой/timezone, note/payee; одна allocation без категории, tags пусты до шага классификации. История читает все страницы и показывает русские типы/статусы и signed amounts. Общий durable engine обновляет accounts/history до обработки receipt. TransactionsScreenTest подтверждает KWD100000−12345+5001=92656, pending expense1000→91656 после recreation/refresh/same-key retry, пагинацию limit=1, UTC/timezone, ноль и archive guard. Итог **14/14 device и 6/6 unit PASS**, включая прежние real outage/cold restart SQL drills. Editing/deletion, классификация/split и остальные виды операций еще в работе.

Основные income/expense теперь редактируются (одна allocation, independent operation) с сохранением kind/currency/instant и исходной классификации; ожидаемая version проверяется сервером. Conflict сохраняет draft и требует explicit latest-version click. DELETE имеет cancel/confirmation и отправляет JSON expectations через durable queue. Расширенный real TransactionsScreenTest подтверждает update/concurrent conflict, balances92156→93656→87655 и history4→3→2 при удалении расхода/дохода, отсутствие pending и recreation. Общий gate14/14 device и6/6 unit PASS, финальный build/lint PASS. Split/catalog/classification, linked fees и остальные financial types еще в работе.

Справочник классификации: read-only categories/tags с полной pagination и workspace/cursor guard. Actual CatalogScreenTest проверяет limit=1, parent/child, literal SVG-like tag, recreation и foreign404. Общий gate **15/15 device и6/6unit PASS**. Назначение классификации/split и управление каталогом — следующие шаги.

Классификация income/expense: выбор категории каждой части по полному пути, теги и до100 частей с точной проверкой суммы через BigInteger. Несколько частей редактируются с сохранением allocation IDs и классификации; pending форма восстанавливается из durable body, новые операции используют новые IDs. Расширенный real CatalogScreenTest подтверждает KWD12345=5001+7344, категории/Shared, локальное отклонение несовпадающей суммы, изменение6001+6344 с прежними IDs/классификацией и balance87655. Общий gate **15/15 device и10/10 unit PASS**, включая outage/cold restart/SQL и release trust. Управление справочниками, другие виды операций/фильтры, полный mirror/outbox и Android→web еще в работе.

Управление справочниками: generated CategoryUpdate/TagUpdate, создание/rename/move/archive/restore, durable POST/PUT и восстановление pending/rejected формы. Typed конфликт показывает server record; refresh и принятие свежей version требуют явных действий, auto-resubmit отсутствует. До обработки receipt обновляются все collections. Три real CatalogScreenTest проверяют оба вида version conflict, recreation, блокировку logout и cycle rejection без изменения server parent/version, а также прежний split/classification сценарий. Общий gate **17/17 device и10/10 unit PASS**, actual runtime/outage/SQL/release trust. Shared UI observer принимает только свежий XML hierarchy и повторяет missing/malformed наблюдения в пределах существующего deadline. Другие финансовые типы/фильтры, полный mirror/outbox и Android→web еще в работе.

Создание переводов: два разных активных счета, в одной валюте одинаковые суммы, в разных обе суммы и точный reduced rate target major/source major через BigInteger. Комиссия сохраняется одной командой с переводом как связанный расход, имеет свой счет/note/tags и до100 частей/categories с точной суммой. Pending форма восстанавливает transfer/fee из исходного body. Real TransfersScreenTest подтверждает same/cross-currency, точные остатки KWD100000→90000→77154→76053/USD3000→4001→4335, fee501=200+301, единый balance_version bump и replay потерянного receipt без новых записей/IDs с исходными микросекундами. Общий gate **18/18 device и12/12 unit PASS**, build/lint и прежние actual outage/SQL/release trust. Editing/deletion transfer, refunds/adjustments, фильтры, полный mirror/outbox и Android→web еще в работе.

Редактор transfer+fee: полная замена с обеими expected versions, исходными entry/fee/allocation IDs, валютами и microsecond instant. При conflict сохраняются поля, refresh не пишет, версии принимаются явно; смена fee identity требует открытия актуального агрегата. Можно убрать/добавить fee с проверкой dependencies. DELETE preview/cancel/confirmation включает related fee version и предупреждает об известных refunds. Три real TransfersScreenTest принимают создание/replay, update/concurrency/recreation/fee remove-add/cancel/delete и stale delete/refund dependency rejection без потери движений. Итог aggregate DELETE возвращает KWD100000/USD3000 и3 openings, Room queue пуста. Финальный общий gate **20/20 device и12/12 unit PASS**, build/lint, real outage/SQL/release trust. UI refunds/adjustments, фильтры/навигация, полный mirror/outbox и Android→web еще в работе.
