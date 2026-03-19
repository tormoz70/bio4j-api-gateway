package ru.sber.bio4j.apigateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.sber.bio4j.apigateway.user.GatewayUserProfile;

@Service
public class UserProfileCacheService {

    private static final String PROFILE_CACHE_KEY_PREFIX = "session:user-profile:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Cache<String, GatewayUserProfile> localCache;

    public UserProfileCacheService(
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.profile-cache.ttl:30m}") Duration ttl,
            @Value("${app.profile-cache.local-max-size:10000}") long localMaxSize,
            @Value("${app.profile-cache.local-ttl:30s}") Duration localTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfterWrite(localTtl)
                .build();
    }

    public Mono<GatewayUserProfile> findBySessionId(String sessionId) {
        GatewayUserProfile cached = localCache.getIfPresent(sessionId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return redisTemplate.opsForValue()
                .get(PROFILE_CACHE_KEY_PREFIX + sessionId)
                .flatMap(this::deserialize)
                .doOnNext(profile -> localCache.put(sessionId, profile));
    }

    public Mono<Void> saveBySessionId(String sessionId, GatewayUserProfile profile) {
        localCache.put(sessionId, profile);
        return Mono.fromCallable(() -> serialize(profile))
                .flatMap(payload -> redisTemplate.opsForValue()
                        .set(PROFILE_CACHE_KEY_PREFIX + sessionId, payload, ttl))
                .then();
    }

    private String serialize(GatewayUserProfile profile) {
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize user profile", ex);
        }
    }

    private Mono<GatewayUserProfile> deserialize(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, GatewayUserProfile.class));
        } catch (JsonProcessingException ex) {
            return Mono.empty();
        }
    }
}
