# Описание классов: bio4j-api-gateway

Пакет: `ru.sber.bio4j.apigateway`

## Диаграмма компонентов

```
┌─────────────────────────────────────────────────────────────────────┐
│                        API Gateway (WebFlux)                        │
│                                                                     │
│  ┌──────────────┐    ┌──────────────────────────────────────────┐  │
│  │ SecurityConfig│───▶│       SecurityWebFilterChain              │  │
│  └──────┬───────┘    │  ┌─────────────┐ ┌──────────────────────┐│  │
│         │            │  │ JWT Decoder  │ │ JwtClaimsUserValid.  ││  │
│         │            │  │ (RS256)      │ │ WebFilter            ││  │
│         │            │  └─────────────┘ └──────────┬───────────┘│  │
│         │            └─────────────────────────────┼────────────┘  │
│         │                                          │               │
│  ┌──────┴────────┐                    ┌────────────▼────────────┐  │
│  │ CorsConfig    │                    │ UserProfileCacheService │  │
│  │ KeyResolver   │                    │ (Caffeine + Redis)      │  │
│  └───────────────┘                    └────────────┬────────────┘  │
│                                                    │               │
│  ┌───────────────────────┐            ┌────────────▼────────────┐  │
│  │ TokenRevocationService│◀───────────│ GatewayUserRepository   │  │
│  │ (Redis blacklist)     │            │ (R2DBC)                 │  │
│  └───────────────────────┘            └─────────────────────────┘  │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ JsonAuthenticationEntryPoint ──▶ UnauthorizedResponseWriter   │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## `ApiGatewayApplication`

**Пакет:** `ru.sber.bio4j.apigateway`
**Тип:** класс (точка входа)

Точка входа Spring Boot приложения. Аннотирован `@SpringBootApplication`.

---

## Пакет `security`

### `SecurityConfig`

**Тип:** `@Configuration`, `@EnableWebFluxSecurity`

Центральная конфигурация безопасности. Определяет:

| Бин | Назначение |
|-----|-----------|
| `SecurityWebFilterChain` | Цепочка фильтров: отключены CSRF/httpBasic/formLogin; включены CORS, security-заголовки (Referrer-Policy, Permissions-Policy), OAuth2 Resource Server (JWT); `JwtClaimsUserValidationWebFilter` добавлен после `AUTHENTICATION`. Путь `/actuator/health/**` открыт. |
| `ReactiveJwtDecoder` | Декодер JWT на базе RSA public key (RS256). Ключ загружается из PEM-файла по пути `security.jwt.public-key-location`. |
| `CorsConfigurationSource` | CORS-политика, настраиваемая через `app.cors.*`. Если `allowed-origins` пуст — CORS не применяется. |
| `KeyResolver` | Определяет ключ для rate limiter: имя аутентифицированного пользователя (principal), fallback — IP-адрес клиента. |

**Приватные методы:**

- `loadRsaPublicKey(Resource)` — парсит PEM-файл, извлекает `RSAPublicKey`.
- `splitCsv(String)` — разбивает CSV-строку в `List<String>`.

---

### `JwtClaimsUserValidationWebFilter`

**Тип:** `@Component`, реализует `WebFilter`

Основной фильтр бизнес-логики аутентификации. Выполняется **после** стандартной JWT-валидации Spring Security.

**Поток обработки:**

1. Получает `Authentication` из `ReactiveSecurityContextHolder`.
2. Проверяет, что principal — `Jwt` с claim `sberpdi`.
3. Определяет `sessionId` из JWT (валидация UUID).
4. Проверяет отзыв токена через `TokenRevocationService`.
5. Ищет профиль в кеше (`UserProfileCacheService.findBySessionId`).
6. При промахе — загружает из БД, сохраняет в кеш (fire-and-forget).
7. Добавляет `X-Session-Id` в downstream-запрос.

**Поля:**

| Поле | Тип | Описание |
|------|-----|---------|
| `gatewayUserRepository` | `GatewayUserRepository` | Доступ к БД пользователей |
| `unauthorizedResponseWriter` | `UnauthorizedResponseWriter` | Формирование 401 ответа |
| `userProfileCacheService` | `UserProfileCacheService` | Двухуровневый кеш профилей |
| `tokenRevocationService` | `TokenRevocationService` | Проверка отзыва токенов |
| `sessionIdHeaderName` | `String` | Имя заголовка сессии (настраиваемое) |

**Ключевые методы:**

| Метод | Описание |
|-------|---------|
| `filter(exchange, chain)` | Точка входа WebFilter; делегирует в `validateUser`. |
| `validateUser(auth, exchange, chain)` | Полный цикл проверки: claim → revocation → cache → DB → forward. |
| `checkRevocation(jwt)` | Проверяет `jti` в Redis-blacklist. |
| `resolveProfile(login, sessionId)` | Cache-first: Caffeine → Redis → DB. |
| `loadFromDbAndCache(login, sessionId)` | Загрузка из БД + fire-and-forget сохранение в кеш. |
| `forwardRequest(sessionId, exchange, chain)` | Мутирует exchange: добавляет `X-Session-Id`, передаёт дальше по цепочке. |
| `resolveSessionId(jwt)` | Приоритет: `sid` → `jti` → новый UUID. Валидирует как UUID. |
| `toProfile(user)` | Конвертирует `GatewayUser` → `GatewayUserProfile`. |
| `parseCsv(raw)` | Разбирает CSV-строку ролей/грантов. |

---

### `UserProfileCacheService`

**Тип:** `@Service`

Двухуровневый кеш профилей пользователей.

| Уровень | Хранилище | TTL | Размер |
|---------|----------|-----|--------|
| L1 | Caffeine (in-memory) | 30 сек | до 10 000 записей |
| L2 | Redis | 30 мин | не ограничен |

**Методы:**

| Метод | Описание |
|-------|---------|
| `findBySessionId(sessionId)` | Ищет профиль: Caffeine → Redis → `Mono.empty()`. При попадании в Redis — дополнительно записывает в Caffeine. |
| `saveBySessionId(sessionId, profile)` | Записывает в Caffeine и Redis (с TTL). Сериализует через Jackson. |

**Ключ Redis:** `session:user-profile:{sessionId}`
**Формат значения:** JSON (`GatewayUserProfile`).

---

### `TokenRevocationService`

**Тип:** `@Service`

Redis-blacklist для отозванных JWT-токенов.

| Метод | Описание |
|-------|---------|
| `isRevoked(jti)` | Проверяет наличие ключа `revoked-token:{jti}` в Redis. Возвращает `Mono<Boolean>`. |
| `revoke(jti, ttl)` | Записывает ключ `revoked-token:{jti}` со значением `"revoked"` и указанным TTL. |

**Ключ Redis:** `{keyPrefix}{jti}` (по умолчанию `revoked-token:{jti}`).

---

### `JsonAuthenticationEntryPoint`

**Тип:** `@Component`, реализует `ServerAuthenticationEntryPoint`

Обрабатывает ошибки аутентификации на уровне Spring Security (отсутствие токена, невалидная подпись, истечение срока).

Делегирует формирование JSON-ответа в `UnauthorizedResponseWriter`. Всегда использует единое сообщение `"Authentication failed"` без раскрытия деталей.

---

### `UnauthorizedResponseWriter`

**Тип:** `@Component`

Формирует HTTP 401 JSON-ответ.

**Метод `write(exchange, message)`:**

1. Проверяет, не был ли ответ уже отправлен (`response.isCommitted()`).
2. Устанавливает статус `401`, Content-Type `application/json`.
3. Формирует JSON-тело: `timestamp`, `status`, `error`, `message`, `path`.
4. Сериализует через Jackson, при ошибке — возвращает минимальный JSON.

---

## Пакет `user`

### `GatewayUser`

**Тип:** `@Table("gateway_users")` — R2DBC entity

Сущность таблицы `gateway_users` в PostgreSQL.

| Поле | Тип | Колонка | Описание |
|------|-----|---------|---------|
| `id` | `Long` | `id` | PK, auto-generated |
| `login` | `String` | `login` | Логин пользователя (уникальный) |
| `active` | `Boolean` | `active` | Активен ли пользователь |
| `roles` | `String` | `roles` | CSV-строка ролей (`ROLE_USER,ROLE_ANALYST`) |
| `grants` | `String` | `user_grants` | CSV-строка грантов (`grant.read,grant.export`) |

---

### `GatewayUserProfile`

**Тип:** Java `record`

Неизменяемый объект профиля пользователя для кеширования и передачи downstream-сервисам.

| Поле | Тип | Описание |
|------|-----|---------|
| `id` | `Long` | ID пользователя |
| `login` | `String` | Логин |
| `roles` | `List<String>` | Список ролей |
| `grants` | `List<String>` | Список грантов |

Сериализуется/десериализуется Jackson (JSON) для хранения в Redis.

---

### `GatewayUserRepository`

**Тип:** интерфейс, расширяет `ReactiveCrudRepository<GatewayUser, Long>`

| Метод | Описание |
|-------|---------|
| `findByLoginAndActiveTrue(login)` | Ищет активного пользователя по логину. Возвращает `Mono<GatewayUser>` (пустой если не найден). |

---

## Зависимости между компонентами

```
SecurityConfig
  ├── создаёт → ReactiveJwtDecoder (RS256)
  ├── создаёт → SecurityWebFilterChain
  │     ├── использует → JsonAuthenticationEntryPoint
  │     └── использует → JwtClaimsUserValidationWebFilter
  ├── создаёт → CorsConfigurationSource
  └── создаёт → KeyResolver (для RateLimiter)

JwtClaimsUserValidationWebFilter
  ├── использует → TokenRevocationService
  ├── использует → UserProfileCacheService
  ├── использует → GatewayUserRepository
  └── использует → UnauthorizedResponseWriter

UserProfileCacheService
  ├── использует → ReactiveStringRedisTemplate
  ├── использует → ObjectMapper (Jackson)
  └── содержит  → Cache<String, GatewayUserProfile> (Caffeine)

TokenRevocationService
  └── использует → ReactiveStringRedisTemplate

JsonAuthenticationEntryPoint
  └── делегирует → UnauthorizedResponseWriter

UnauthorizedResponseWriter
  └── использует → ObjectMapper (Jackson)
```
