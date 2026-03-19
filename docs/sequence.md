# Диаграмма последовательности: bio4j-api-gateway

## Основной поток (UC-01): авторизованный запрос

```mermaid
sequenceDiagram
    participant Client as Клиент
    participant SCG as Spring Cloud Gateway
    participant SS as Spring Security<br/>(JWT Decoder RS256)
    participant Filter as JwtClaimsUserValidation<br/>WebFilter
    participant Revoke as TokenRevocation<br/>Service
    participant Cache as UserProfileCache<br/>Service
    participant Caffeine as Caffeine<br/>(L1 Cache)
    participant Redis as Redis
    participant DB as PostgreSQL
    participant Downstream as Downstream<br/>Service

    Client->>SCG: HTTP запрос<br/>Authorization: Bearer {JWT}

    SCG->>SS: Валидация JWT (RS256)
    alt JWT невалиден / отсутствует
        SS-->>Client: 401 {"message": "Authentication failed"}
    end

    SS->>Filter: Authentication (Jwt principal)

    Filter->>Filter: Извлечь claim "sberpdi" (login)
    alt claim отсутствует
        Filter-->>Client: 401 {"message": "Authentication failed"}
    end

    Filter->>Filter: resolveSessionId(jwt)<br/>sid → jti → UUID

    Filter->>Revoke: isRevoked(jti)
    Revoke->>Redis: EXISTS revoked-token:{jti}
    Redis-->>Revoke: true / false
    Revoke-->>Filter: revoked?
    alt Токен отозван
        Filter-->>Client: 401 {"message": "Authentication failed"}
    end

    Filter->>Cache: findBySessionId(sessionId)

    Cache->>Caffeine: getIfPresent(sessionId)
    alt L1 hit
        Caffeine-->>Cache: GatewayUserProfile
        Cache-->>Filter: profile
    else L1 miss
        Cache->>Redis: GET session:user-profile:{sessionId}
        alt L2 hit
            Redis-->>Cache: JSON profile
            Cache->>Cache: deserialize
            Cache->>Caffeine: put(sessionId, profile)
            Cache-->>Filter: profile
        else L2 miss
            Cache-->>Filter: empty
            Filter->>DB: findByLoginAndActiveTrue(login)
            alt Пользователь не найден
                DB-->>Filter: empty
                Filter-->>Client: 401 {"message": "Authentication failed"}
            end
            DB-->>Filter: GatewayUser
            Filter->>Filter: toProfile(user)
            Filter-)Cache: saveBySessionId (fire-and-forget)
            Cache->>Caffeine: put(sessionId, profile)
            Cache->>Redis: SET session:user-profile:{sessionId}<br/>EX 30m
        end
    end

    Filter->>SCG: mutatedExchange<br/>+ X-Session-Id: {sessionId}

    SCG->>SCG: Применить фильтры маршрута:<br/>CircuitBreaker → Retry → RateLimiter

    alt Rate limit превышен
        SCG-->>Client: 429 Too Many Requests
    else Circuit Breaker OPEN
        SCG-->>Client: 503 Service Unavailable
    else Нормальный режим
        SCG->>Downstream: HTTP запрос<br/>X-Session-Id: {sessionId}
        Downstream->>Redis: GET session:user-profile:{sessionId}
        Redis-->>Downstream: JSON profile (roles, grants)
        Downstream-->>SCG: HTTP ответ
        SCG-->>Client: HTTP ответ
    end
```

## Поток отзыва токена (UC-07)

```mermaid
sequenceDiagram
    participant Admin as Администратор
    participant Redis as Redis
    participant Client as Клиент
    participant Gateway as API Gateway
    participant Filter as JwtClaimsUserValidation<br/>WebFilter
    participant Revoke as TokenRevocation<br/>Service

    Admin->>Redis: SET revoked-token:{jti} "revoked"<br/>EX {remaining TTL}

    Note over Client,Gateway: Последующий запрос с отозванным JWT

    Client->>Gateway: HTTP запрос<br/>Authorization: Bearer {revoked JWT}
    Gateway->>Gateway: JWT подпись OK, exp OK
    Gateway->>Filter: Authentication (Jwt principal)
    Filter->>Revoke: isRevoked(jti)
    Revoke->>Redis: EXISTS revoked-token:{jti}
    Redis-->>Revoke: true
    Revoke-->>Filter: revoked = true
    Filter-->>Client: 401 {"message": "Authentication failed"}
```

## Поток с попаданием в L1-кеш (оптимальный путь)

```mermaid
sequenceDiagram
    participant Client as Клиент
    participant Gateway as Spring Cloud Gateway
    participant SS as Spring Security
    participant Filter as JwtClaimsUserValidation<br/>WebFilter
    participant Revoke as TokenRevocation<br/>Service
    participant Redis as Redis
    participant Caffeine as Caffeine
    participant Downstream as Downstream

    Client->>Gateway: HTTP запрос
    Gateway->>SS: Валидация JWT ✓
    SS->>Filter: Authentication

    Filter->>Revoke: isRevoked(jti)
    Revoke->>Redis: EXISTS revoked-token:{jti}
    Redis-->>Revoke: false

    Filter->>Caffeine: getIfPresent(sessionId)
    Caffeine-->>Filter: GatewayUserProfile ✓

    Note over Filter: Нет обращений к Redis (GET) и PostgreSQL

    Filter->>Gateway: mutatedExchange + X-Session-Id
    Gateway->>Downstream: HTTP запрос
    Downstream-->>Gateway: ответ
    Gateway-->>Client: ответ
```

## Обзор компонентов и их взаимодействия

```mermaid
graph TB
    Client([Клиент]) --> SCG[Spring Cloud Gateway]

    subgraph Gateway["API Gateway"]
        SCG --> SS[Spring Security<br/>JWT RS256]
        SS --> Filter[JwtClaimsUser<br/>ValidationWebFilter]
        Filter --> Revoke[TokenRevocation<br/>Service]
        Filter --> Cache[UserProfileCache<br/>Service]
        Cache --> Caffeine[(Caffeine<br/>L1 Cache)]
        Cache --> Redis2[(Redis<br/>L2 Cache)]
        Filter --> DB[(PostgreSQL)]
        SS --> EntryPoint[JsonAuthentication<br/>EntryPoint]
        EntryPoint --> Writer[Unauthorized<br/>ResponseWriter]
        Filter --> Writer
        SCG --> CB[CircuitBreaker<br/>Resilience4j]
        SCG --> Retry[Retry Filter]
        SCG --> RL[RateLimiter<br/>Redis]
    end

    Revoke --> Redis2
    RL --> Redis2

    SCG --> UserService([User Service])
    SCG --> BillingService([Billing Service])

    UserService --> Redis2
    BillingService --> Redis2

    style Gateway fill:#f0f4ff,stroke:#4a6fa5
    style Caffeine fill:#e8f5e9,stroke:#4caf50
    style Redis2 fill:#fff3e0,stroke:#ff9800
    style DB fill:#fce4ec,stroke:#e91e63
```
