# Синтетические уведомления

Пример для parser v1: пакет `example.bank`, шаблон `Покупка {amount} {currency} в {merchant}. Карта *{card}`, RUB/expense; строка `Покупка 1 234,56 RUB в SHOP. Карта *1234` → amount_minor123456, merchantSHOP, last41234. Это вымышленный формат, не образец реального банка.

Исполняемые positive/negative fixtures: android/app/src/test/java/com/capital/accounting/NotificationParserTest.kt. Проверяются нулевая/избыточная точность, группировка, OTP/PAN, скрытый/групповой текст, сторонний пакет, replay и две одинаковые покупки. Полные уведомления не предназначены для хранения или журналирования.
