package ru.sber.bio4j.apigateway.security;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JwtClaimsUserValidationWebFilter jwtClaimsUserValidationWebFilter,
            CorsConfigurationSource corsConfigurationSource
    ) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .headers(headers -> headers
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(pp -> pp.policy("camera=(), microphone=(), geolocation=()")))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeExchange(ae -> ae
                        .pathMatchers("/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(Customizer.withDefaults()))
                .addFilterAfter(jwtClaimsUserValidationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${security.jwt.public-key-location:}") String publicKeyLocation,
            @Value("${security.jwt.allow-unsigned:false}") boolean allowUnsigned,
            ResourceLoader resourceLoader
    ) {
        ReactiveJwtDecoder signedJwtDecoder = null;
        if (StringUtils.hasText(publicKeyLocation)) {
            Resource publicKeyResource = resourceLoader.getResource(publicKeyLocation);
            RSAPublicKey publicKey = loadRsaPublicKey(publicKeyResource);
            signedJwtDecoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey)
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        }

        if (signedJwtDecoder == null) {
            if (!allowUnsigned) {
                throw new IllegalStateException(
                        "security.jwt.public-key-location must be configured when security.jwt.allow-unsigned=false"
                );
            }
            return this::decodeUnsignedJwt;
        }

        if (!allowUnsigned) {
            return signedJwtDecoder;
        }

        ReactiveJwtDecoder finalSignedJwtDecoder = signedJwtDecoder;
        return token -> finalSignedJwtDecoder.decode(token)
                .onErrorResume(error -> isUnsecuredJwt(token)
                        ? decodeUnsignedJwt(token)
                        : Mono.error(error));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") String allowedOrigins,
            @Value("${app.cors.allowed-methods:GET,POST,PUT,DELETE,PATCH,OPTIONS}") String allowedMethods,
            @Value("${app.cors.allowed-headers:Authorization,Content-Type,X-Session-Id}") String allowedHeaders,
            @Value("${app.cors.max-age:3600}") long maxAge
    ) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!allowedOrigins.isBlank()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(splitCsv(allowedOrigins));
            config.setAllowedMethods(splitCsv(allowedMethods));
            config.setAllowedHeaders(splitCsv(allowedHeaders));
            config.setMaxAge(maxAge);
            config.setAllowCredentials(true);
            source.registerCorsConfiguration("/**", config);
        }
        return source;
    }

    @Bean
    KeyResolver principalKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> principal.getName())
                .defaultIfEmpty(
                        Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
                                .getAddress().getHostAddress()
                );
    }

    private RSAPublicKey loadRsaPublicKey(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            String pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(pem);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to load RSA public key from: " + resource, e);
        }
    }

    private Mono<Jwt> decodeUnsignedJwt(String token) {
        try {
            JWT parsedToken = JWTParser.parse(token);
            if (!(parsedToken instanceof PlainJWT plainJwt)) {
                return Mono.error(new BadJwtException("Only unsigned JWT with alg=none are supported in fallback mode"));
            }

            JWTClaimsSet claimsSet = plainJwt.getJWTClaimsSet();
            Map<String, Object> headers = new LinkedHashMap<>(plainJwt.getHeader().toJSONObject());
            Map<String, Object> claims = new LinkedHashMap<>(claimsSet.getClaims());
            Instant issuedAt = toInstant(claimsSet.getIssueTime());
            Instant expiresAt = toInstant(claimsSet.getExpirationTime());

            Jwt jwt = new Jwt(
                    token,
                    issuedAt != null ? issuedAt : Instant.EPOCH,
                    expiresAt != null ? expiresAt : Instant.MAX,
                    headers,
                    claims
            );

            var validationResult = new JwtTimestampValidator().validate(jwt);
            if (validationResult.hasErrors()) {
                return Mono.error(new BadJwtException("JWT timestamp validation failed"));
            }
            return Mono.just(jwt);
        } catch (ParseException e) {
            return Mono.error(new BadJwtException("Failed to parse unsigned JWT", e));
        } catch (JwtException e) {
            return Mono.error(e);
        }
    }

    private boolean isUnsecuredJwt(String token) {
        try {
            return JWTParser.parse(token) instanceof PlainJWT;
        } catch (ParseException e) {
            return false;
        }
    }

    private Instant toInstant(java.util.Date value) {
        return value == null ? null : value.toInstant();
    }

    private List<String> splitCsv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
