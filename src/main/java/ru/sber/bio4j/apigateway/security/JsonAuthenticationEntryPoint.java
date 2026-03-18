package ru.sber.bio4j.apigateway.security;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JsonAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final UnauthorizedResponseWriter unauthorizedResponseWriter;

    public JsonAuthenticationEntryPoint(UnauthorizedResponseWriter unauthorizedResponseWriter) {
        this.unauthorizedResponseWriter = unauthorizedResponseWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        String message = "JWT token is missing or invalid";
        if (ex instanceof InvalidBearerTokenException) {
            message = "JWT token is invalid or expired";
        }
        return unauthorizedResponseWriter.write(exchange, message);
    }
}
