package ru.sber.bio4j.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

class UnauthorizedResponseWriterTest {

    UnauthorizedResponseWriter writer;

    @BeforeEach
    void setUp() {
        writer = new UnauthorizedResponseWriter(new ObjectMapper());
    }

    @Test
    void shouldWriteJsonUnauthorizedResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/user/profile").build()
        );

        StepVerifier.create(writer.write(exchange, "Authentication failed")).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body)
                .contains("\"status\":401")
                .contains("\"error\":\"Unauthorized\"")
                .contains("\"message\":\"Authentication failed\"")
                .contains("\"path\":\"/user/profile\"")
                .contains("\"timestamp\"");
    }

    @Test
    void shouldIncludeCorrectRequestPath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/billing/invoices").build()
        );

        StepVerifier.create(writer.write(exchange, "test message")).verifyComplete();

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"path\":\"/billing/invoices\"");
        assertThat(body).contains("\"message\":\"test message\"");
    }
}
