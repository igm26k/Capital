# Этап 4: CSV-импорт и экспорт

Дата: 2026-10-15. Статус: done в согласованном объеме реализации без Android device исполнения. Ограничения базового выпуска перечислены в STAGE-05-REPORT.md.

UTF-8 parser поддерживает BOM, comma/semicolon/tab, exact signed minor units, mapping колонок, YYYY-MM-DD/RFC3339 и timezone. До10MiB/1000строк; ошибки видны до подтверждения. Raw CSV сервер не хранит после нормализации. Предпросмотр immutable, действует24h. Подтверждение проверяет версию и запускает durable job.

Каждая строка проводится финансовой командой с постоянными IDs/key; обновление row/job/source атомарно с entries/journal/receipt. Worker проверяет server-recorded issuing session UUID, membership и generation для каждой строки, без хранения bearer. После отзыва/истечения сессии job останавливается; продолжение требует явного подтверждения текущей сессией и затрагивает только непримененные строки.

Source identity = hash файла+mapping+номер логической строки. Одинаковые покупки на разных строках сохраняются раздельно. Повтор файла использует исходные ссылки, не заменяет категорию/примечание. Сумма+дата не доказывают дубль; совпадения требуют решения и текущей версии выбранной операции.

Экспорт до10000движений/10MiB содержит точные суммы, валюту, status/kind, source IDs, категории/теги JSON, reversible spreadsheet escaping. Собственный экспорт сопоставляется по source_transaction_id, включая transfer/refund/adjustment, без новых проводок и с сохранением последующих пользовательских правок. Новые сложные операции внешних выписок требуют ручного разбора. Полное восстановление БД выполняется backup/restore, а не трактовкой CSV как полного backup.

Web/Android реализуют предпросмотр, решения строк, прогресс, повтор и выбор/сохранение файлов. Web сохраняет большой запрос в IndexedDB до HTTP, Android — AtomicFile noBackup; исходные ключ/body/session/generation сохраняются. Потерянный ответ требует повтора исходного запроса. Reload после проведения задания не доказывает получения квитанции: отдельная неизвестная команда остается доступной для повтора.

Проверки PostgreSQL TestCSV: PASS,29 HTTP bodies/6 OpenAPI schemas. Проверены identical rows, durable partial progress/restart, same-file replay, revoke/resume, foreign/unauthenticated access, own-export roundtrip и сохранение пользовательского примечания. Добавлен8MiB parser/hash regression с проверкой неизменности прежнего1MiB лимита финансовых команд. Финальный Chrome recovery большого файла и общий Android gate: STAGE-05-REPORT.md. Device исполнение не запускалось по поручению пользователя.
