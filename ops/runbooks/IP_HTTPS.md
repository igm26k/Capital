# HTTPS на личном сервере без домена

Настроено 2026-10-17 для IPv4 `195.133.21.189`. Доверенный сертификат
Let's Encrypt использует обязательный для IP профиль `shortlived` (около 6 дней).
Более длинный срок для этого профиля недоступен; поддерживается автоматическое
продление. Основание: https://letsencrypt.org/2026/03/11/shorter-certs-certbot

## Установленная конфигурация хоста

- Certbot 5.8.0 в отдельном virtualenv `/opt/accounting-certbot`.
- Apache продолжает обслуживать прежний HTTP сайт; добавлен только ACME Alias
  в `/etc/apache2/conf-available/accounting-acme.conf`.
- HTTP-01 webroot: `/var/lib/accounting-acme`; входящий TCP 80 должен оставаться
  доступен удостоверяющему центру для последующих продлений.
- Постоянные сертификаты и приватные ключи вне Git:
  `/etc/letsencrypt/live/195.133.21.189/`.
- Deploy hook `/etc/letsencrypt/renewal-hooks/deploy/accounting-web` — копия
  `ops/renew-web-certificate.sh`. Проверяет IP и системную цепочку доверия,
  обновляет `/etc/accounting/web-tls/localhost.crt` (fullchain) и
  `localhost.key` (0600), затем проверяет и перезагружает только работающий
  Compose web с labels project=accounting-prod, service=web. Тесты не затрагивает.
- В production задать `PRODUCTION_WEB_TLS_DIR=/etc/accounting/web-tls`,
  `PRODUCTION_PUBLIC_ORIGIN=https://195.133.21.189`; остальные production
  переменные и отдельная база обязательны согласно `release.md`.
- Systemd service/timer установлены из `ops/systemd/accounting-certbot-renew.*`.
  Проверка каждые 6 часов плюс случайная задержка до 15 минут; пропущенный запуск
  компенсируется при включении сервера. Certbot сам определяет срок продления.
  Ошибки доступны в journal; внешняя доставка оповещений ещё не настроена.
- ACME аккаунт создан без email, так как адрес владельца не предоставлен.

## Контроль

```sh
systemctl list-timers accounting-certbot-renew.timer
systemctl status accounting-certbot-renew.service
journalctl -u accounting-certbot-renew.service
/opt/accounting-certbot/bin/certbot certificates
/opt/accounting-certbot/bin/certbot renew --cert-name 195.133.21.189 --dry-run --no-random-sleep-on-renew
```

Первичный staging и production выпуск успешны, цепочка доверия и IP проверены.
Пробное продление `certbot renew --dry-run` успешно; запуск systemd service
завершился Result=success / ExecMainStatus=0. Первый сертификат действителен
до 2026-10-23 21:33:36 UTC (2026-10-24 00:33:36 МСК).
После отдельного развёртывания 2026-10-17 production nginx использует этот
сертификат на IPv4 TCP 443. См. ../../docs/agent-tasks/PERSONAL-DEPLOYMENT.md.
При изменении публичного IP потребуется новый сертификат и обновление hook/origin.
Приватные ключи, ACME аккаунты и защищённые конфиги не добавлять в Git.
