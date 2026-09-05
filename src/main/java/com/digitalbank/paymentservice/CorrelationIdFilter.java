package com.digitalbank.paymentservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Integer.MIN_VALUE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

    static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}");
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private final String environment;

    public CorrelationIdFilter(
            @Value("${digital-bank.service.runtime-profile:${spring.profiles.active:sit}}") String environment) {
        this.environment = environment;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        var startedAt = System.nanoTime();
        try (var ignored = MDC.putCloseable("correlation_id", correlationId)) {
            filterChain.doFilter(request, response);
        } finally {
            LOGGER.atInfo()
                    .addKeyValue("event.name", "http.request.completed")
                    .addKeyValue("digital_bank.environment", environment)
                    .addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("http.response.status_code", response.getStatus())
                    .addKeyValue("correlation_id", correlationId)
                    .addKeyValue("event.duration_ms", (System.nanoTime() - startedAt) / 1_000_000)
                    .log("HTTP request completed");
        }
    }

    static String resolveCorrelationId(String candidate) {
        return candidate != null && SAFE_CORRELATION_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
