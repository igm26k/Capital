# APP-03: сквозная приемка Android → сервер → веб

Статус: blocked (runner и код готовы; нет физического устройства для финальной native→server→web проверки)

Актуально 2026-10-16: Последний APK/93JVM и web29/29 приняты раздельно; сквозной native host runner на текущем APK не запускался. Физическое тестовое устройство отсутствует, эмуляторы не разрешены. Зависимость конкретная внешняя; новые mock-проверки не объявляются native→web доказательством. Актуальный итог STAGE-06-REPORT.md.

## История выполнения и прежних проверок

Исполнитель: координатор, последовательно
Зависимости: APP-02, S3-05 — еще не завершены
Входы: docs/implementation/07_APPLICATIONS_PLAN.md, contracts/openapi.json, docs/decisions/005-sync-protocol.md

Критерии: счет и операция из Android видны в настоящем вебе с exact amounts; offline create/edit/delete доставляются после восстановления связи; outbox restart/retry/temporary errors не теряют изменения и не создают дубли; конфликты двух клиентов явны, устаревший cursor восстанавливается без потери local actions; секреты защищены, чужие данные недоступны, устройства отзываются. Нужны реальные emulator/API/PostgreSQL/Chrome, отчет с proof для каждого критерия.

Шаг47 принимает первый online сценарий: `make android-web-e2e` exit0, native1/Chrome1/unit41/build/lint PASS. Compose UI создает KWD100000 account и expense12345; Chrome UI видит balance87655 и тот же transaction, reload/SQL unchanged, two financial actions и one expense receipt. Restricted artifacts: android-web-instrumentation.txt, android-web-proof.json, android-web-result.json; credential/password отсутствуют. Офлайн и final cross-client recovery еще не приняты; задача остается in_progress.


## Продолжение 2026-10-15: офлайн Android → Chrome

Добавлен `make android-offline-web-e2e`: общий Android/Compose setup, настоящий outage API, 36 команд из Compose UI, force-stop и cold recovery, затем вход отдельной browser session в тот же workspace. Используется существующий host driver `check-android-offline-tags.py --chrome`; native proof передает synthetic email и UUID счета/дохода/исходной команды, без credential/password.

Chrome проверяет balances124456/10000 KWD, income1000/version2 с категорией и тегом, ровно три активных операции (две opening и income), отсутствие удаленных expense/income/transfer/adjustment, reload и неизменность SQL snapshot (37 actions/groups/head37). Online `make android-web-e2e` по-прежнему выбирает свой `roundtrip.spec.ts`.

Статус не повышен: настоящий native→Chrome сценарий **not_run**. Запуск target остановился до установки APK: `adb: device 'emulator-5556' not found`. `/opt/accounting-sdk/emulator/emulator -accel-check` возвращает отсутствие vmx/svm; ADB devices пуст. Сборка веба и обнаружение теста Playwright проверяются отдельно и не заменяют сквозную приемку. Следующая готовая проверка на Android runner — `make android-offline-web-e2e`, затем полный `make android-auth-e2e` и online `make android-web-e2e` на одной итоговой версии.
