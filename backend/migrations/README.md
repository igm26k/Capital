# SQL migrations

Начальная схема S3-02A реализована: 0001_identity.sql, 0002_ledger.sql, 0003_sync.sql. Номера непрерывны, примененные файлы неизменяемы; следующие изменения оформляются новой миграцией. Файлы не содержат внешних BEGIN/COMMIT: runner выполняет их в своей транзакции, сохраняет SHA-256 и отказывает при изменении примененного SQL.

Особенности хранения: transactions.opening_account_id определяет счет даже при нулевой opening без entries; уникальность сохраняется для всей истории. sessions хранит SHA-256 token/CSRF hashes, без сырых credentials. tags.name_normalized заполняется сервисом. FX — numeric с проверкой целого положительного значения до 40 цифр, без округления дробного ввода. Остатки рассчитываются из entries; отдельные балансные колонки не создаются.

Ссылки workspace-сущностей составные; sync_heads.generation_id согласован с Workspace.sync_generation_id отложенным FK для атомарной смены поколения. Actions и snapshot markers намеренно не ссылаются на текущее поколение/удаляемый журнал: они переживают retention/reset. Группа ссылается на actor-scoped receipt отложенным FK; очистка группы каскадно удаляет события.

CHECK/FK/UNIQUE ограничивают локальные значения и связи. Формы движений, суммы частей, валюты связанных операций, лимиты возвратов, полное дерево категорий, group ordinals/count/payload consistency, snapshot immutability, membership authorization и порядок блокировок должны проверяться финансовым/sync сервисом в транзакции. Эти сервисные проверки реализованы в S3-02/S3-04 и проходят PostgreSQL acceptance; прямой SQL обходит доменный CommandRunner. Production DB-права на запись еще не настроены.

Проверка: make schema-check запускает настоящую accounting_test, применяет bundled migrations в отдельной временной схеме и очищает эту схему. Тест отказывает для другого имени БД; без TEST_DATABASE_URL обычный go test помечает его skipped. Локальное применение: make migrate.

0004_snapshot_cursor_key.sql добавляет nullable cursor_key_id к snapshot markers. Новые снимки сохраняют ID ключа HMAC, поэтому metadata/page tokens не меняются при активной ротации; секрет ключа остается только в конфигурации. Marker без key ID считается истекшим и требует нового bootstrap. Начальные SQL файлы не редактировались. Schema acceptance проверяет свежие 4 миграции; runner по-прежнему применяет upgrade по checksum и номеру.
