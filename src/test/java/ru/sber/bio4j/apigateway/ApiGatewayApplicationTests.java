package ru.sber.bio4j.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class ApiGatewayApplicationTests {

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
    }
}
