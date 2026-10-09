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

## APP-02: финансовый transport и счета API — 2026-10-06

Общий MutationResult/Account DTOs генерируются из OpenAPI; суммы и версии строковые. Парные headers command/generation передаются без auto-retry. BigInteger Money проверяет валютные масштабы, синтаксис и лимит без плавающей точки. Полный `make android-auth-e2e`: 4/4 unit, **8/8 instrumentation** и debug/release/lint PASS. Android через actual API/PostgreSQL создает KWD 123456 minor с единственной opening операцией; idempotent replay структурно равен, история и остаток подтверждают отсутствие дубля. Архив/восстановление и rename/type не меняют balance_version/остаток, stale version и invalid generation дают 409. Остальные auth/device runtime drills и compiled release trust проходят.

Экраны финансового учета еще отсутствуют: этот шаг принимает только контракт и сетевой слой. Следующий шаг — сохранение неизвестного результата команды до отправки и экран счетов; APP-02/S3-05/APP-03 не завершены.

## APP-02: durable командный слой — 2026-10-06

Room v2/migration 1→2 сохраняет настройки и исходный финансовый command key/body/context до отправки, без bearer/password. CommandRunner сохраняет pending при неопределенном результате/401, terminal rejection с черновиком и confirmed receipt перед явным удалением; не повторяет terminal commands. Exact ID/state updates защищают новую команду от старого подтверждения. Команда привязана к origin/owner/workspace/session/generation; typed MutationResult проверяется на action/generation/workspace.

Полный `make android-auth-e2e`: **11/11 real instrumentation**, 4/4 unit, build/lint/release trust PASS. Genuine SQLite→Room migration и DB reopen подтверждены. Незаписанное подтверждение сервера воспроизведено закрытием DB до локальной отметки; новый runner повторяет тот же ключ, actual API/PostgreSQL history остается из одной opening записи и остаток не удваивается. Version conflict сохраняет terminal draft/key, чужая session guard и 401 сохраняют pending. Финансовый UI еще не принят; следующий шаг — UI счетов и финансовый outage/process restart drill. Full mirror/multi-command atomic outbox/staging остаются S3-05.

## APP-02: UI счетов — 2026-10-06

Добавлены создание счета с валютным initial balance/датой/timezone, список всех страниц с архивом, rename/type/archive/restore и точный остаток. Global busy + durable financial guard блокирует session mutation, пока результат команды не обработан; pending/rejected/confirmed имеют явные действия. Version conflict показывает текущую запись и черновик, обновление expected_version требует отдельного клика без auto-resubmit.

`make android-auth-e2e`: **12/12 actual instrumentation**, 4/4 unit, debug/release/build/lint и compiled trust PASS. Real Compose/API/PostgreSQL сценарий: KWD 123456 minor → rename/type/archive → внешний concurrent update → UI stale version rejection с сохраненным draft/Room key → явная актуальная версия и restore. Баланс/balance_version сохранены, очередь очищена, Activity recreation/refresh читает server state. Legacy logout/revoke outage/cold restart/SQL drills проходят вместе с UI счетов.

APP-02 in_progress. Далее финансовый outage/process restart, auth rebind/generation recovery и полный набор ручных операций; S3-05/APP-03 не приняты.

## APP-02: финансовое восстановление проверено — 2026-10-07

Account draft восстанавливает исходную дату/zone; actual UI 401→login→explicit same-key/session rebind подтвержден неизменными body/key и точным остатком после retry. FinancialOutageTest + host helper останавливают API перед нативным Create, проверяют pending/disabled auth mutation, делают настоящий force-stop/start offline, затем явное восстановление auth и повтор прежнего command UUID. Повторный restart/refresh читает счет; SQL доказывает один account/opening/applied action, 123456 minor KWD и исходные 08:00UTC/Europe-Nicosia. Обработка confirmed receipt разблокирует logout.

Полный `make android-auth-e2e`: **13/13 instrumentation без skips**, 6/6 unit, build/lint/debug/release и system-only compiled trust PASS. Доказательства без secrets: ops/.runtime/checks/android-financial-outage-instrumentation.txt и android-financial-runtime.json. Generation rejection acceptance теперь refreshes auth перед новым вводом; отдельный actual restore/epoch UI drill пока не выполнен и должен пройти в S3-05. APP-02 in_progress; впереди остальные ручные операции, классификация/фильтры, mirror/outbox и Android→web приемка.

## APP-02: доходы/расходы и история — 2026-10-07

Android создает income/expense через durable command engine с точной положительной minor суммой, активным account, strict date/timezone, note/payee и одной unclassified allocation. Полная history pagination проверяет workspace/cursor; UI отображает русские типы/статусы и точные signed entries. Confirmed receipt не удаляется до загрузки accounts/history; pending восстанавливает исходные поля после загрузки currency.

Полный `make android-auth-e2e`: **14/14 actual instrumentation без skips**, 6/6 unit, build/lint/debug/release и compiled trust PASS. Real Compose/API/PostgreSQL: KWD100000→expense12345→87655→income5001→92656; limit=1 читает всю историю. Сохраненный expense1000 после recreation/refresh восстанавливает minor/date/zone/note, same-key retry дает91656 и ровно4 записи. Ноль не пишет, архивный account запрещает новое создание, история сохраняется после recreation. Предыдущие auth/device и financial outage/process restart SQL drills прошли общим pipeline.

Test harness распознает только системный Android System UI ANR/package=android; app ANR не скрывается. Accounts acceptance ожидает фактическую row, не текст поля до refresh. APP-02 in_progress: editing/deletion, classification/split, transfer/refund/adjustment, S3-05 mirror/outbox/staging/epoch recovery и APP-03 Android→web остаются впереди.

## APP-02: изменение и удаление income/expense — 2026-10-07

Independent single-allocation операции редактируются по generated Replace DTO и expected_version; kind/currency сохраняются, unchanged instant не теряет fractions, allocation/category/tags сохраняются. Version conflict сравнивается с draft, свежая version принимается явной кнопкой после refresh. Подтвержденный DELETE с JSON version expectations использует durable command protocol и cancel; dependency checks выполняет сервер.

Actual `make android-auth-e2e`: **14/14 instrumentation**, 6/6 unit, debug/release/lint, auth/device и financial outage/cold restart/SQL gates, compiled system-only trust PASS. Real UI/API/PostgreSQL тест проверяет income update, external expense conflict/rejected draft/no auto-resubmit, latest-version update, cancel/delete expense/income, точные balances92156→93656→87655 и history4→3→2, очищенную очередь и recreation. После проверки уточнена информационная подпись сохраненной классификации; финальный android-check PASS.

APP-02 in_progress. Split/classification/tag UI и полный редактор частей, linked fees, transfer/refund/adjustment, S3-05 и APP-03 остаются впереди; этот шаг не заявляет поддержку всех редакторов финансовой модели.

## APP-02: справочник categories/tags — 2026-10-07

Добавлены generated list/create DTOs, чтение всех страниц с workspace/cursor guard и read-only Compose catalog. Categories/tags публикуются одним state update после обеих загрузок; auth401 не стирает Room pending. Real CatalogScreenTest проверяет limit=1, parent/child, literal tag text, recreation и foreign workspace404 для обоих списков.

Полный `make android-auth-e2e`: **15/15 instrumentation**,6/6unit,debug/release/lint, прежние outage/cold restart/SQL и system-only compiled trust PASS. Read-only catalog — подготовка назначения классификации/split, а не завершение этого требования. APP-02 in_progress; выбор частей/categories/tags, catalog mutations/conflicts, другие виды операций и S3-05/APP-03 остаются впереди.

## APP-02: классификация и части income/expense — 2026-10-07

Compose выбирает категории по полному пути и теги, добавляет/удаляет до100 частей, проверяет положительные minor и точную сумму через BigInteger. Редактирование нескольких частей сохраняет IDs/категории/теги; pending восстанавливается из сохраненного тела, новые операции получают новые allocation IDs.

Полный `make android-auth-e2e`: **15/15 instrumentation**, **10/10 unit**, debug/release/lint, прежние actual outage/process restart/SQL и compiled release trust PASS. Real CatalogScreenTest проверяет KWD12345=5001+7344, назначенные категории/Shared, блокировку несовпадающей суммы без изменения version, update6001+6344 с прежними IDs/классификацией и balance87655. Unit tests покрывают суммы и полную иерархию. APP-02 in_progress; catalog mutations/conflicts, другие виды операций/фильтры, S3-05 и APP-03 остаются впереди.

## APP-02: управление справочниками — 2026-10-07

Категории/теги создаются, переименовываются, архивируются и восстанавливаются; категории меняют parent. Durable POST/PUT сохраняет исходный draft/key до HTTP, форма восстанавливает поля после recreation. Конфликт показывает typed текущую запись, refresh требует отдельного принятия version и явной отправки. Каталог обновляется вместе с остальными collections до удаления confirmed receipt.

Финальный actual `make android-auth-e2e`: **17/17 instrumentation**, **10/10 unit**, debug/release/lint, outage/cold restart/SQL и compiled release trust PASS. Catalog UI tests проверяют оба вида конфликтов, сохраненный rejected body, блокировку logout, recreation, создание/move/archive/restore и запрет цикла без server mutation. Первый runtime прогон остановился из-за отсутствующего startup XML; fresh-only shared UI observer повторяет наблюдение в существующих deadlines и исключает stale/malformed evidence. Финальный gate выполнен после исправления и добавления category conflict/cycle test. APP-02 in_progress: другие финансовые виды/фильтры, S3-05 и APP-03 остаются впереди.

## APP-02: создание transfer+fee — 2026-10-07

Generated TransferCreate/FeeInput, Compose два разных активных счета, одна/разные валюты, дата/timezone, payee/note/tags, отдельно раскрываемая комиссия с категориями и до100 частей. BigInteger вычисляет reduced target-major/source-major rate; одна валюта автоматически использует одинаковые суммы. Команда сохраняет transfer+fee атомарно, pending форма читает оригинальное тело; retry сохраняет UUID/даты/части.

Финальный actual `make android-auth-e2e`: **18/18 instrumentation**, **12/12 unit**, debug/release/lint, outage/cold restart/SQL и compiled release trust PASS. TransfersScreenTest подтверждает KWD100000→90000→77154→76053, USD3000→4001→4335, rate1/1 и2002/2469, commission501=200+301 с tag/category/link и единственным balance_version bump. Lost local receipt после применения transfer1000→USD334+fee101 восстанавливается recreation/refresh/same-key UI retry без новых записей, с исходными allocation IDs/body/микросекундами. Архивный выбранный счет блокирует создание. Первый native прогон уточнил assertion серверного UTC формата .000000Z; весь pipeline повторен после исправления. APP-02 in_progress: edit/delete transfer/fee, refund/adjustment, фильтры, S3-05 и APP-03 остаются впереди.

## APP-02: редактор и удаление transfer+fee — 2026-10-07

Generated TransferReplace, immutable currency pair/существующая fee currency, сохранение entry/fee/allocation/category/tag IDs и неизменного UTC instant с микросекундами. Durable PUT включает обе expected versions; конфликт сохраняет draft, refresh не пишет и требует explicit latest-version click. Remove/add fee проверяет зависимости и создает новый UUID после удаления. DELETE preview показывает оба движения, комиссию и известные refunds; confirmation передает related fee version, cancel не отправляет запрос. Отсутствующий после успешного DELETE transfer закрывает редактор.

Финальный `make android-auth-e2e`: **20/20 instrumentation**, **12/12 unit**, debug/release/lint, прежние outage/cold restart/SQL и compiled release trust PASS. Расширенный TransfersScreenTest проверяет update12000→USD400+fee601 с прежними IDs/.123456Z, external version3 conflict/recreation/retained draft, explicit local13000→USD450+fee701 (KWD86299/USD3450), fee remove/add (87000→86899), cancel и aggregate DELETE (3 openings/KWD100000/USD3000). Отдельный actual fee refund поднимает root/fee versions, stale DELETE отклоняется; актуальные DELETE/remove fee отклоняются dependent_transactions без изменения6 записей/KWD89599. После API удаления synthetic refund с обеими parent versions native DELETE завершает агрегат. После размещения typed конфликта у transfer формы весь pipeline повторен успешно. APP-02 in_progress: UI возвратов/корректировки, фильтры/навигация, S3-05 и APP-03 остаются впереди.

## APP-02: создание refunds — 2026-10-07

Compose выбирает posted expense/fee, активный счет той же валюты и суммы по original allocations; категории наследуются. Authoritative remaining_refundable_minor и BigInteger/Money ограничивают каждую часть и общий денежный предел. Durable POST сохраняет expected parent/transfer versions, body/key; pending/rejected форма восстанавливает исходные refs/поля. Conflict refresh не пишет, версии принимаются явно. Новое создание получает новые IDs, повтор сохраняет прежние.

Финальный actual `make android-auth-e2e`: **22/22 instrumentation**, **14/14 unit**, debug/release/lint, прежние outage/cold restart/SQL и compiled release trust PASS. RefundsScreenTest проверяет partial3003=1001+2002, category/tag inheritance, quotas4000/5342, over-quota/zero rejection, external parent conflict/recreation/explicit versions и final balances88655/24003 с remaining2000. Fee refund101 и lost-receipt refund100 replay сохраняют body/key/UUID/.654321Z без дублей, fee/transfer versions3 и remaining300; source77254/alternate20101. Архивный parent account не позволяет вернуть на другой активный счет. Transfer test выбирает fee part по UUID вместо случайного номера строки. Прежний login timeout не воспроизвелся в отдельном auth UI/runtime и финальном общем прогоне после readiness assertions/безопасной диагностики. APP-02 in_progress: refund edit/delete, adjustment, filters/navigation, S3-05 и APP-03 остаются впереди.

## APP-02: редактирование и удаление refunds — 2026-10-07

Generated RefundReplace и Compose PUT сохраняют original allocation IDs/refs, категории/tags/exact instant, parent и валюту; допустима смена счета той же валюты. Квота включает собственную reservation, сохраняя ограничения чужих возвратов. Durable rejected/pending editor восстанавливается после recreation, conflict refresh не пишет, версии принимаются явно. Собственное успешное сохранение принимает версии подтвержденного результата. DELETE preview/cancel/confirmation включает expected refund/parent/transfer versions.

Финальный actual `make android-auth-e2e`: **24/24 instrumentation**, **16/16 unit**, debug/release/lint, real TLS API/PostgreSQL, прежние outage/cold restart/SQL и compiled release trust PASS. Новые сценарии проверяют смену счета/retained UUID/.123456Z/tags, stale refund conflict/recreation/explicit version, cancel/delete и restored quota5001/KWD87655; fee refund101→301, versions fee/transfer2→3→4, DELETE quota501/KWD77154/USD3300. Первый прогон обнаружил stale editor version после собственного PUT; исправление принято повторным полным прогоном. APP-02 in_progress: adjustment, filters/navigation, S3-05 и APP-03 впереди.

## APP-02: корректировки остатка — 2026-10-09

Generated AdjustmentCreate/Replace и Compose фактический signed/zero остаток, обязательная reason и timezone; Preview target−posted использует BigInteger/Money и пересчитывается после refresh; delta/current time определяет сервер. Expected balance_version проверяется независимо от account settings version. Durable POST разрешает scoped nested account UUID route, сохраняет body/key/target/version и восстанавливает pending/rejected форму. Explicit balance version после refresh без auto-resubmit. Existing adjustment редактирует только note с read-only delta/reason/time/account; DELETE preview/cancel/confirmation с transaction version.

Финальный actual `make android-auth-e2e`: **26/26 instrumentation**, **16/16 unit**, debug/release/lint, real TLS API/PostgreSQL, прежние outage/cold restart/SQL и compiled release trust PASS. Сценарии: target−1234/delta−101234, concurrent income1000/stale balance_version2→3/recreation/explicit version, zero delta234, cancel/delete→−234→101000; lost local receipt replay без нового server time/ID/record (2 adjustments/120000), note conflict/recreation/version3 при неизменном balance_version/entries/reason/time, DELETE→123456. Первый прогон выявил отсутствующее разрешение nested route в durable validation; исправление принято полным повторным gate. Lost-receipt fixture выполняет refresh после прямой записи Room; итоговый gate также принимает preview разницы до confirmation и после conflict refresh. APP-02 in_progress: filters/navigation/details, S3-05 и APP-03 впереди.

## APP-02: серверные фильтры истории и пагинация — 2026-10-09

Immutable validated TransactionFilter: account/category/tag UUID, kind/status, literal search≤200, exact UTC [from,to); values/cursor encoded separately. Reader validates workspace/unique page IDs/repeated cursor; full reader сохраняет фильтры всех страниц. Applied page/cursor отделены от полного graph, selectors/period/search/limit1–100 доступны в Compose. Apply/refresh загружают graph и первую страницу; next page использует прежний immutable filter и guards cursor cycles. Confirmed/rejected result refresh перечитывает active filter до удаления receipt. Ошибки сохраняют прошлые результаты/возможность retry; auth reset очищает applied state. Пустые filtered/unfiltered состояния имеют разные тексты, reset доступен, Activity recreation сохраняет applied state.

Приемка шага18: финальный `make android-auth-e2e` — **28/28 instrumentation без skips**, **19/19 unit**, debug/release/lint, real TLS API/PostgreSQL, прежние auth/financial outage/cold restart/SQL и compiled release trust PASS. Три unit проверяют URI value encoding/UTF-8/injection/cursor, microsecond/calendar bounds и invalid parameters. Два real HistoryScreenTest принимают combined filters/limit1/half-open range/kind/status/foreign404, UI pagination/recreation/invalid-period/no-match/reset/full graph и edit с active filter, balance97500. Первый прогон API PASS, UI остановился на неверной метке Save в тесте; исправлена существующая метка редактора. APP-02 in_progress: navigation/details/deleted-record conflicts/bulk classification, S3-05 и APP-03 впереди.

## APP-02: удаленные записи и confirmed snapshot versions — 2026-10-09

transactionsLoaded отделяет отсутствие записи в полном graph от еще не загруженной истории. Income/expense, transfer, refund и adjustment editors сохраняют поля при external deletion, показывают notice и блокируют save; DELETE preview confirmation отсутствующего root также блокируется. Filtered view не используется для проверки существования root. Rejected404 body/key остается в Room и восстанавливается после recreation; explicit refresh не превращает пропавший editor в create.

Очистка/принятие версии привязаны к конкретному confirmed command ID/root/action и выполняются один раз, а не по старому success message. Own versions/related expectations берутся из сохраненного MutationResult snapshot; более поздний GET не дает разрешения на принятие чужой версии. Snapshot decode происходит до удаления receipt, failure сохраняет confirmed command. Own DELETE закрывает соответствующий editor; own create income/expense обновляет allocation draft IDs.

Приемка шага19: финальный `make android-auth-e2e` — **30/30 instrumentation без skips**, **19/19 unit**, debug/release/lint, real TLS API/PostgreSQL, прежние auth/financial outage/cold restart/SQL и compiled release trust PASS. Actual DeletedTransactionsScreenTest: income404/Room/recreation/exact ID/instant/blocked save; stale-message deletion adjustment, transfer+fee и refund с сохранением draft/blocked save+delete/cancel/no duplicates, balances89000/3300 и101000/3000/quota1000. Actual confirmed adjustment snapshot3 переживает запись чужого version4 до acceptance: UI own3/server4 с explicit version, без записи при принятии receipt. APP-02 in_progress: navigation/details/bulk classification, S3-05 и APP-03 впереди.
