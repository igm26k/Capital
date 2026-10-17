> Обновление эксплуатации 2026-10-17: личный production запущен на HTTPS IP, локальный backup timer включён. Подробности и оставшиеся ограничения — [PERSONAL-DEPLOYMENT](PERSONAL-DEPLOYMENT.md). Ниже сохранена приёмка реализации до развёртывания.

# Этап 6: личная версия и банковский конвейер

Дата 2026-10-16. Реализация доступного кода завершена; внешняя приёмка реальных банков и последнего APK на физическом устройстве остаётся открытой. Публикация в магазинах и публичном вебе исключена. Исходные незакоммиченные изменения сохранены первым commit `6fc4016`; сброс/stash не использовались. Каждый завершённый пакет зафиксирован и отправлен в master.

## Проверенная версия

Код: `511a29850c6a51ad14c0e500929f9273cf486e02`, SHA256 `8c274173948c97a4b448ff0604ebec10810b1605ac83e38d655845c0e84a0ad5`, 444 файлов. Digest включает tracked code/config и исключает Markdown и runtime/секреты; manifest: `ops/.runtime/checks/bank-implementation-source.json`. Последующая документация не меняет этот код. Репозиторий `igm26k/Capital`, приложение Accounting / Capital.

Linux, Docker/PostgreSQL18, Go1.26, Node24/Chrome, JDK17/SDK36. Все данные испытаний синтетические. Исторические Android device proofs прежних APK не выдаются за приёмку этой версии. ADB сейчас показывает0подключённых устройств; эмуляторы не запускались.

## Что завершено

Базовый личный учёт: счета и валюты; расходы/доходы/переводы/комиссии/возвраты/корректировки, версии и идемпотентность; категории/теги и массовая классификация; Android Room mirror/atomic staging/outbox/recovery и WorkManager; бюджеты/отчёты с предварительными операциями, раздельными валютами и порогами80%/100%; правила; CSV; auth/email recovery, устройства, полный экспорт/удаление и резервирование. Предыдущая базовая приёмка: [STAGE-05](STAGE-05-REPORT.md).

Банковский код: opt-in listener, до20профилей приложений/карт, launcher picker без QUERY_ALL_PACKAGES, безопасная локальная грамматика и card-last4, bounded private inbox с ambiguity/conflict/replay receipts. Raw текст и SBN key не сохраняются и не отправляются. Счётчик неразобранного не содержит текст. Только server-backed локальные receipts могут вытесняться; server identity продолжает защищать повтор.

Source API/worker: immutable typed intake, private actor visibility, exact semantic identity, неизменные legacy hashes, original issuer/generation/key/body, durable auxiliary receipts и job recovery. Автоматическая preliminary delivery default off, требует успешного preview/consent и стабильного notification slot; повтор slot/возможные дубли/незавершённый CSV остаются review. Неизвестный исход не получает новую финансовую попытку. Explicit create/link/dismiss/post/cancel, optimistic versions, текущие суммы/счета/классификация сохраняются, metadata link не меняет деньги. Старый tombstone допускает явную перепривязку к существующей операции с историей; живая операция не заменяется.

Web/native review и keyset paging, durable retry без нового ключа, CSV/source CAS между вкладками, transfer/refund links. Новый bank candidate после CSV preview возвращает ready с новой версией до записи денег. Точный CSV fingerprint имеет приоритет; независимая одинаковая покупка не сливается без решения владельца. Все source финансовые изменения идут через прежний CommandRunner/sync protocol.

Личная APK identity создаётся один раз и повторно используется. Ключ/пароли/APK вне Git; apksigner и compiled TLS проверяются автоматически. Ничего не опубликовано. Инструкция: [PERSONAL_ANDROID](../../ops/runbooks/PERSONAL_ANDROID.md).

## Проверки

| Gate | Итог |
|---|---|
| Full Go tests и vet | PASS, integration69.728s; Android browser-host bridge требует отдельного устройства и здесь не исполнялся |
| OpenAPI/static examples/protocol checks и Compose isolation | PASS, core0.7.2,60operations,29valid/24invalid examples,5events |
| Реальные API guards/body schemas | PASS,60routes/55private guards,276HTTP bodies/12schemas |
| Bank CSV / tombstone relink | PASS,30HTTP/5schemas и15HTTP/3schemas; финансовый digest неизменен при metadata link |
| Auto sources | PASS,28HTTP/4schemas; slot updates, legacy/no-slot, CSV queued/failed, revoked session, review/replay |
| Web build/types/E2E types | PASS |
| Full Chrome | PASS29/29,2.9m, реальный HTTPS API/PostgreSQL/worker |
| Android JVM | PASS93/93,0fail/0skip |
| Android debug, signed release, lintDebug/lintRelease | PASS10m23s через private helper --verify-all |
| APK identity и compiled TLS | PASS, постоянный certificate SHA256 `7c2ab15989439f94f1327cc234be484700fe00b6b06788d9c0c46a8470f26bde`; system CA only, cleartext/debug CA отсутствуют |
| Ops unit | PASS23/23,9.679s |
| Verified HTTPS smoke | PASS, без финансовых writes |
| Реальный signed/encrypted backup restore | PASS28tables, key rotation, monthly crash repair, failure/retry и concurrent publisher guards; временные keys удалены |
| Новый APK на физическом Android / Native→server→Chrome | NOT RUN,0connected devices; эмуляторы исключены |
| Реальный банк/API/контрольная выписка/разные OEM | NOT RUN, нет выбранных банков/образцов/доступа |

Первый общий browser run обнаружил регрессию приоритета exact CSV identity. Исправление проверено отдельным regression и повторным полным29/29; первый1failed/28passed не является итоговой приёмкой. Один native run остановлен для stable-slot guard; итоговые результаты относятся к повторной проверке, а не прерванной сборке.

Private proofs: `ops/.runtime/checks/` (bank-csv/source-relink/source-auto/access responses, signed-backup-result.json, personal-apk.json, android-device-availability.json). Личный APK и подписанный manifest сохраняются в `ops/.runtime/personal-android/artifacts/`. После документационного commit выполняется упаковка чистого дерева тем же ключом; актуальный manifest содержит точный source HEAD, SHA256 APK и source_dirty=false.

## Оставшиеся внешние зависимости

APP-02/S3-05/APP-03/S6-04: физическое тестовое устройство для последнего APK. S1-02/S4-02/S4-06: реальные package names/обезличенные уведомления/выписки владельца для проверки покрытия. S4-05: доступный и разрешённый выбранным банкам API provider, авторизация и условия; фиктивный адаптер не выдаётся за интеграцию. S6-03: установка таймеров/независимого offsite/alerts в выбранном постоянном личном окружении и измеренный RPO. Код автоматизации и isolated restore готовы; production/offsite не настроены.

Семейный доступ, цели, OCR, инвестиции и сложные AI-функции остаются последующими этапами исходного плана. Магазин, публичный сайт/пилот, публичный домен и SMTP не являются условиями завершения личного MVP. Следующий кодовый пакет возможен после конкретизации внешних банковских входов; остальные доступные пакеты выполнены.
