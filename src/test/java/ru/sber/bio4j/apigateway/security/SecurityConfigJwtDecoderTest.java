package ru.sber.bio4j.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.test.StepVerifier;

class SecurityConfigJwtDecoderTest {

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    @Test
    void shouldFailWhenPublicKeyMissingAndUnsignedModeDisabled() {
        assertThatThrownBy(() -> securityConfig.jwtDecoder("", false, resourceLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.jwt.public-key-location must be configured");
    }

    @Test
    void shouldDecodeUnsignedJwtWhenUnsignedModeEnabledWithoutPublicKey() {
        ReactiveJwtDecoder decoder = securityConfig.jwtDecoder("", true, resourceLoader);
        String token = buildUnsignedJwt("test.user", Instant.now().plusSeconds(120));

        StepVerifier.create(decoder.decode(token))
                .assertNext(jwt -> {
                    assertThat(jwt.getClaimAsString("sberpdi")).isEqualTo("test.user");
                    assertThat(jwt.getHeaders().get("alg")).isEqualTo("none");
                })
                .verifyComplete();
    }

    @Test
    void shouldUseUnsignedFallbackWhenPublicKeyConfigured() {
        ReactiveJwtDecoder decoder = securityConfig.jwtDecoder("classpath:jwt-test-public-key.pem", true, resourceLoader);
        String token = buildUnsignedJwt("fallback.user", Instant.now().plusSeconds(120));

        StepVerifier.create(decoder.decode(token))
                .assertNext(jwt -> assertThat(jwt.getClaimAsString("sberpdi")).isEqualTo("fallback.user"))
                .verifyComplete();
    }

    @Test
    void shouldRejectExpiredUnsignedJwt() {
        ReactiveJwtDecoder decoder = securityConfig.jwtDecoder("", true, resourceLoader);
        String token = buildUnsignedJwt("expired.user", Instant.now().minusSeconds(300));

        StepVerifier.create(decoder.decode(token))
                .expectError()
                .verify();
    }

    private String buildUnsignedJwt(String login, Instant expiresAt) {
        long issuedAt = expiresAt.minusSeconds(60).getEpochSecond();
        String headerJson = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        String payloadJson = """
                {
                  "sub":"user-id",
                  "sberpdi":"%s",
                  "iat":%d,
                  "exp":%d
                }
                """.formatted(login, issuedAt, expiresAt.getEpochSecond())
                .replaceAll("\\s+", "");

        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
