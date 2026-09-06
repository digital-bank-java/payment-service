package com.digitalbank.paymentservice;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

public final class FlatStructuredLoggingJsonMembersCustomizer
        implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    private static final String UNKNOWN = "unknown";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);
    private final String service;
    private final String environment;

    public FlatStructuredLoggingJsonMembersCustomizer(Environment environment) {
        this.service = environment.getProperty("spring.application.name", UNKNOWN);
        this.environment = environment.getProperty(
                "digital-bank.service.runtime-profile", environment.getProperty("spring.profiles.active", UNKNOWN));
    }

    @Override
    public void customize(Members<ILoggingEvent> members) {
        members.add("timestamp", event -> timestamp(event.getInstant()));
        members.add("level", event -> event.getLevel().toString());
        members.add("flat_service", event -> service);
        members.add("environment", event -> environment);
        members.add("flat_correlation_id", event -> contextValue(event, "correlation_id"));
        members.add("flat_trace_id", event -> traceValue(event, "trace_id", "traceId", "trace.id"));
        members.add("flat_span_id", event -> traceValue(event, "span_id", "spanId", "span.id"));
        members.add("logger", ILoggingEvent::getLoggerName);
    }

    private static String timestamp(Instant instant) {
        return TIMESTAMP_FORMATTER.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    private static String contextValue(ILoggingEvent event, String key) {
        var mdcValue = event.getMDCPropertyMap().get(key);
        if (mdcValue != null && !mdcValue.isBlank()) {
            return mdcValue;
        }
        for (KeyValuePair pair : keyValuePairs(event)) {
            if (key.equals(pair.key)
                    && pair.value != null
                    && !pair.value.toString().isBlank()) {
                return pair.value.toString();
            }
        }
        return UNKNOWN;
    }

    private static String traceValue(ILoggingEvent event, String... keys) {
        for (String key : keys) {
            var value = event.getMDCPropertyMap().get(key);
            if (value != null && !value.isBlank()) {
                return value.toLowerCase(Locale.ROOT);
            }
            for (KeyValuePair pair : keyValuePairs(event)) {
                if (key.equals(pair.key)
                        && pair.value != null
                        && !pair.value.toString().isBlank()) {
                    return pair.value.toString().toLowerCase(Locale.ROOT);
                }
            }
        }
        return UNKNOWN;
    }

    private static List<KeyValuePair> keyValuePairs(ILoggingEvent event) {
        var pairs = event.getKeyValuePairs();
        return pairs != null ? pairs : List.of();
    }
}
