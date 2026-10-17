# Личное развёртывание — 2026-10-17

Accounting запущен по `https://195.133.21.189` на текущем сервере.
Публикация в магазинах не выполнялась. Доступ через интернет защищён входом;
публичная регистрация выключена, SMTP/recovery email не подключены.

## Окружение

Отдельный Compose project `accounting-prod`, PostgreSQL volume
`accounting-prod_db-data`, API, worker и nginx. Все четыре постоянных сервиса
имеют restart=unless-stopped. Только nginx публикует TCP 443 на публичном IPv4;
production PostgreSQL/API не имеют опубликованных портов. Тестовый project и
сторонние сервисы сохранены. Apache продолжает обслуживать порт 80 и ACME.

Конфигурация и секреты вне Git: `/etc/accounting/production.env` (0600),
`/etc/accounting/private/`, `/etc/accounting/db-tls/`, `/etc/accounting/db-ca/`.
PostgreSQL использует verify-full и частный CA; HTTPS — публичный сертификат
Let's Encrypt для IP с автоматическим продлением и nginx reload.
Production web TLS mount: `/etc/accounting/web-tls`.

Пользователь `owner@accounting.local`, timezone Europe/Moscow, создан через
временный внутренний API без опубликованных портов. Публичная регистрация
не включалась. Временный контейнер удалён, его сессия отозвана. Пароль находится
только в `/etc/accounting/owner-access.txt` (0600). Этот адрес служит логином;
доставка почты на него не настроена.

## Проверки

- Production preflight: все 6 проверок пройдены, системное доверие HTTPS.
- Эксплуатационные unit-тесты: 24/24, включая срок короткого IP-сертификата.
- Миграции выполнены отдельным контейнером после предмиграционного backup.
- Verified HTTPS smoke: readiness, HTML/security headers, anonymous auth/export.
- Вход с Secure cookie, session/me/workspaces, logout, регистрация 403.
- Chrome с системной проверкой TLS: вход, сохранение сессии при reload, выход;
  ошибок JavaScript нет, финансовые записи не создавались.
- Deploy hook проверен с работающим production nginx: config test и reload;
  после reload повторный HTTPS smoke успешен.
- Первый local backup systemd service: Result=success, ExecMainStatus=0.
- Проверки HTTPS выполнялись с этого сервера по публичному IP; доступ из
  независимой внешней сети отдельно не подтверждён.

## Резервирование и ограничения

`accounting-backup.timer` включён: ежедневно 02:00 UTC + до 5 минут,
retention 30 daily / 12 monthly. Каталог `/var/backups/accounting` (0700).
Это локальные резервные копии, независимый offsite не подключён. Конфигурация,
CA, signing identity Android и ключи курсоров также требуют отдельного
защищённого сохранения владельцем. Внешние alerts не настроены.

Android подключается к адресу `https://195.133.21.189` с тем же личным логином.
Проверка на физическом устройстве и реальные банки остаются открытыми;
эмуляторы не запускались. При смене IP нужно обновить сертификат, hook и origin.

## Управление

```sh
cd /root/Accounting
python3 ops/preflight.py --env-file /etc/accounting/production.env
python3 ops/deploy.py --env-file /etc/accounting/production.env --backup-directory /var/backups/accounting
systemctl list-timers accounting-backup.timer accounting-certbot-renew.timer
systemctl start accounting-backup.service
journalctl -u accounting-backup.service -u accounting-certbot-renew.service
```

Повторный deploy делает backup перед миграциями. Не запускать development/test
reset-команды для production и не восстанавливать dump поверх действующей БД.
