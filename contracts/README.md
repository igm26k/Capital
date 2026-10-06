# Контракты Accounting

- `openapi.json`: HTTP API первого этапа, `/api/v1`, 35 операций ручного учета и синхронизации.
- `openapi.future.json`: предварительные интерфейсы поздних этапов, 9 операций; не часть первого сервера.
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

Текущий draft: 0.2.0. Изменяющие команды workspace требуют X-Sync-Generation, полученный из Workspace.sync_generation_id/снимка. Generation присутствует в группах, событиях и ответах; строки sequence разных поколений не сравниваются. Контракт 0.1.0 не выпускался.

[Результаты проверки проектирования](../docs/decisions/006-design-review.md) и `fixtures/design-review-cases.json` содержат ожидаемые интеграционные проверки, которые пока не запускались. Большие сводные суммы используют AggregateMoney, суммы операций/счетов — Money.
