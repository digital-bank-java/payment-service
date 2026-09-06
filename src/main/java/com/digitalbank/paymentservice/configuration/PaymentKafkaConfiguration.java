package com.digitalbank.paymentservice.configuration;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class PaymentKafkaConfiguration {

    @Bean
    ProducerFactory<String, String> paymentProducerFactory(Environment environment) {
        var properties = new HashMap<String, Object>();
        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                environment.getProperty("spring.kafka.bootstrap-servers", "localhost:9092"));
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.putAll(boundProperties(environment, "spring.kafka.properties"));
        properties.putAll(boundProperties(environment, "spring.kafka.producer.properties"));
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, String> paymentKafkaTemplate(ProducerFactory<String, String> paymentProducerFactory) {
        return new KafkaTemplate<>(paymentProducerFactory);
    }

    private static Map<String, Object> boundProperties(Environment environment, String prefix) {
        return new HashMap<>(Binder.get(environment)
                .bind(prefix, Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of()));
    }
}
