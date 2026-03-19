# Use Cases: bio4j-api-gateway

## UC-01. Авторизованный запрос в целевой микросервис

- **Цель:** пропустить запрос в downstream-сервис только для валидного пользователя.
- **Акторы:** клиент (frontend/сервис), API Gateway, Caffeine-кеш, Redis, БД пользователей, целевой микросервис.
- **Предусловия:**
  - клиент отправляет JWT в `Authorization: Bearer <token>`;
  - JWT подписан RS256 (асимметричный);
  - в JWT есть claim `sberpdi` с логином;
  - пользователь с таким `login` существует в БД и активен;
  - токен не отозван.
- **Основной поток:**
  1. Клиент отправляет REST-запрос в gateway.
  2. Spring Security валидирует JWT (подпись RS256, срок действия).
  3. `JwtClaimsUserValidationWebFilter` извлекает claim `sberpdi` из JWT.
  4. Фильтр проверяет, не отозван ли токен (`TokenRevocationService` → Redis-ключ `revoked-token:{jti}`).
  5. Фильтр определяет `sessionId` (приоритет: JWT `sid` → JWT `jti` → новый UUID; валидация как UUID).
  6. Фильтр ищет профиль в кеше (`UserProfileCacheService`):
     - L1: Caffeine (in-memory, TTL ~30 сек) → при попадании — шаг 9.
     - L2: Redis (ключ `session:user-profile:{sessionId}`, TTL 30 мин) → при попадании — шаг 9.
  7. При промахе кеша — загрузка из БД: `GatewayUserRepository.findByLoginAndActiveTrue(login)`.
  8. Профиль сохраняется в Caffeine и Redis (fire-and-forget, не блокирует downstream).
  9. Gateway добавляет заголовок `X-Session-Id: {sessionId}` в downstream-запрос.
  10. Spring Cloud Gateway применяет фильтры маршрута (CircuitBreaker, Retry, RateLimiter).
  11. Запрос маршрутизируется в целевой сервис.
- **Постусловия:**
  - целевой сервис получает `X-Session-Id`;
  - профиль пользователя доступен в Redis по `sessionId`.

## UC-02. JWT отсутствует

- **Цель:** отклонить неавторизованный запрос.
- **Предусловия:** отсутствует заголовок `Authorization` или формат Bearer некорректен.
- **Поток:**
  1. Клиент отправляет запрос без корректного Bearer-токена.
  2. Spring Security не находит токен.
  3. `JsonAuthenticationEntryPoint` возвращает `401` JSON (`Authentication failed`).
- **Результат:** запрос не маршрутизируется в downstream.

## UC-03. JWT невалиден или истек

- **Цель:** не пропускать запросы с недействительным токеном.
- **Предусловия:** JWT имеет неверную подпись RS256 или `exp` в прошлом.
- **Поток:**
  1. Spring Security выполняет валидацию JWT.
  2. Валидация завершается ошибкой.
  3. `JsonAuthenticationEntryPoint` возвращает `401` JSON (`Authentication failed`).
- **Результат:** запрос блокируется.

## UC-04. В JWT нет claim `sberpdi`

- **Цель:** гарантировать наличие идентификатора пользователя.
- **Предусловия:** JWT валиден, но claim `sberpdi` отсутствует или пуст.
- **Поток:**
  1. Spring Security валидирует JWT (ОК).
  2. `JwtClaimsUserValidationWebFilter` пытается извлечь `sberpdi`.
  3. Claim отсутствует.
  4. Фильтр возвращает `401` JSON (`Authentication failed`).
- **Результат:** запрос блокируется.

## UC-05. Пользователь не найден в БД или неактивен

- **Цель:** пропускать только зарегистрированных и активных пользователей.
- **Предусловия:** JWT валиден и содержит `sberpdi`, профиль отсутствует в кеше, запись в БД не найдена или `active=false`.
- **Поток:**
  1. Фильтр извлекает `sberpdi`.
  2. Caffeine и Redis не содержат профиль для данного `sessionId`.
  3. `GatewayUserRepository.findByLoginAndActiveTrue(login)` возвращает пустой результат.
  4. Фильтр возвращает `401` JSON (`Authentication failed`).
- **Результат:** запрос блокируется.

## UC-06. Получение профиля на целевом сервисе

- **Цель:** целевой сервис получает профиль пользователя из Redis.
- **Акторы:** целевой микросервис, Redis.
- **Предусловия:**
  - в запросе есть заголовок `X-Session-Id`;
  - в Redis существует ключ `session:user-profile:{sessionId}`.
- **Поток:**
  1. Целевой сервис читает заголовок `X-Session-Id`.
  2. Формирует Redis-ключ `session:user-profile:{sessionId}`.
  3. Загружает JSON-профиль из Redis.
  4. Использует `roles` и `grants` для авторизации бизнес-операций.
- **Альтернативный поток:**
  - если ключ отсутствует (TTL истёк), сервис может вернуть `401/403` или запросить ре-авторизацию (зависит от политики сервиса).

## UC-07. Отзыв скомпрометированного токена

- **Цель:** заблокировать использование скомпрометированного JWT до истечения его `exp`.
- **Акторы:** администратор / сервис безопасности, Redis, API Gateway.
- **Предусловия:** известен `jti` скомпрометированного токена.
- **Поток:**
  1. Администратор записывает в Redis ключ `revoked-token:{jti}` со значением `revoked` и TTL, равным оставшемуся времени жизни токена.
  2. Клиент отправляет запрос с отозванным JWT.
  3. `JwtClaimsUserValidationWebFilter` проверяет `jti` через `TokenRevocationService`.
  4. `TokenRevocationService` находит ключ `revoked-token:{jti}` в Redis.
  5. Фильтр возвращает `401` JSON (`Authentication failed`).
- **Результат:** запрос блокируется, даже если JWT формально не просрочен.

## UC-08. Ограничение частоты запросов (Rate Limiting)

- **Цель:** защитить downstream-сервисы от перегрузки.
- **Предусловия:** клиент превысил лимит запросов.
- **Поток:**
  1. Клиент отправляет запрос.
  2. `RequestRateLimiter` (Redis Token Bucket) проверяет лимит для ключа (principal или IP).
  3. Лимит превышен.
  4. Gateway возвращает `429 Too Many Requests`.
- **Параметры по умолчанию:** 50 запросов/сек, burst до 100.

## UC-09. Срабатывание Circuit Breaker

- **Цель:** предотвратить каскадные сбои при недоступности downstream-сервиса.
- **Предусловия:** доля ошибок downstream превысила порог (50% из последних 10 запросов).
- **Поток:**
  1. Клиент отправляет запрос.
  2. CircuitBreaker в состоянии OPEN.
  3. Gateway немедленно возвращает `503 Service Unavailable` без обращения к downstream.
  4. Через 10 сек CircuitBreaker переходит в HALF-OPEN, пропускает 3 пробных запроса.
  5. Если пробные запросы успешны — CLOSED (нормальная работа).
- **Retry:** GET-запросы автоматически повторяются до 3 раз при `502/503/504` с экспоненциальным backoff (100–500 мс).

## Формат ответа об ошибке авторизации

Во всех негативных сценариях (UC-02 .. UC-07) gateway возвращает:

- HTTP статус: `401 Unauthorized`
- Content-Type: `application/json`
- JSON:

```json
{
  "timestamp": "2026-03-19T10:15:30.135Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication failed",
  "path": "/user/profile"
}
```

Сообщение всегда `"Authentication failed"` — внутренняя причина отказа не раскрывается.
