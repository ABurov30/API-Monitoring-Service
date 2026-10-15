# Конфигурация

Spring relaxed binding: `app.monitor.max-response-bytes` → `APP_MONITOR_MAX_RESPONSE_BYTES`. Длительности принимают суффиксы `ms`, `s`, `m`, `d`. Все группы — валидируемые `@ConfigurationProperties`; неверные интервалы, лимиты и короткий JWT-ключ останавливают старт.

| Ключ | По умолчанию |
| --- | --- |
| app.monitor.enabled | true (false в test) |
| app.monitor.cron / zone | `0 * * * * *` / UTC |
| app.monitor.url | https://api.frankfurter.dev/v2/rates?base=EUR&quotes=USD,GBP,CHF |
| app.monitor.connect-timeout / read-timeout | 2s / 5s |
| app.monitor.request-timeout / cycle-timeout | 7s / 50s |
| app.monitor.max-response-bytes | 65536 |
| app.monitor.retry.max-attempts | 3 |
| app.monitor.retry.initial-delay / multiplier / max-delay | 1s / 2 / 30s |
| app.outbox.enabled / poll-delay | true / 1s |
| app.outbox.max-events-per-tick / max-attempts | 10 / 20 |
| app.outbox.initial-delay / multiplier / max-delay | 5s / 2 / 5m |
| app.outbox.lease / send-timeout | 60s / 25s |
| app.outbox.max-block / request-timeout / delivery-timeout | 5s / 10s / 20s |
| app.auth.access-ttl / refresh-ttl | 15m / 7d |
| app.auth.cookie-secure | true; false только local |
| app.auth.issuer / audience | api-monitor / monitor-ui |
| app.auth.secret | JWT_SECRET, Base64 минимум 32 случайных байта |
| app.cors-origins | пустой список; только явно заданные origins |

Producer всегда `acks=all`, `enable.idempotence=true`, `linger.ms=5`. Его сетевые лимиты берутся из `app.outbox`, чтобы валидировать их вместе с lease. Требуется delivery ≥ request + linger, send ≥ delivery, lease > maxBlock + send + 5s. Kafka ACK-ожидание и блокировка producer проходят вне БД-транзакции.

Интервалы и сетевые таймауты должны быть не меньше 1ms; access/refresh TTL — не меньше 1s, поскольку JWT и cookie используют секунды. Длительности ограничены диапазоном преобразования в наносекунды. Kafka request/delivery timeout дополнительно ограничены `Integer.MAX_VALUE` миллисекунд. Это исключает округление до нуля и переполнение при настройке транспорта. URL опроса должен быть абсолютным HTTP(S)-адресом с host и без username/password; cron и time zone проверяются при валидации настроек.

`DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `MONITOR_URL` поддержаны напрямую. Локальные значения DB URL/user и Kafka bootstrap — localhost/monitor и localhost:9092. В Compose используются postgres:5432 и kafka:19092. Внешний Kafka listener — localhost:9092.

`DEMO_USER_PASSWORD`/`DEMO_ADMIN_PASSWORD` обязательны только при local, минимум 12 символов. Продакшен-профиль не создаёт аккаунты и требует отдельного provisioning. TLS завершается на доверенном reverse proxy; Secure-cookie остаётся включённой.

Frontend `VITE_API_URL` задаётся на этапе сборки. Proxy по умолчанию обеспечивает один origin. Vite сохраняет Host (`changeOrigin=false`), Nginx передаёт `$http_host` вместе с портом; это предотвращает ложное отклонение same-origin POST через CORS. Refresh/CSRF cookie имеют Path=/ и работают также при прямом документированном доступе к backend.

## Проверенные источники

- [Spring Boot 3.5: Java 17 и системные требования](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
- [Spring Security 6.5: CSRF и SPA](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html).
- [Spring Retry RetryTemplate](https://docs.spring.io/spring-retry/docs/api/current/org/springframework/retry/support/RetryTemplate.html): retry выполняется с отдельным backoff policy; текущая ссылка API у сайта может показывать старую версию, сигнатуры необходимо подтвердить полной Maven-сборкой.
- [Frankfurter v2](https://frankfurter.dev/): публичный источник без ключа.

Невыполненные проверки доступности образов и Maven-компиляции перечислены в verification.md.

Дополнительно сверены [исходники Spring Retry v2.0.13](https://github.com/spring-attic/spring-retry/blob/v2.0.13/src/main/java/org/springframework/retry/support/RetryTemplate.java), [матрица Springdoc](https://springdoc.org/), [требования Vite к Node](https://vite.dev/guide/) и API Apache HttpClient. HTTP-клиент отдельно скомпилирован javac на Java 17 с HttpClient 5.5.2; это не заменяет полную Maven-сборку.
