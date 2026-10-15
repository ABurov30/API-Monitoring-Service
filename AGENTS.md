# Project instructions

Источник требований: [AGENT_TASK.md](AGENT_TASK.md). Запуск и стек: [README.md](README.md).
Архитектура: [docs/architecture.md](docs/architecture.md), API: [docs/api.md](docs/api.md), настройки: [docs/configuration.md](docs/configuration.md), проверки: [docs/verification.md](docs/verification.md).

- Java 17, Spring Boot 3.x, Vue 3; не мигрировать на Boot 4.
- HTTP/backoff и Kafka send вне DB-транзакций; результат и outbox атомарны.
- Сохранять snapshot/eventId, lease и fencing lock_token. Ручной CRUD не публикует события.
- USER только /status; auth POST защищён CSRF, CRUD принимает Bearer.
- Не хранить токены в web storage, не возвращать данные старой сессии после logout.
- Не коммитить .env, реальные секреты, target, node_modules, coverage.
- Backend: `cd backend && ./mvnw --batch-mode clean verify`. Формат: `./mvnw spotless:apply`.
- Frontend: `cd frontend && npm ci && npm run lint && npm run format:check && npm run typecheck && npm test && npm run build`.
- Интеграционные тесты требуют Docker и используют настоящие PostgreSQL/Kafka. Не подменять их H2 и не отключать из-за отсутствующего Docker.
- Исправлять ошибки перед завершением работы. Не отключать правила/тесты/coverage-пороги ради зелёного результата.
- Если среда блокирует проверку, явно записать команду, причину и непроверенное поведение в docs/verification.md; не утверждать, что тесты прошли.
