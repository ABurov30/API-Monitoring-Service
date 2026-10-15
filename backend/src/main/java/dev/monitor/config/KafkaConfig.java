package dev.monitor.config;

import java.util.HashMap;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaConfig {
  @Bean
  public ProducerFactory<?, ?> producerFactory(KafkaProperties kafka, AppProperties app) {
    var config = new HashMap<String, Object>(kafka.buildProducerProperties());
    var outbox = app.getOutbox();
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, outbox.getMaxBlock().toMillis());
    config.put(
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
        Math.toIntExact(outbox.getRequestTimeout().toMillis()));
    config.put(
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
        Math.toIntExact(outbox.getDeliveryTimeout().toMillis()));
    config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    return new DefaultKafkaProducerFactory<>(config);
  }
}
