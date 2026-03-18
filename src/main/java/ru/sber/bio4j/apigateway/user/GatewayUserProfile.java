package ru.sber.bio4j.apigateway.user;

import java.util.List;

public record GatewayUserProfile(
        Long id,
        String login,
        List<String> roles,
        List<String> grants
) {
}
