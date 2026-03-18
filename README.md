# bio4j-api-gateway

API Gateway на Spring Boot `3.5.11` и Java `21` для Kubernetes.

Use case'ы описаны в `docs/use-cases.md`.

## Что делает сервис

- Принимает REST-запросы.
- Проверяет JWT токен.
- Извлекает claim `sberpdi` (логин пользователя).
- Проверяет, что логин есть в таблице `gateway_users` и пользователь активен.
- Загружает профиль пользователя из БД: `id`, `login`, `roles`, `grants`.
- Кеширует профиль в Redis по ключу `session:user-profile:{sessionId}`.
- Пробрасывает `sessionId` в downstream через заголовок `X-Session-Id` (имя настраивается).
- Если проверка пройдена, маршрутизирует запрос в downstream-сервис по правилам `spring.cloud.gateway.routes`.
- Если токен отсутствует/невалиден/истек или логин не найден, возвращает `401` в JSON.

## Быстрый запуск (локально)

1. Укажите секрет:

```powershell
$env:SECURITY_JWT_SECRET="very-secret-key-at-least-32-characters"
```

2. Запуск:

```powershell
gradle bootRun
```

3. Проверка health:

```powershell
curl http://localhost:8080/actuator/health
```

## Роутинг (по умолчанию)

- `/user/**` -> `${ROUTE_USER_SERVICE_URI}` (по умолчанию `http://localhost:8081`)
- `/billing/**` -> `${ROUTE_BILLING_SERVICE_URI}` (по умолчанию `http://localhost:8082`)

## JWT требования

- Подписан HS256 ключом `SECURITY_JWT_SECRET`.
- Содержит claim `sberpdi` с логином пользователя.
- Не должен быть просрочен.
- Для `sessionId` используется приоритет: JWT claim `sid` -> JWT `jti` -> входящий `X-Session-Id` -> новый UUID.

## Redis кеш профиля

- Хост/порт Redis: `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT`
- TTL профиля: `APP_PROFILE_CACHE_TTL` (по умолчанию `30m`)
- Ключ: `session:user-profile:{sessionId}`
- Значение: JSON профиля пользователя (`id`, `login`, `roles`, `grants`)

## Формат ошибки 401

```json
{
  "timestamp": "2026-03-18T10:15:30.135Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "JWT token is missing or invalid",
  "path": "/user/profile"
}
```

## Kubernetes

Шаблоны находятся в `k8s/`:

- `redis-deployment.yaml`
- `redis-service.yaml`
- `deployment.yaml`
- `service.yaml`
- `secret.example.yaml`

Применение:

```bash
kubectl apply -f k8s/secret.example.yaml
kubectl apply -f k8s/redis-deployment.yaml
kubectl apply -f k8s/redis-service.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```
