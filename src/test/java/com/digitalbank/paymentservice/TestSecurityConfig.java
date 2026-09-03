package com.digitalbank.paymentservice;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;

@TestConfiguration
class TestSecurityConfig {

    static final String ISSUER = "https://issuer.test.internal";
    static final String TEST_BEARER_TOKEN = "test-payment-jwt";
    static final String INSUFFICIENT_SCOPE_BEARER_TOKEN = "test-insufficient-scope-jwt";

    @Bean
    JwtDecoder testJwtDecoder() {
        OAuth2TokenValidator<Jwt> validator =
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER));
        return token -> {
            Instant now = Instant.now();
            if (TEST_BEARER_TOKEN.equals(token)) {
                return validate(
                        validator, jwt(token, ISSUER, now.minusSeconds(60), now.plusSeconds(3600), "payment.internal"));
            }
            if (INSUFFICIENT_SCOPE_BEARER_TOKEN.equals(token)) {
                return validate(
                        validator, jwt(token, ISSUER, now.minusSeconds(60), now.plusSeconds(3600), "other.scope"));
            }
            if (token.isBlank() || token.startsWith("malformed")) {
                throw new BadJwtException("Malformed token");
            }
            throw new JwtException("Invalid token");
        };
    }

    private static Jwt validate(OAuth2TokenValidator<Jwt> validator, Jwt jwt) {
        OAuth2TokenValidatorResult result = validator.validate(jwt);
        if (result.hasErrors()) {
            throw new JwtValidationException("Token validation failed", result.getErrors());
        }
        return jwt;
    }

    private static Jwt jwt(String token, String issuer, Instant issuedAt, Instant expiresAt, String scope) {
        return Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("payment-internal-client")
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("scope", scope)
                .build();
    }
}
