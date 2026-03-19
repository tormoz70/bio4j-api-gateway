package ru.sber.bio4j.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.sber.bio4j.apigateway.user.GatewayUser;
import ru.sber.bio4j.apigateway.user.GatewayUserProfile;
import ru.sber.bio4j.apigateway.user.GatewayUserRepository;

@ExtendWith(MockitoExtension.class)
class JwtClaimsUserValidationWebFilterTest {

    @Mock GatewayUserRepository userRepository;
    @Mock UnauthorizedResponseWriter responseWriter;
    @Mock UserProfileCacheService cacheService;
    @Mock TokenRevocationService revocationService;
    @Mock WebFilterChain chain;

    JwtClaimsUserValidationWebFilter filter;
    MockServerWebExchange exchange;

    static final String SID = "550e8400-e29b-41d4-a716-446655440000";
    static final String JTI = "660e8400-e29b-41d4-a716-446655440000";
    static final String LOGIN = "ivanov_ii";
    static final GatewayUserProfile PROFILE =
            new GatewayUserProfile(1L, LOGIN, List.of("ROLE_USER"), List.of("grant.read"));

    @BeforeEach
    void setUp() {
        filter = new JwtClaimsUserValidationWebFilter(
                userRepository, responseWriter, cacheService, revocationService, "X-Session-Id"
        );
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/user/profile").build());
    }

    @Test
    void shouldForwardWhenProfileFoundInCache() {
        Jwt jwt = jwt(LOGIN, SID, null);
        when(cacheService.findBySessionId(SID)).thenReturn(Mono.just(PROFILE));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
        ).verifyComplete();

        verify(userRepository, never()).findByLoginAndActiveTrue(anyString());

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().getFirst("X-Session-Id")).isEqualTo(SID);
    }

    @Test
    void shouldLoadFromDbOnCacheMiss() {
        Jwt jwt = jwt(LOGIN, SID, null);
        GatewayUser user = user(1L, LOGIN, true, "ROLE_USER", "grant.read");

        when(cacheService.findBySessionId(SID)).thenReturn(Mono.empty());
        when(userRepository.findByLoginAndActiveTrue(LOGIN)).thenReturn(Mono.just(user));
        when(cacheService.saveBySessionId(eq(SID), any())).thenReturn(Mono.empty());
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
        ).verifyComplete();

        verify(userRepository).findByLoginAndActiveTrue(LOGIN);
        verify(cacheService).saveBySessionId(eq(SID), any(GatewayUserProfile.class));
    }

    @Test
    void shouldReturn401WhenSberpdiMissing() {
        Jwt jwt = jwt(null, null, null);
        when(responseWriter.write(any(), eq("Authentication failed"))).thenReturn(Mono.empty());

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
        ).verifyComplete();

        verify(responseWriter).write(any(), eq("Authentication failed"));
        verify(chain, never()).filter(any());
    }

    @Test
    void shouldReturn401WhenUserNotInDb() {
        Jwt jwt = jwt(LOGIN, SID, null);
        when(cacheService.findBySessionId(SID)).thenReturn(Mono.empty());
        when(userRepository.findByLoginAndActiveTrue(LOGIN)).thenReturn(Mono.empty());
        when(responseWriter.write(any(), eq("Authentication failed"))).thenReturn(Mono.empty());

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
        ).verifyComplete();

        verify(responseWriter).write(any(), eq("Authentication failed"));
    }

    @Test
    void shouldReturn401WhenTokenRevoked() {
        Jwt jwt = jwt(LOGIN, null, JTI);
        when(revocationService.isRevoked(JTI)).thenReturn(Mono.just(true));
        when(responseWriter.write(any(), eq("Authentication failed"))).thenReturn(Mono.empty());

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
        ).verifyComplete();

        verify(responseWriter).write(any(), eq("Authentication failed"));
        verify(cacheService, never()).findBySessionId(anyString());
    }

    @Test
    void shouldPreferSidOverJtiForSessionId() {
        Jwt jwt = jwt(LOGIN, SID, JTI);
        when(revocationService.isRevoked(JTI)).thenReturn(Mono.just(false));
        when(cacheService.findBySessionId(SID)).thenReturn(Mono.just(PROFILE));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
        ).verifyComplete();

        verify(cacheService).findBySessionId(SID);
    }

    @Test
    void shouldFallbackToJtiWhenSidNotUuid() {
        Jwt jwt = jwt(LOGIN, "not-a-uuid", JTI);
        when(revocationService.isRevoked(JTI)).thenReturn(Mono.just(false));
        when(cacheService.findBySessionId(JTI)).thenReturn(Mono.just(PROFILE));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
        ).verifyComplete();

        verify(cacheService).findBySessionId(JTI);
    }

    @Test
    void shouldGenerateNewUuidWhenNoValidSidOrJti() {
        Jwt jwt = jwt(LOGIN, "bad", null);
        when(cacheService.findBySessionId(anyString())).thenReturn(Mono.just(PROFILE));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
        ).verifyComplete();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(cacheService).findBySessionId(captor.capture());
        assertThat(UUID.fromString(captor.getValue())).isNotNull();
        assertThat(captor.getValue()).isNotEqualTo("bad");
    }

    @Test
    void shouldPassThroughWithoutSecurityContext() {
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verify(responseWriter, never()).write(any(), anyString());
    }

    private Jwt jwt(String login, String sid, String jti) {
        var builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "test");
        if (login != null) builder.claim("sberpdi", login);
        if (sid != null) builder.claim("sid", sid);
        if (jti != null) builder.claim("jti", jti);
        return builder.build();
    }

    private GatewayUser user(Long id, String login, boolean active, String roles, String grants) {
        GatewayUser u = new GatewayUser();
        u.setId(id);
        u.setLogin(login);
        u.setActive(active);
        u.setRoles(roles);
        u.setGrants(grants);
        return u;
    }
}
