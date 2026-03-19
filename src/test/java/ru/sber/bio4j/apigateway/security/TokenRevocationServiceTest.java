package ru.sber.bio4j.apigateway.security;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    @Mock ReactiveStringRedisTemplate redisTemplate;
    @Mock ReactiveValueOperations<String, String> valueOps;

    TokenRevocationService service;

    @BeforeEach
    void setUp() {
        service = new TokenRevocationService(redisTemplate, "revoked-token:");
    }

    @Test
    void shouldReturnFalseForNullJti() {
        StepVerifier.create(service.isRevoked(null))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldReturnFalseForEmptyJti() {
        StepVerifier.create(service.isRevoked(""))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldReturnFalseWhenKeyNotFound() {
        when(redisTemplate.hasKey("revoked-token:jti-123")).thenReturn(Mono.just(false));

        StepVerifier.create(service.isRevoked("jti-123"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldReturnTrueWhenTokenRevoked() {
        when(redisTemplate.hasKey("revoked-token:jti-123")).thenReturn(Mono.just(true));

        StepVerifier.create(service.isRevoked("jti-123"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void shouldStoreRevokedTokenWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.set("revoked-token:jti-456", "revoked", Duration.ofHours(1)))
                .thenReturn(Mono.just(true));

        StepVerifier.create(service.revoke("jti-456", Duration.ofHours(1)))
                .expectNext(true)
                .verifyComplete();

        verify(valueOps).set("revoked-token:jti-456", "revoked", Duration.ofHours(1));
    }
}
