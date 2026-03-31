# bio4j-api-gateway

API Gateway на Spring Boot `3.5.11`, Spring Cloud Gateway `2025.0.0` и Java `21` для Kubernetes.

Документация:

- [Use Cases](docs/use-cases.md) — сценарии использования
- [Описание классов](docs/classes.md) — архитектура и ответственность каждого компонента
- [Диаграмма последовательности](docs/sequence.md) — взаимодействие компонентов
- [Рекомендации](docs/recomendations.md) — выполненные улучшения

## Что делает сервис

- Принимает REST-запросы и маршрутизирует их в downstream-сервисы.
- Проверяет JWT токен (RS256, асимметричный ключ).
- Проверяет, что токен не отозван (Redis-blacklist по `jti`).
- Извлекает claim `sberpdi` (логин пользователя).
- Ищет профиль в двухуровневом кеше (Caffeine → Redis), при промахе — загружает из БД.
- Кеширует профиль в Redis по ключу `session:user-profile:{sessionId}`.
- Пробрасывает `sessionId` в downstream через заголовок `X-Session-Id`.
- Применяет rate limiting, circuit breaker и retry к каждому маршруту.
- Возвращает `401` JSON при любой ошибке аутентификации (единое сообщение без раскрытия деталей).

## Стек технологий

| Компонент | Технология |
|-----------|-----------|
| Framework | Spring Boot 3.5.11 |
| Gateway | Spring Cloud Gateway 2025.0.0 |
| Security | Spring Security OAuth2 Resource Server (RS256 JWT) |
| БД | PostgreSQL (R2DBC, реактивный) |
| Кеш (distributed) | Redis (Lettuce, реактивный) |
| Кеш (local) | Caffeine |
| Устойчивость | Resilience4j (CircuitBreaker, Retry, TimeLimiter) |
| Rate Limiting | Spring Cloud Gateway + Redis |
| Java | 21 |

## Быстрый запуск (локально)

### 1. Подготовка RSA-ключей

Сгенерировать пару ключей:

```bash
openssl genpkey -algorithm RSA -out jwt-private-key.pem -pkeyopt rsa_keygen_bits:2048
openssl rsa -pubout -in jwt-private-key.pem -out jwt-public-key.pem
```

Положить `jwt-public-key.pem` в `src/main/resources/` или указать путь через переменную окружения.

### 2. Переменные окружения

```powershell
$env:SECURITY_JWT_PUBLIC_KEY_LOCATION="file:./jwt-public-key.pem"
$env:SPRING_DATA_REDIS_PASSWORD="your-redis-password"
```

### 3. Запуск

```powershell
gradle bootRun
```

### 4. Проверка health

```powershell
curl http://localhost:8080/actuator/health
```

## Конфигурация

### Переменные окружения

| Переменная | Описание | По умолчанию |
|-----------|----------|-------------|
| `SERVER_PORT` | Порт сервера | `8080` |
| `SECURITY_JWT_PUBLIC_KEY_LOCATION` | Путь к RSA public key (PEM) | `classpath:jwt-public-key.pem` |
| `SPRING_DATA_REDIS_HOST` | Хост Redis | `localhost` |
| `SPRING_DATA_REDIS_PORT` | Порт Redis | `6379` |
| `SPRING_DATA_REDIS_PASSWORD` | Пароль Redis | **обязателен** |
| `SPRING_R2DBC_URL` | R2DBC URL базы данных | `r2dbc:postgresql://localhost:5432/gatewaydb` |
| `SPRING_R2DBC_USERNAME` | Пользователь БД | `gateway` |
| `SPRING_R2DBC_PASSWORD` | Пароль БД | _(пустой)_ |
| `ROUTE_USER_SERVICE_URI` | URI user-сервиса | `http://localhost:8081` |
| `ROUTE_BILLING_SERVICE_URI` | URI billing-сервиса | `http://localhost:8082` |
| `APP_SESSION_HEADER_NAME` | Имя заголовка сессии | `X-Session-Id` |
| `APP_PROFILE_CACHE_TTL` | TTL профиля в Redis | `30m` |
| `APP_PROFILE_CACHE_LOCAL_MAX_SIZE` | Макс. записей в Caffeine | `10000` |
| `APP_PROFILE_CACHE_LOCAL_TTL` | TTL профиля в Caffeine | `30s` |
| `APP_CORS_ALLOWED_ORIGINS` | Разрешённые CORS origins | _(не задан — CORS отключён)_ |
| `RATE_LIMITER_REPLENISH_RATE` | Запросов/сек на пользователя | `50` |
| `RATE_LIMITER_BURST_CAPACITY` | Макс. burst запросов | `100` |

### R2DBC Connection Pool

| Переменная | По умолчанию |
|-----------|-------------|
| `R2DBC_POOL_INITIAL_SIZE` | `5` |
| `R2DBC_POOL_MAX_SIZE` | `20` |
| `R2DBC_POOL_MAX_IDLE_TIME` | `30m` |
| `R2DBC_POOL_MAX_LIFE_TIME` | `60m` |

### Redis Lettuce Pool

| Переменная | По умолчанию |
|-----------|-------------|
| `REDIS_POOL_MIN_IDLE` | `2` |
| `REDIS_POOL_MAX_IDLE` | `8` |
| `REDIS_POOL_MAX_ACTIVE` | `16` |

## Роутинг

| Путь | Downstream | Фильтры |
|------|-----------|---------|
| `/user/**` | `ROUTE_USER_SERVICE_URI` | StripPrefix, CircuitBreaker, Retry, RateLimiter |
| `/billing/**` | `ROUTE_BILLING_SERVICE_URI` | StripPrefix, CircuitBreaker, Retry, RateLimiter |

Default-фильтры для всех маршрутов: `RemoveRequestHeader=Cookie`, `RemoveRequestHeader=X-Session-Id`.

## JWT требования

- Подписан **RS256** (асимметричный RSA).
- Gateway хранит только **публичный ключ** (PEM).
- Содержит claim `sberpdi` с логином пользователя.
- Не должен быть просрочен (`exp`).
- `sessionId` определяется по приоритету: JWT claim `sid` → JWT `jti` → новый UUID.
- Значения `sid` и `jti` валидируются как UUID.

## Двухуровневый кеш профиля

```
Запрос → [Caffeine (30с)] → [Redis (30м)] → [PostgreSQL] → сохранение в Redis + Caffeine
```

- **Caffeine** — in-memory, до 10 000 записей, TTL 30 сек.
- **Redis** — ключ `session:user-profile:{sessionId}`, TTL 30 мин.
- Запись в Redis выполняется fire-and-forget (не блокирует downstream).

## Отзыв токенов

Для блокировки скомпрометированного JWT:

- В Redis записывается ключ `revoked-token:{jti}` с TTL, равным оставшемуся времени жизни токена.
- При каждом запросе gateway проверяет наличие ключа.

## Устойчивость (Resilience4j)

| Параметр | Значение |
|----------|---------|
| Sliding window | 10 запросов |
| Failure rate threshold | 50% |
| Wait in open state | 10 сек |
| Half-open calls | 3 |
| Timeout (TimeLimiter) | 5 сек |
| Retry (GET) | 3 попытки, backoff 100–500 мс |

## Rate Limiting

- На основе Redis (Token Bucket).
- Ключ: principal (имя аутентифицированного пользователя) или IP-адрес.
- По умолчанию: 50 запросов/сек, burst до 100.

## Формат ошибки 401

```json
{
  "timestamp": "2026-03-19T10:15:30.135Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication failed",
  "path": "/user/profile"
}
```

Сообщение всегда `"Authentication failed"` — детали ошибки не раскрываются.

## Security-заголовки

Gateway автоматически добавляет:

- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Strict-Transport-Security: max-age=31536000; includeSubDomains` (HTTPS)
- `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Permissions-Policy: camera=(), microphone=(), geolocation=()`

## CORS

Настраивается через переменные окружения:

```powershell
$env:APP_CORS_ALLOWED_ORIGINS="https://app.example.com,https://admin.example.com"
$env:APP_CORS_ALLOWED_METHODS="GET,POST,PUT,DELETE,PATCH,OPTIONS"
$env:APP_CORS_ALLOWED_HEADERS="Authorization,Content-Type,X-Session-Id"
```

Если `APP_CORS_ALLOWED_ORIGINS` не задан — CORS-политика не применяется.

## Kubernetes

Шаблоны находятся в `k8s/`:

- `secret.example.yaml` — секреты (JWT public key)
- `redis-deployment.yaml` + `redis-service.yaml` — Redis
- `deployment.yaml` + `service.yaml` — Gateway (2 реплики)

Применение:

```bash
kubectl apply -f k8s/secret.example.yaml
kubectl apply -f k8s/redis-deployment.yaml
kubectl apply -f k8s/redis-service.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

## Helm

Добавлен chart в `helm/bio4j-api-gateway` с конфигурированием сервиса через `ConfigMap`.

- Все несекретные переменные окружения задаются в `values.yaml` в секции `config`.
- В `Deployment` они подключаются через `envFrom.configMapRef`.
- Секреты можно подключить через `existingSecretName` (опционально, `envFrom.secretRef`).

Установка:

```bash
helm upgrade --install bio4j-api-gateway ./helm/bio4j-api-gateway
```

## Структура проекта

```
src/main/java/ru/sber/bio4j/apigateway/
├── ApiGatewayApplication.java
├── security/
│   ├── SecurityConfig.java
│   ├── JwtClaimsUserValidationWebFilter.java
│   ├── UserProfileCacheService.java
│   ├── TokenRevocationService.java
│   ├── JsonAuthenticationEntryPoint.java
│   └── UnauthorizedResponseWriter.java
└── user/
    ├── GatewayUser.java
    ├── GatewayUserProfile.java
    └── GatewayUserRepository.java
```
