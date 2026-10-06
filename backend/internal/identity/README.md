# Identity

Atomic registration создает user/workspace/membership/head/session. Login проверяет literal password вне user lock, затем под user FOR UPDATE повторно проверяет hash и disabled. Argon2id v19 m=65536 KiB,t=3,p=4,salt16,key32; официальный пакет [Go argon2](https://pkg.go.dev/golang.org/x/crypto/argon2). Dummy hash применяется для неизвестного email. Concurrent hashing ограничен semaphore.

WithSession: preliminary credential lookup → user SHARE/UPDATE → session SHARE/UPDATE → caller membership SHARE → commit. Credential/expiry/disabled проверяются заново; expiry дополнительно проверяется по DB clock после locks. Мутации identity получают user UPDATE. Membership guard возвращает 404 для отсутствующего/отозванного доступа. Таймаут ожидания lock 3s, statement 10s. Last-seen меняется отдельным best-effort запросом после завершения основной транзакции.

Токен — random 32 bytes, хранится SHA-256. CSRF детерминирован как HMAC-SHA256 с ключом presented session credential и меткой accounting/csrf/v1; CSRF хранится только хешем и восстанавливается без сохранения plaintext credentials. Cookie-клиент не получает bearer token в JSON. Cookie/CSRF/Origin проверяет HTTP adapter до чтения приватного результата.

Списки используют UUID keyset и HMAC cursors, привязанные к credential и endpoint; курсор другого сеанса или списка отвергается. Это отдельные list cursors, не финансовые sync cursors.

Лимиты auth — bounded memory одного процесса, peer IP и normalized email. Production trusted-proxy/distributed throttling, настоящая browser acceptance и финансовые resource/race проверки остаются открытыми. Handler не логирует auth тела/credentials.
