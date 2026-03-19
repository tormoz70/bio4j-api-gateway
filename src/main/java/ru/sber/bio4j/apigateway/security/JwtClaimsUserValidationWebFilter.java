package ru.sber.bio4j.apigateway.security;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ru.sber.bio4j.apigateway.user.GatewayUser;
import ru.sber.bio4j.apigateway.user.GatewayUserProfile;
import ru.sber.bio4j.apigateway.user.GatewayUserRepository;

@Component
public class JwtClaimsUserValidationWebFilter implements WebFilter {

    private static final String LOGIN_CLAIM = "sberpdi";
    private static final String SESSION_ID_CLAIM = "sid";
    private static final String AUTH_FAILED = "Authentication failed";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final GatewayUserRepository gatewayUserRepository;
    private final UnauthorizedResponseWriter unauthorizedResponseWriter;
    private final UserProfileCacheService userProfileCacheService;
    private final TokenRevocationService tokenRevocationService;
    private final String sessionIdHeaderName;

    public JwtClaimsUserValidationWebFilter(
            GatewayUserRepository gatewayUserRepository,
            UnauthorizedResponseWriter unauthorizedResponseWriter,
            UserProfileCacheService userProfileCacheService,
            TokenRevocationService tokenRevocationService,
            @Value("${app.session.header-name:X-Session-Id}") String sessionIdHeaderName
    ) {
        this.gatewayUserRepository = gatewayUserRepository;
        this.unauthorizedResponseWriter = unauthorizedResponseWriter;
        this.userProfileCacheService = userProfileCacheService;
        this.tokenRevocationService = tokenRevocationService;
        this.sessionIdHeaderName = sessionIdHeaderName;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .flatMap(auth -> validateUser(auth, exchange, chain))
                .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Void> validateUser(
            Authentication authentication,
            ServerWebExchange exchange,
            WebFilterChain chain
    ) {
        if (!(authentication instanceof AbstractAuthenticationToken authToken) || !authToken.isAuthenticated()) {
            return chain.filter(exchange);
        }

        Object principal = authToken.getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            return chain.filter(exchange);
        }

        String login = jwt.getClaimAsString(LOGIN_CLAIM);
        if (!StringUtils.hasText(login)) {
            return unauthorizedResponseWriter.write(exchange, AUTH_FAILED);
        }

        String sessionId = resolveSessionId(jwt);

        return checkRevocation(jwt)
                .flatMap(revoked -> {
                    if (revoked) {
                        return unauthorizedResponseWriter.write(exchange, AUTH_FAILED);
                    }
                    return resolveProfile(login, sessionId)
                            .flatMap(profile -> forwardRequest(sessionId, exchange, chain))
                            .switchIfEmpty(unauthorizedResponseWriter.write(exchange, AUTH_FAILED));
                });
    }

    private Mono<Boolean> checkRevocation(Jwt jwt) {
        String jti = jwt.getId();
        if (!StringUtils.hasText(jti)) {
            return Mono.just(false);
        }
        return tokenRevocationService.isRevoked(jti);
    }

    private Mono<GatewayUserProfile> resolveProfile(String login, String sessionId) {
        return userProfileCacheService.findBySessionId(sessionId)
                .switchIfEmpty(loadFromDbAndCache(login, sessionId));
    }

    private Mono<GatewayUserProfile> loadFromDbAndCache(String login, String sessionId) {
        return gatewayUserRepository.findByLoginAndActiveTrue(login)
                .map(this::toProfile)
                .doOnNext(profile ->
                        userProfileCacheService.saveBySessionId(sessionId, profile).subscribe()
                );
    }

    private Mono<Void> forwardRequest(String sessionId, ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(builder -> builder.headers(headers -> headers.set(sessionIdHeaderName, sessionId)))
                .build();
        return chain.filter(mutatedExchange);
    }

    private String resolveSessionId(Jwt jwt) {
        String sid = jwt.getClaimAsString(SESSION_ID_CLAIM);
        if (isValidUuid(sid)) {
            return sid;
        }

        String jti = jwt.getId();
        if (isValidUuid(jti)) {
            return jti;
        }

        return UUID.randomUUID().toString();
    }

    private boolean isValidUuid(String value) {
        return StringUtils.hasText(value) && UUID_PATTERN.matcher(value).matches();
    }

    private GatewayUserProfile toProfile(GatewayUser user) {
        return new GatewayUserProfile(
                user.getId(),
                user.getLogin(),
                parseCsv(user.getRoles()),
                parseCsv(user.getGrants())
        );
    }

    private List<String> parseCsv(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
