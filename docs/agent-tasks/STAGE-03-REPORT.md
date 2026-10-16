# Этап 3: бюджеты, отчеты и правила

Дата: 2026-10-15. Статус: done в согласованном объеме реализации без Android device исполнения. Ограничения базового выпуска перечислены в STAGE-05-REPORT.md.

По поручению пользователя работа продолжена без эмуляторов и вопросов. Device сценарии APP-02/S3-05/APP-03 остаются непроверенными; исключение этих запусков не является доказательством их прохождения.

Решения ADR008: пользователь выбрал учет pending. Валюты и статусы раздельны, период UTC [from,to), месяцы в IANA timezone, лимиты с дочерними категориями, предупреждения80/100%. Возвраты уменьшают расходы по своей дате; transfer/opening/adjustment исключены из доходов и расходов. Баланс включает все движения счета и не фильтруется категорией/тегом; интерфейс это объясняет.

Реализованы Go reports/budgets/rules API, миграция0005, versioned/idempotent planning storage с audit. Финансовый sync v1 сохраняет прежние четыре типа сущностей. Веб/Android показывают лимиты и остатки, pending, категории/доли, динамику, фильтры и переход к операциям; доступны CRUD и явный preview/apply правил. Незавершенные запросы сохраняют исходные IDs/body/generation. Unknown результат не отбрасывается и не переносится в новое поколение. Правила не заменяют пользовательские категории; применение сохраняет теги и проверяет версии всей группы.

Контрольные суммы пересчитаны независимо Python integer oracle: PASS. PostgreSQL TestReports/TestPlanning: PASS; OpenAPI captured bodies27/3 и21/7. Проверены split1200=500+700, refund100, pending200, transfer±100, EUR/KWD, границы microseconds, изменения/удаления, auth/foreign references. Unit проверяет DST Europe/Berlin, пороги и aggregate money выше лимита одной записи. Отчет на10000 synthetic transactions:321.579604ms в isolated test PostgreSQL; это измерение одной среды, не production SLA.

Исправлена transient serialization failure authorization при concurrent last_seen: bounded retry consistent transactions. Клиентские чтения аналитики сериализованы. Actual401 скрывает финансовые данные. Финальный Chrome/Android gate, идентификатор версии и ограничения: STAGE-05-REPORT.md.
