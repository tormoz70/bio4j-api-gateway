package ru.sber.bio4j.apigateway.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.sber.bio4j.apigateway.user.GatewayUserProfile;

@ExtendWith(MockitoExtension.class)
class UserProfileCacheServiceTest {

    @Mock ReactiveStringRedisTemplate redisTemplate;
    @Mock ReactiveValueOperations<String, String> valueOps;

    UserProfileCacheService service;
    final ObjectMapper objectMapper = new ObjectMapper();

    static final String SESSION_ID = "test-session-id";
    static final String REDIS_KEY = "session:user-profile:" + SESSION_ID;
    static final Duration TTL = Duration.ofMinutes(30);
    static final GatewayUserProfile PROFILE =
            new GatewayUserProfile(1L, "ivanov_ii", List.of("ROLE_USER"), List.of("grant.read"));

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new UserProfileCacheService(
                redisTemplate, objectMapper, TTL, 1000, Duration.ofSeconds(30)
        );
    }

    @Test
    void shouldReturnFromLocalCacheAfterSave() {
        when(valueOps.set(eq(REDIS_KEY), anyString(), eq(TTL))).thenReturn(Mono.just(true));

        StepVerifier.create(service.saveBySessionId(SESSION_ID, PROFILE)).verifyComplete();

        StepVerifier.create(service.findBySessionId(SESSION_ID))
                .expectNext(PROFILE)
                .verifyComplete();

        verify(valueOps, never()).get(anyString());
    }

    @Test
    void shouldReturnFromRedisWhenLocalCacheMiss() throws Exception {
        String json = objectMapper.writeValueAsString(PROFILE);
        when(valueOps.get(REDIS_KEY)).thenReturn(Mono.just(json));

        StepVerifier.create(service.findBySessionId(SESSION_ID))
                .expectNext(PROFILE)
                .verifyComplete();

        verify(valueOps).get(REDIS_KEY);
    }

    @Test
    void shouldReturnEmptyWhenNothingCached() {
        when(valueOps.get(REDIS_KEY)).thenReturn(Mono.empty());

        StepVerifier.create(service.findBySessionId(SESSION_ID))
                .verifyComplete();
    }

    @Test
    void shouldSaveToRedisWithTtl() {
        when(valueOps.set(eq(REDIS_KEY), anyString(), eq(TTL))).thenReturn(Mono.just(true));

        StepVerifier.create(service.saveBySessionId(SESSION_ID, PROFILE)).verifyComplete();

        verify(valueOps).set(eq(REDIS_KEY), anyString(), eq(TTL));
    }

    @Test
    void shouldPopulateLocalCacheFromRedis() throws Exception {
        String json = objectMapper.writeValueAsString(PROFILE);
        when(valueOps.get(REDIS_KEY)).thenReturn(Mono.just(json));

        StepVerifier.create(service.findBySessionId(SESSION_ID))
                .expectNext(PROFILE)
                .verifyComplete();

        StepVerifier.create(service.findBySessionId(SESSION_ID))
                .expectNext(PROFILE)
                .verifyComplete();

        verify(valueOps, times(1)).get(REDIS_KEY);
    }

    @Test
    void shouldReturnEmptyForCorruptedRedisData() {
        when(valueOps.get(REDIS_KEY)).thenReturn(Mono.just("{invalid-json}"));

        StepVerifier.create(service.findBySessionId(SESSION_ID))
                .verifyComplete();
    }
}
