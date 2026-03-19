# Рекомендации по улучшению bio4j-api-gateway

## 1. Производительность

### 1.1. Запрос в БД на каждый HTTP-запрос

**Проблема:** фильтр `JwtClaimsUserValidationWebFilter` выполняет `findByLoginAndActiveTrue` к БД и `SET` в Redis
на каждый входящий запрос, даже если профиль уже закеширован.

**Решение:** двухуровневый кеш — сначала проверяется локальный Caffeine-кеш (TTL ~30 с),
затем Redis, и только при промахе — обращение к БД.

### 1.2. Последовательная запись в Redis перед downstream

**Проблема:** `saveBySessionId().then(chain.filter(...))` — downstream-запрос ждёт завершения Redis SET.

**Решение:** fire-and-forget запись в Redis через `doOnNext(...subscribe())` — downstream не блокируется.

### 1.3. Сериализация профиля на каждый запрос

**Проблема:** `ObjectMapper.writeValueAsString(profile)` вызывается при каждом запросе.

**Решение:** сериализация только при cache miss (в связке с 1.1).

### 1.4. Нет конфигурации R2DBC connection pool

**Проблема:** не настроены `initial-size`, `max-size`, `max-idle-time`, `max-life-time`.

**Решение:** добавлена секция `spring.r2dbc.pool` с настраиваемыми через env значениями.

### 1.5. Нет конфигурации Redis connection pool

**Проблема:** Lettuce по умолчанию использует одно соединение.

**Решение:** добавлена секция `spring.data.redis.lettuce.pool` + зависимость `commons-pool2`.

### 1.6. Нет rate limiting, circuit breaker, retry

**Проблема:** маршруты не используют фильтры устойчивости.

**Решение:**
- `RequestRateLimiter` — ограничение по количеству запросов (Redis-based).
- `CircuitBreaker` (Resilience4j) — защита от каскадных сбоев.
- `Retry` — автоматический повтор для `GET` при `502/503/504`.

## 2. Потребление ресурсов

### 2.1. Нет resource limits для gateway pod

K8s deployment не содержит `resources.requests/limits`. Рекомендуется добавить.

### 2.2. Нет JVM-тюнинга в Dockerfile

Рекомендуется: `-XX:MaxRAMPercentage=75.0 -XX:+UseZGC`.

### 2.3. Нет `.dockerignore`

Рекомендуется создать для исключения `.git/`, `.gradle/`, `build/` из Docker-контекста.

## 3. Безопасность

### 3.1. HS256 с симметричным секретом

**Проблема:** один секрет для подписи и верификации — при утечке можно подделать токены.

**Решение:** переход на RS256 (асимметричный). Gateway хранит только публичный ключ (`PEM`).

### 3.2. Дефолтный JWT-секрет в конфиге

**Проблема:** `security.jwt.secret` имел placeholder-значение по умолчанию.

**Решение:** свойство `security.jwt.public-key-location` указывает на PEM-файл; дефолтов нет.

### 3.3. Session fixation через `sid` / `X-Session-Id`

**Проблема:**
- `sid` из JWT мог быть произвольным → перезапись чужого кеша.
- Fallback на клиентский `X-Session-Id` → подмена сессии.

**Решение:**
- Убран fallback на клиентский заголовок.
- `sid` / `jti` валидируются как UUID.
- Входящий `X-Session-Id` удаляется default-фильтром gateway.

### 3.4. Redis без пароля по умолчанию

**Проблема:** `password: ${SPRING_DATA_REDIS_PASSWORD:}` — пустой пароль.

**Решение:** убран дефолт — `${SPRING_DATA_REDIS_PASSWORD}` обязателен.

### 3.6. Information leakage в ошибках 401

**Проблема:** сообщения раскрывали внутреннюю логику (`claim 'sberpdi' is missing`, `not found in database`).

**Решение:** единое сообщение `Authentication failed` для всех случаев.

### 3.8. Нет security-заголовков

**Решение:** включены стандартные Spring Security заголовки + добавлены:
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Permissions-Policy: camera=(), microphone=(), geolocation=()`

### 3.9. Нет CORS-политики

**Решение:** добавлена настраиваемая CORS-конфигурация через `app.cors.*`.

### 3.10. Нет механизма отзыва токенов

**Проблема:** скомпрометированный JWT действителен до `exp`.

**Решение:** `TokenRevocationService` — Redis-blacklist по `jti` с TTL.

## Инициализация БД

Удалены `schema.sql`, `data.sql` и конфиг `spring.sql.init.mode`. Предполагается, что БД уже создана.
