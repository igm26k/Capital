# APP-03: сквозная приемка Android → сервер → веб

Статус: in_progress (предварительная online сверка; финальная приемка зависит от APP-02 и S3-05)
Исполнитель: координатор, последовательно
Зависимости: APP-02, S3-05 — еще не завершены
Входы: docs/implementation/07_APPLICATIONS_PLAN.md, contracts/openapi.json, docs/decisions/005-sync-protocol.md

Критерии: счет и операция из Android видны в настоящем вебе с exact amounts; offline create/edit/delete доставляются после восстановления связи; outbox restart/retry/temporary errors не теряют изменения и не создают дубли; конфликты двух клиентов явны, устаревший cursor восстанавливается без потери local actions; секреты защищены, чужие данные недоступны, устройства отзываются. Нужны реальные emulator/API/PostgreSQL/Chrome, отчет с proof для каждого критерия.

Шаг47 принимает первый online сценарий: `make android-web-e2e` exit0, native1/Chrome1/unit41/build/lint PASS. Compose UI создает KWD100000 account и expense12345; Chrome UI видит balance87655 и тот же transaction, reload/SQL unchanged, two financial actions и one expense receipt. Restricted artifacts: android-web-instrumentation.txt, android-web-proof.json, android-web-result.json; credential/password отсутствуют. Офлайн и final cross-client recovery еще не приняты; задача остается in_progress.
