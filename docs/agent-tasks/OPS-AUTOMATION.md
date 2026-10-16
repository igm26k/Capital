# OPS-AUTOMATION: расписание, retention и проверка deployment
Статус: done (код и локальная приемка; production эксплуатация остается S6-03/S6-06)
Исполнитель: координатор, последовательно
Область: ops/, tests/ops/, Makefile

Реализованы ежедневный backup cycle, monthly immutable copy, retention30дней/12месяцев, process lock, атомарная публикация без перезаписи, age offsite encryption и Ed25519 подпись с ротацией доверенных ключей. Failure marker сохраняет last-success и исключает очистку при ошибке. Recovery проверяет SHA/подпись до расшифровки. Добавлены production TLS/config preflight, deployment с backup до миграции, protected logs, verified HTTPS smoke и health aggregates, systemd templates. Инструкция: ops/runbooks/automation.md.

Приемка2026-10-16:17unit tests PASS; systemd-analyze verify и Bash syntax PASS. Actual test PostgreSQL dump → подписанное шифрование → расшифровка → restore26таблиц и recovery PASS. Проверены ротация ключа, восстановление monthly checksum после прерванной публикации, сохранение last-success при отказе offsite, успешный retry, конкурирующая публикация одного имени. Ephemeral private keys удалены. Доказательство: ops/.runtime/checks/signed-backup-result.json. Test API/worker возвращены в исходное работающее состояние, HTTPS smoke PASS.

Production timers не установлены, удаленное хранилище и доставка alerts не настроены; preflight текущей отсутствующей production конфигурации корректно отказывает. Реальный deployment не запускался. Внешние зависимости не маскируются локальными тестами.
