package ru.sber.bio4j.apigateway.user;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface GatewayUserRepository extends ReactiveCrudRepository<GatewayUser, Long> {

    Mono<GatewayUser> findByLoginAndActiveTrue(String login);
}
