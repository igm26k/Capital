# PUBLIC-REGISTRATION: email verification и восстановление пароля
Статус: done (реализация и локальная техническая приемка; production delivery относится к S6-06)
Дата:2026-10-16
Основание: ADR004/REV07, ADR009, S6-02
Область: backend/migrations/0007_email_auth.sql, identity/email_auth.go, platform/httpapi/email_auth_routes.go, maildelivery/, worker/config, contracts0.4.0, web/Android auth UI

Результат: одинаковый202 до подтверждения mailbox; без профиля/сессии до верного кода и исходного пароля. Код256бит/30минут/одноразовый, digest в БД; очередь писем AES256-GCM, verified TLS SMTP, retries/backoff/cooldown/лимиты. Recovery требует новый пароль и атомарно отзывает все сессии. После потери ответа безопасен обычный вход; использованный код не применяется повторно. Удаление профиля и восстановление БД стирают старые proofs. Production разрешает регистрацию только с явным включением и полной защищенной конфигурацией.

Проверки: полный Go suite/vet PASS; PostgreSQL подтверждает expiry/reuse/purpose isolation, одинаковые start responses, original-password binding, concurrent use одного кода, SMTP retry с исходным кодом, revoke всех сессий и очистку при удалении.17 реальных HTTP bodies/4email schemas;55API operations/50private guards,261access bodies/12schemas. Dedicated Chrome1/1 с реальным клиентом/API/PostgreSQL проверяет registration→recovery→401другого браузера→вход новым паролем. SMTP transport проверен настоящим локальным TLS listener; отказ при отсутствии STARTTLS проверен. Web production build/types и основной Chrome21/21 PASS. Android53unit/build/lint PASS; device execution исключено пользователем.

Внешняя приемка: выбранный SMTP provider, credentials, DNS/SPF/DKIM/квоты, фактическая доставка согласованному mailbox и эксплуатационные alerts в production не проверены. Test-only capture inbox не входит в API binaries и не доказывает внешнюю доставку. До этих проверок deployment registration остается выключенной по умолчанию. Подробные доказательства и source ID — STAGE-05-REPORT.md.
