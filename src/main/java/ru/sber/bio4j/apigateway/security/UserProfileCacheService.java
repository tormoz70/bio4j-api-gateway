package ru.sber.bio4j.apigateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.sber.bio4j.apigateway.user.GatewayUserProfile;
import reactor.core.publisher.Mono;

@Service
public class UserProfileCacheService {

    private static final String PROFILE_CACHE_KEY_PREFIX = "session:user-profile:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public UserProfileCacheService(
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.profile-cache.ttl:30m}") Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Mono<Void> saveBySessionId(String sessionId, GatewayUserProfile profile) {
        return Mono.fromCallable(() -> serialize(profile))
                .flatMap(payload -> redisTemplate.opsForValue()
                        .set(PROFILE_CACHE_KEY_PREFIX + sessionId, payload, ttl))
                .then();
    }

    private String serialize(GatewayUserProfile profile) {
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize user profile for cache", ex);
        }
    }
}
