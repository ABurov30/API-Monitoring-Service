# HTTP-контракт

Backend root — `/`; UI root — `/api` через proxy. JSON по UTF-8.

| Метод и путь | Роль | Успех |
| --- | --- | --- |
| GET /status | USER, ADMIN | 200 `{status:"UP",checkedAt:Instant}` |
| GET /data | ADMIN | 200 `{items:Data[],total:number}` |
| GET /data/{uuid} | ADMIN | 200 Data |
| POST /data | ADMIN | 201 Data, Location |
| PUT /data/{uuid} | ADMIN | 200 Data |
| DELETE /data/{uuid} | ADMIN | 204 |
| GET /auth/csrf | публичный | 200 `{token,headerName}` + cookie |
| POST /auth/login | публичный + CSRF | 200 Session + refresh cookie |
| POST /auth/refresh | refresh cookie + CSRF | 200 Session + новая cookie |
| POST /auth/logout | CSRF | 204, отзыв cookie |

Data: `{id:UUID,createdAt:Instant,success:boolean,payload:string}`. GET /data возвращает максимум 10, сортировка `created_at DESC,id DESC`, total считает всю таблицу. USER через status не получает ни counts, ни payload.

POST/PUT принимают **только** `{success:boolean,payload:string}`. False допустим. Оба обязательны; payload непустой до 65536 байт UTF-8. `id`, `createdAt`, опечатки и неизвестные поля отклоняются с 400. PUT сохраняет UUID/createdAt. Ручной payload может быть обычным текстом. Общий предел тела запроса на backend — 524288 байт (JSON escaping может быть существенно больше строки).

Login: `{username,password}`. Session: `{accessToken,tokenType:"Bearer",expiresIn:900,user:{id,username,roles}}`. Refresh не возвращается в JSON. GET csrf нужно повторить после успешной смены auth-состояния; Axios делает это централизованно. Refresh-сессия cookie-based и не служит аутентификацией CRUD.

Ошибка: `{code:"HTTP_400",message:"...",timestamp:Instant,path:"/data",fieldErrors?:{field:message}}`.

- 400: JSON, UUID, неизвестные поля и валидация.
- 401: отсутствует/невалиден/истёк JWT либо refresh.
- 403: недостаточная роль или CSRF.
- 404: отсутствующая запись.
- 413: превышен общий предел тела.
- 503: недоступна необходимая БД.
- 500: непредвиденная ошибка без технических деталей.

Security filter errors тоже имеют JSON-формат. Payload отображается как текст, без v-html. Status отражает доступность HTTP endpoint, а не здоровье источника/Kafka.
