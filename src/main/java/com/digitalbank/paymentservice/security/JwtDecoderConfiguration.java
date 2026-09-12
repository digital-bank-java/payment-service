package com.digitalbank.paymentservice.security;

import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
class JwtDecoderConfiguration {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    @ConditionalOnExpression(
            "'${spring.security.oauth2.resourceserver.jwt.issuer-uri:}' != '' or '${auth.jwt.secret:}' != ''")
    JwtDecoder jwtDecoder(Environment environment) {
        String issuerUri = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        if (issuerUri != null && !issuerUri.isBlank()) {
            String jwkSetUri = environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri");

            if (jwkSetUri != null && !jwkSetUri.isBlank()) {
                var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
                return configure(decoder, issuerUri, environment);
            }

            return configure((NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri), issuerUri, environment);
        }

        String secret = requiredProperty(environment, "auth.jwt.secret");
        String issuer = requiredProperty(environment, "auth.jwt.issuer");
        String audience = requiredProperty(environment, "auth.jwt.audience");
        String tokenPurpose = requiredProperty(environment, "auth.jwt.token-purpose");
        byte[] decodedSecret;
        try {
            decodedSecret = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("auth.jwt.secret must be valid base64", exception);
        }
        if (decodedSecret.length < 32) {
            throw new IllegalStateException("auth.jwt.secret must decode to at least 32 bytes");
        }
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(decodedSecret, "HmacSHA256"))
                .build();
        return configure(decoder, issuer, audience, tokenPurpose);
    }

    @Bean
    @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
    Object requireIssuerForExplicitJwkSet(Environment environment) {
        requiredProperty(environment, "spring.security.oauth2.resourceserver.jwt.issuer-uri");
        return new Object();
    }

    private static String requiredProperty(Environment environment, String propertyName) {
        String value = environment.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured");
        }
        return value;
    }

    private static JwtDecoder configure(NimbusJwtDecoder decoder, String issuer, Environment environment) {
        return configure(
                decoder,
                issuer,
                requiredProperty(environment, "auth.jwt.audience"),
                requiredProperty(environment, "auth.jwt.token-purpose"));
    }

    private static JwtDecoder configure(NimbusJwtDecoder decoder, String issuer, String audience, String tokenPurpose) {
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator =
                new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(audience));
        OAuth2TokenValidator<Jwt> purposeValidator =
                new JwtClaimValidator<String>("token_purpose", tokenPurpose::equals);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator, purposeValidator));
        return decoder;
    }
}
