package ru.sber.bio4j.apigateway.security;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JsonAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private static final String AUTH_FAILED = "Authentication failed";

    private final UnauthorizedResponseWriter unauthorizedResponseWriter;

    public JsonAuthenticationEntryPoint(UnauthorizedResponseWriter unauthorizedResponseWriter) {
        this.unauthorizedResponseWriter = unauthorizedResponseWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return unauthorizedResponseWriter.write(exchange, AUTH_FAILED);
    }
}
