# Контракты Accounting

- `openapi.json`: HTTP API `/api/v1`, 52 операции ручного учета, синхронизации, бюджетов, отчетов, правил, CSV и пользовательских данных.
- `openapi.future.json`: резерв для будущих банковских интерфейсов; реализованные операции перенесены в основной контракт. Старый draft reports/summary заменен GET reports.
- `events/workspace-change.schema.json`: приватный журнал изменений; протокол доставки определен в S2-04.
- `fixtures/sync-examples.json`: группы, снимки, квитанции, границы retention и восстановление очереди.
- `fixtures/api-examples.json`: положительные и отрицательные примеры схем.
- `fixtures/financial-model.json`: контрольные денежные примеры до реализации ядра.

Проверка в окружении Python 3 с зависимостью из `requirements.txt`:

```bash
python3 contracts/check_contracts.py
python3 contracts/fixtures/check_financial_model.py
```

Если зависимости нет, установите `contracts/requirements.txt` в виртуальное окружение Python. В текущей среде jsonschema 4.10.3 уже доступна. Проверка выполняется без сети: официальная структурная схема сохранена в `vendor/` с указанием источника.

Эти команды проверяют спецификации. Они не запускают сервер, PostgreSQL, браузер и реальные проверки доступа/атомарности. Семантика API описана в [решении 004](../docs/decisions/004-api-contract.md).

Протокол и сроки: [решение 005](../docs/decisions/005-sync-protocol.md). Проверка трасс — проверка спецификации; работу PostgreSQL locks и Android Room она не доказывает.

Текущий контракт: 0.3.0 (публичный выпуск пока не выполнен). Изменяющие команды workspace требуют X-Sync-Generation, полученный из Workspace.sync_generation_id/снимка. Generation присутствует в группах, событиях и ответах; строки sequence разных поколений не сравниваются. Контракт 0.1.0 не выпускался.

[Результаты проверки проектирования](../docs/decisions/006-design-review.md) и `fixtures/design-review-cases.json` содержат исходную матрицу ожидаемых интеграционных проверок. Актуальные реальные результаты находятся в docs/agent-tasks/STAGE-*-REPORT.md. Большие сводные суммы используют AggregateMoney, суммы операций/счетов — Money.

Финансовый sync v1 сохраняет прежние четыре entity types. Бюджеты/правила и контроль CSV используют отдельные versioned online receipts и не выдаются за финансовые outbox команды. Worker CSV каждую проводку выполняет через обычный финансовый runner с server-recorded issuing session, не сохраняя bearer token. Собственный CSV экспорт сопоставляет все существующие типы операций по source_transaction_id без перезаписи их текущих полей.

2026-10-16: core OpenAPI0.4.0 содержит55операций. AUTH_EMAIL_VERIFICATION добавляет202 EmailChallenge к регистрации и три публичных rate-limited endpoints для registration confirm/recovery request/recovery confirm. Android DTO генерируются из того же core. ADR009 описывает одноразовые proofs, одинаковый ответ до подтверждения mailbox и отзыв всех сессий при reset.

## Личные банковские наблюдения (0.5.0)

POST/GET source-events и GET source-events/{id} принимают только typed notification metadata с частной видимостью автора. Это durable review intake, без финансового эффекта и новых сущностей sync mirror. Source identity не зависит от record UUID; conflicting payload не перезаписывает оригинал. Реальное банковское покрытие не заявляется. Контрактные и captured HTTP fixtures проверяются отдельно от device/runtime приемки.
