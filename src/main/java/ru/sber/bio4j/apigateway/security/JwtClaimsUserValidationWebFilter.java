package ru.sber.bio4j.apigateway.security;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import ru.sber.bio4j.apigateway.user.GatewayUser;
import ru.sber.bio4j.apigateway.user.GatewayUserProfile;
import ru.sber.bio4j.apigateway.user.GatewayUserRepository;
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

@Component
public class JwtClaimsUserValidationWebFilter implements WebFilter {

    private static final String LOGIN_CLAIM = "sberpdi";
    private static final String SESSION_ID_CLAIM = "sid";

    private final GatewayUserRepository gatewayUserRepository;
    private final UnauthorizedResponseWriter unauthorizedResponseWriter;
    private final UserProfileCacheService userProfileCacheService;
    private final String sessionIdHeaderName;

    public JwtClaimsUserValidationWebFilter(
            GatewayUserRepository gatewayUserRepository,
            UnauthorizedResponseWriter unauthorizedResponseWriter,
            UserProfileCacheService userProfileCacheService,
            @Value("${app.session.header-name:X-Session-Id}") String sessionIdHeaderName
    ) {
        this.gatewayUserRepository = gatewayUserRepository;
        this.unauthorizedResponseWriter = unauthorizedResponseWriter;
        this.userProfileCacheService = userProfileCacheService;
        this.sessionIdHeaderName = sessionIdHeaderName;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> validateUser(authentication, exchange, chain))
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
            return unauthorizedResponseWriter.write(exchange, "JWT claim 'sberpdi' is missing");
        }

        return gatewayUserRepository.findByLoginAndActiveTrue(login)
                .flatMap(user -> cacheProfileAndContinue(user, jwt, exchange, chain))
                .switchIfEmpty(unauthorizedResponseWriter.write(exchange, "User login from JWT not found in database"));
    }

    private Mono<Void> cacheProfileAndContinue(
            GatewayUser user,
            Jwt jwt,
            ServerWebExchange exchange,
            WebFilterChain chain
    ) {
        String sessionId = resolveSessionId(jwt, exchange);
        GatewayUserProfile profile = toProfile(user);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(builder -> builder.headers(headers -> headers.set(sessionIdHeaderName, sessionId)))
                .build();

        return userProfileCacheService.saveBySessionId(sessionId, profile)
                .then(chain.filter(mutatedExchange));
    }

    private String resolveSessionId(Jwt jwt, ServerWebExchange exchange) {
        String sid = jwt.getClaimAsString(SESSION_ID_CLAIM);
        if (StringUtils.hasText(sid)) {
            return sid;
        }

        if (StringUtils.hasText(jwt.getId())) {
            return jwt.getId();
        }

        String incomingSessionId = exchange.getRequest().getHeaders().getFirst(sessionIdHeaderName);
        if (StringUtils.hasText(incomingSessionId)) {
            return incomingSessionId;
        }

        return UUID.randomUUID().toString();
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
