package ru.sber.bio4j.apigateway.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Service
public class TokenRevocationService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public TokenRevocationService(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${app.token-revocation.key-prefix:revoked-token:}") String keyPrefix
    ) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    public Mono<Boolean> isRevoked(String jti) {
        if (!StringUtils.hasText(jti)) {
            return Mono.just(false);
        }
        return redisTemplate.hasKey(keyPrefix + jti);
    }

    public Mono<Boolean> revoke(String jti, Duration ttl) {
        return redisTemplate.opsForValue()
                .set(keyPrefix + jti, "revoked", ttl);
    }
}
