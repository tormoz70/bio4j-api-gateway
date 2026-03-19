package ru.sber.bio4j.apigateway.security;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class JsonAuthenticationEntryPointTest {

    @Mock UnauthorizedResponseWriter responseWriter;

    JsonAuthenticationEntryPoint entryPoint;

    @BeforeEach
    void setUp() {
        entryPoint = new JsonAuthenticationEntryPoint(responseWriter);
    }

    @Test
    void shouldReturnGenericMessageForBadCredentials() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build()
        );
        when(responseWriter.write(exchange, "Authentication failed")).thenReturn(Mono.empty());

        StepVerifier.create(
                entryPoint.commence(exchange, new BadCredentialsException("bad"))
        ).verifyComplete();

        verify(responseWriter).write(exchange, "Authentication failed");
    }

    @Test
    void shouldReturnGenericMessageForInvalidBearerToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/data").build()
        );
        when(responseWriter.write(exchange, "Authentication failed")).thenReturn(Mono.empty());

        StepVerifier.create(
                entryPoint.commence(exchange, new InvalidBearerTokenException("expired"))
        ).verifyComplete();

        verify(responseWriter).write(exchange, "Authentication failed");
    }
}
