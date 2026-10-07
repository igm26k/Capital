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
