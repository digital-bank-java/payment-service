package com.digitalbank.paymentservice.security;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
                decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
                return decoder;
            }

            return JwtDecoders.fromIssuerLocation(issuerUri);
        }

        String secret = requiredProperty(environment, "auth.jwt.secret");
        String issuer = requiredProperty(environment, "auth.jwt.issuer");
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
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
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
}
