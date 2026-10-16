# Автоматизация эксплуатации

Скрипты подготовлены и проверены на isolated test PostgreSQL. Эти инструкции сами по себе не означают действующее production расписание, внешний storage или доставку alerts.

## Подготовка хоста

Установить Docker Compose, Python3, OpenSSL, age, GNU coreutils и systemd. Разместить checkout в `/opt/accounting`. Пользователю `accounting` необходим доступ к Docker: это привилегированный доступ к хосту. Каталоги backup и offsite должны быть отдельными абсолютными каталогами с правами0700. Offsite должен находиться на фактически отдельном защищенном хранилище; соседний каталог на том же диске используется только в тестах.

Создать age identity на отдельном recovery хосте (`age-keygen -o recovery.identity`); на backup хост передать только публичные recipients. Хранить recovery identity офлайн с правами0600. Создать отдельный ключ подписи `openssl genpkey -algorithm Ed25519 -out backup-signing.pem`, права0600; его публичную часть получить через `openssl pkey -in backup-signing.pem -pubout -out current.pub`. Защитить каталог доверенных `.pub` от записи посторонними. При ротации сохранять старые публичные ключи и age identities до истечения retention. Подпись проверяется до расшифрования: знание публичного age recipient не позволяет создавать доверенные резервные копии.

Пример `/etc/accounting/backup.env`, защищенный0600 (заменить пути и origin):

```ini
ACCOUNTING_ENV_FILE=/etc/accounting/production.env
ACCOUNTING_BACKUP_DIR=/var/backups/accounting
ACCOUNTING_OFFSITE_DIR=/mnt/offsite/accounting
ACCOUNTING_AGE_RECIPIENTS=/etc/accounting/age-recipients.txt
ACCOUNTING_BACKUP_SIGNING_KEY=/etc/accounting/backup-signing.pem
ACCOUNTING_BACKUP_VERIFICATION_KEYS=/etc/accounting/trusted-backup-keys
PRODUCTION_PUBLIC_ORIGIN=https://accounting.example.org
```

Переменные production Compose находятся в отдельном защищенном production.env; ориентир `.env.example` и `compose.prod.yaml`. Не публиковать ключи, дампы или защищенные deployment логи.

## Расписание и контроль

После ручного успешного цикла с переменными выше установить шаблоны:

```sh
sudo install -m 0644 ops/systemd/accounting-* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now accounting-backup.timer accounting-monitor.timer
sudo systemctl list-timers accounting-backup.timer accounting-monitor.timer
```

Backup выполняется ежедневно02:00UTC со случайной задержкой до5минут. Retention:30дней daily и12календарных месяцев monthly. Monthly фиксирует первую успешную копию месяца. Удаляются только собственные распознанные имена; очистка начинается после успешной новой копии, шифрования и проверки подписи. Flock исключает пересечение циклов; публикация не перезаписывает существующий файл. Ошибка сохраняет предыдущий last-success и старые копии; отдельный error marker сбрасывается только успешным циклом.

Монитор каждые5минут проверяет verified HTTPS, защищенные anonymous endpoints, возраст backup (26часов), последний сбой и агрегаты очередей/очистки. JSON и ненулевой exit code предназначены для существующего внешнего сборщика alerts. Доставку уведомления реальному контакту необходимо настроить и проверить отдельно. Суточное расписание является целевым RPO, фактический production RPO/RTO измеряются восстановлением.

Если публикация прервалась между dump, checksum и подписью, следующий запуск не доверяет неполному offsite артефакту. Оператор проверяет журнал и переносит неполный набор в защищенный quarantine, затем повторяет цикл. Не пересчитывать подпись неизвестного файла в хранилище. Missing checksum локальной monthly автоматически восстанавливается только из доказанно идентичного daily inode.

## Восстановление

```sh
python3 ops/decrypt_backup.py /recovery/test.dump.age /recovery/clear.dump --identity /offline/recovery.identity --verification-key /recovery/trusted-backup-keys
```

Скрипт проверяет SHA256 и подпись доверенного отправителя, расшифровывает в новый файл и никогда не перезаписывает существующий dump/checksum. Далее следовать `release.md`: новая БД, проверка данных, `recovery-prepare.sql`, проверка поколений/сессий и повторный вход клиентов. Не восстанавливать поверх работающей БД. `make ops-check` воспроизводит локальный drill, временно останавливая и затем возвращая только ранее работавшие test api/worker; к production не обращается.

## Развертывание

```sh
python3 ops/deploy.py --plan --backup-directory /var/backups/accounting
python3 ops/preflight.py --env-file /etc/accounting/production.env
python3 ops/deploy.py --env-file /etc/accounting/production.env --backup-directory /var/backups/accounting
```

`--plan` не меняет инфраструктуру. Preflight проверяет production origin, совпадение DB target с backup, TLS цепочки/hostname/ключи и срок не менее7дней, cursor keys, конфигурацию регистрации/SMTP. Для публичного origin используется системное доверие сертификатам; локальный debug CA не является production приемкой.

Apply собирает образы, закрывает ingress/останавливает writers, делает backup до миграции, мигрирует, запускает API/worker, проверяет readiness и затем verified HTTPS. Защищенный лог0600 сохраняется рядом с backup. При сбое после остановки ingress/writers остаются остановленными, БД и backup сохраняются; восстановление выбирает оператор по runbook. Скрипт не объявляет завершенными пилот, Android подпись владельца, публикацию или банковские этапы.

Source events: монитор также проверяет source_failed и source_oldest_pending_seconds (>300). Recovery останавливает source jobs до запуска worker, сохраняя job/issuer/transaction links. Возобновление требует явного подтверждения владельца и проверки financial outcome; неизвестные/истекшие результаты не переиздаются автоматически.
