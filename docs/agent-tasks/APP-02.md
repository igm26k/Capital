# APP-02: Android online клиент ручного учета

Статус: todo
Исполнитель: координатор, последовательно
Зависимости: APP-01
Входы: contracts/openapi.json, docs/decisions/002-screen-specifications.md, docs/implementation/07_APPLICATIONS_PLAN.md
Разрешенные файлы: android/, scripts/ Android acceptance, собственная карточка, docs/agent-tasks/STAGE-02-REPORT.md.
Задача: HTTPS bearer регистрация/вход/renew/revoke, Keystore credential, accounts/opening/archive, полные ручные операции, классификация/фильтры и явные ошибки/конфликты по готовому серверному контракту.
Критерии приемки: реальные API/PostgreSQL; exact integer minor/versions/generation, никакого финансового auto-resubmit при конфликте; credential не в Room/логах/backup; Android запись видна в вебе.
Проверки: Gradle unit/build/lint и instrumentation на устройстве/эмуляторе с реальным TLS API/PostgreSQL; сквозная web сверка. Mock не подменяет приемку.
Результат: еще не начато. Сначала auth/Keystore/read bootstrap, затем самостоятельные проверяемые финансовые шаги; каждый завершенный шаг — отдельный commit/push.
Следующий шаг: S3-05 durable atomic outbox/mirror/staging; APP-03 общая приемка Android→server→web.
