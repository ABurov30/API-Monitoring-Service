# Проверки

## Требования

- JDK 17 для backend.
- Node 22.20.0 и npm 10+ для frontend.
- Docker Engine / Docker Desktop для интеграционных тестов и Compose.
- Доступ к Maven Central, npm registry и Docker Hub.
- Python 3 для smoke-проверки.

## Backend

Из корня проекта:

```sh
cd backend
./mvnw --batch-mode clean verify
```

Команда выполняет Checkstyle, Spotless, unit-тесты через Surefire, интеграционные тесты через Failsafe и проверку JaCoCo. Интеграционные тесты используют настоящие PostgreSQL и Kafka через Testcontainers. Внешний HTTP API заменён MockWebServer.

Порог покрытия — 80% строк и 80% ветвей. Отчёты:

- `backend/target/surefire-reports/` — unit-тесты.
- `backend/target/failsafe-reports/` — интеграционные тесты.
- `backend/target/site/jacoco/index.html` — общее покрытие.

Форматирование Java:

```sh
./mvnw spotless:apply
```

## Frontend

Из корня проекта:

```sh
cd frontend
npm ci
npm run lint
npm run format:check
npm run typecheck
npm test
npm run build
```

ESLint проверяет код, Prettier — форматирование, vue-tsc — типы. Vitest и Vue Test Utils проверяют авторизацию, обработку HTTP-ошибок, маршруты и компоненты. Пороги покрытия — 80% строк, ветвей, функций и выражений. HTML-отчёт: `frontend/coverage/index.html`.

## Compose и smoke

Из корня проекта:

```sh
./scripts/init-env.sh
docker compose config --quiet
docker compose up --build --wait --wait-timeout 240 postgres kafka backend frontend
docker compose run --rm --no-deps kafka-init
python3 scripts/smoke.py
```

`--wait` применяется к постоянным сервисам. `kafka-init` запускается отдельно: он создаёт топики и завершается с кодом 0. Ненулевой код означает ошибку инициализации.

Smoke проходит через Nginx `/api` и проверяет CSRF, вход USER/ADMIN, ограничения ролей, CRUD, refresh и logout. Учётные данные берутся из локального `.env`; токены и пароли не выводятся. Адрес приложения можно изменить через `SMOKE_URL`.

Для диагностики:

```sh
docker compose ps -a
docker compose logs --no-color
```

Остановка с сохранением данных:

```sh
docker compose down
```

Ручные сценарии интерфейса и отказов описаны в [manual-testing.md](manual-testing.md).

## Полный прогон и CI

```sh
./scripts/verify.sh
```

Скрипт последовательно проверяет backend и frontend, затем запускает Compose и smoke. Логи и итоговый `results.tsv` сохраняются во временном каталоге, путь печатается при запуске. Compose использует отдельное имя проекта; после проверки его контейнеры и тома удаляются. Порты 5432, 9092, 8080 и 8081 должны быть свободны.

Для отдельного Maven-репозитория и npm-кэша доступны переменные `MONITOR_MAVEN_REPO` и `MONITOR_NPM_CACHE`.

GitHub Actions выполняет те же проверки на push и pull request. Compose smoke запускается после успешных backend/frontend jobs. Отчёты тестов и покрытия сохраняются в артефактах workflow.

## Основные сценарии тестов

| Область | Сценарии |
| --- | --- |
| HTTP и retry | Исходный payload, три попытки всего, ошибки 4xx/5xx, JSON, размер ответа, таймауты, Retry-After и перенос следующего опроса |
| Kafka | Запуск контекста без брокера, параметры producer, подтверждение отправки, отказ и восстановление, оба топика, snapshot и key |
| PostgreSQL и outbox | Liquibase/validate, атомарный rollback, lease, конкурирующий claim, fencing, FAILED/replay, snapshot после CRUD |
| Безопасность | Роли, CSRF, cookie, подпись и срок JWT, refresh rotation/expiry/revoke, конкурентный refresh |
| Frontend | Login/logout, параллельные 401, старые ответы после смены сессии, CRUD-формы, маршруты и темы |
