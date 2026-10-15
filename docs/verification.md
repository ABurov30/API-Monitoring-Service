# Фактический отчёт проверки

Последний прогон: 15.10.2026; первоначальный прогон: 14.10.2026. Среда: macOS arm64, Temurin Java 17.0.20.1, Maven Wrapper 3.9.16, Node 20.19.4, npm 10.8.2.

## Исправление запуска Kafka — 15.10.2026, 16:45 MSK

Причина сбоя: пользовательский бин `ProducerFactory<String, String>` отключал создание стандартной фабрики, но не подходил для параметра `ProducerFactory<Object, Object>` в `KafkaAutoConfiguration.kafkaTemplate`. Объявление изменено на `ProducerFactory<?, ?>`, как в автоконфигурации Spring Boot 3.5. Строковые сериализаторы, idempotence, ACK и таймауты сохранены. [Исходник Spring Boot](https://github.com/spring-projects/spring-boot/blob/3.5.x/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/kafka/KafkaAutoConfiguration.java).

`KafkaConfigTest` сначала воспроизвёл эту ошибку при `./mvnw --batch-mode -Dtest=KafkaConfigTest test`, затем прошёл с исправлением. Он проверяет запуск без брокера, создание шаблона и publisher, использование единственной пользовательской фабрики и её параметры.

Полный прогон выявил ещё два сбоя: проверка CSRF по `servletPath` пропускала auth POST в MockMvc с пустым servlet path; немедленный replay зависел от сравнения часов PostgreSQL и JVM. Для CSRF использован `PathPatternRequestMatcher` по HTTP-методу и пути; проверены отсутствие токена на всех трёх auth POST и неправильный токен login. В тестовых фикстурах replay/recovery `next_attempt_at` устанавливается в уже прошедший `created_at`. Проверки настроек дополнены несовместимыми producer timeout и интервалами повторов outbox.

Итоговая команда из `backend/`:

```sh
./mvnw --batch-mode spotless:apply clean verify
```

Результат: **BUILD SUCCESS**, Surefire — 34 теста, Failsafe — 15 тестов, без ошибок и пропусков. JaCoCo: **92,26% строк (560/607), 80,97% ветвей (200/247)**; пороги 80% сохранены. Spotless и Checkstyle проходят. Testcontainers действительно запустил PostgreSQL 17.6 и Kafka 3.9.1: проверены Liquibase/validate, API через MockMvc, сессии, атомарность outbox, lease/fencing, replay, отказ producer и последующая доставка обоих топиков с исходным snapshot/key. Отчёт: `backend/target/site/jacoco/index.html`.

`docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'` выполнился. Однако `docker compose up -d --build --wait backend frontend` заблокирован sandbox: `connect: operation not permitted` для `~/.docker/run/docker.sock`. **Пересборка образов, запуск исправленного backend в Compose и UI → HTTP backend остаются непроверенными.** Доступность Docker для Maven/Testcontainers не означает доступность Compose в этом окружении. Frontend в рамках этого исправления не изменялся и повторно не проверялся.

## Выполнено успешно

| Проверка | Результат |
| --- | --- |
| npm ci из lockfile | Успешно, установка из локального кэша |
| npm run lint | ESLint без ошибок и предупреждений |
| npm run format:check | Все файлы соответствуют Prettier |
| npm run typecheck | vue-tsc без ошибок |
| npm test | 43 теста в 9 файлах проходят |
| Frontend coverage | 98,17% строк, 97,82% ветвей, 92,5% функций |
| npm run build | Production build успешен; основной JS 265,80 КБ, gzip 98,29 КБ |
| Java Spotless check + Checkstyle | Успешно, 0 нарушений Checkstyle |
| Maven Wrapper --version | Maven 3.9.16 запускается на Java 17 |
| javac --release 17 для RateClient, PollFailure, AppProperties | Успешная отдельная компиляция с HttpClient 5.5.2, Spring 6.2.19 / Boot 3.5.16 и Jackson 2.21.4 из кэша |
| Отдельный ConfigurationTest на Java 17 | 3 теста прошли через JUnit Platform 1.12.2 / Jupiter 5.12.2 с Hibernate Validator 8.0.3.Final; это не Maven verify |
| docker compose config --quiet | Конфигурация корректна |
| sh -n scripts/init-env.sh | Синтаксис корректен |
| python3 -m py_compile scripts/smoke.py | Синтаксис корректен |
| Генерация локального .env | Создан с правами 600; секреты не выводились и не включены в исходники |

Команды frontend выполнялись из `frontend/`. В первоначальном прогоне npm использовал `--offline --cache /tmp/monitor-npm`; в повторном — `npm ci --cache /tmp/monitor-npm` с подготовленным кэшем, затем `npm run lint`, `npm run format:check`, `npm run typecheck`, `npm test`, `npm run build`. Успешная установка не подтверждает доступность registry из shell. Недостающие метаданные локального кэша ранее восстановлены из уже имеющихся оригинальных tarball/package.json; lockfile содержит публичные registry.npmjs.org URL и исходные integrity, без локальных путей. Linux arm64/x64 optional binaries присутствуют в lockfile.

Java-линтеры запускались с доступным локальным репозиторием:

```sh
cd backend
./mvnw -o --batch-mode -Dmaven.repo.local=/tmp/monitor-m2 spotless:apply spotless:check checkstyle:check
```

До исправления Kafka для отдельного запуска `ConfigurationTest` использован временный диагностический runner `/tmp/monitor-config-check.py`: `python3 /tmp/monitor-config-check.py`. Он компилировал `AppProperties`, `DataRequest` и существующий `ConfigurationTest` через `javac --release 17`, затем запускал этот класс через JUnit Launcher. Полная backend-проверка теперь выполнена Maven, см. новый прогон выше.

## Исправления при продолжении задания

- Два регрессионных теста сначала воспроизвели повторный refresh при запоздавшем 401 и приход login-cookie после logout. После исправлений они проходят.
- Auth-запросы и CSRF bootstrap выполняются последовательно; после смены сессии login не отправляет оставшийся POST. Logout отзывает cookie уже отправленного login/refresh и не очищает последующий вход.
- После refresh старый HTTP-запрос не повторяется в другой сессии; подтверждение удаления и завершение CRUD-формы не возвращают действия и данные прежнего пользователя.
- Валидация backend-настроек отклоняет URL без HTTP(S)/host или с credentials, неверный cron/time zone, слишком короткие TTL и интервалы, превышение диапазона сетевых timeout.

## Ограничения среды и непроверенное поведение

1. Ранние попытки `./mvnw --batch-mode clean verify` блокировались записью в Maven-кэш и DNS. В последнем прогоне Maven скачал зависимости, скомпилировал backend и успешно выполнил Surefire, Failsafe и JaCoCo. Эти прежние ограничения больше не блокируют backend verify.
2. `docker compose up -d --build --wait backend frontend` по-прежнему получает `connect: operation not permitted` для Docker-сокета. Compose, full-stack smoke и Docker image builds **не проверены**. PostgreSQL/Liquibase и настоящая Kafka теперь проверены через Maven/Testcontainers.
3. Ранний отдельный HTTP-harness блокировался `SocketException: Operation not permitted`. В последнем Maven-прогоне сетевые retry/timeout-тесты MockWebServer выполнены успешно.
4. Скриншоты реального dashboard, мобильной версии и Swagger не создавались. Изображения с подменённым backend не использовались для отчёта.
5. CI-конфигурация создана, но GitHub Actions не запускался: проект не публиковался в удалённый репозиторий.

**Пока не закрыты:** запуск Compose, full-stack smoke и ручная проверка интерфейса. Backend-сборка, пороги 80% coverage и интеграционные тесты с PostgreSQL/Kafka подтверждены.

## Подготовленные backend-сценарии

- MockWebServer: исходный payload, 3 попытки всего, неповторяемые ошибки, JSON, размер, read timeout, цикл, Retry-After в секундах/HTTP-date/невалидном формате, перенос следующего тика.
- Mockito: SENT только после завершённого Kafka future, отказ отправки, выключенный publisher и ошибка БД.
- PostgreSQL Testcontainers: Liquibase/validate, CRUD и total, UTF-8/HTTP body limits, роли, CSRF/cookie, настоящая JWT-аутентификация, refresh rotation/expiry/revoke/concurrent exchange, атомарный rollback, lease, конкурирующий claim, старый lock_token, FAILED/replay, snapshot после CRUD.
- Kafka Testcontainers: настоящий недоступный producer endpoint → сохранение PENDING → доставка через работающий Kafka producer → consumer получает оба топика и исходный event snapshot/key.
- JWT unit: подпись, issuer, audience, срок действия, слабый ключ.
- Конфигурация и OpenAPI-контракт.

## Дальнейший прогон в среде с сетью и Docker

```sh
cd backend
./mvnw --batch-mode clean verify
cd ../frontend
npm ci
npm run lint && npm run format:check && npm run typecheck && npm test && npm run build
cd ..
./scripts/init-env.sh
docker compose up --build --wait
python3 scripts/smoke.py
```

После запуска Compose дополнить отчёт результатами smoke и ручной проверки из manual-testing.md. Пороги coverage и обязательные тесты не отключать.
