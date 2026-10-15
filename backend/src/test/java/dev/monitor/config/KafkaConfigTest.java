package dev.monitor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.monitor.kafka.OutboxPublisher;
import dev.monitor.kafka.OutboxStore;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

class KafkaConfigTest {
  @Test
  void autoConfiguresTemplateAndPublisherWithCustomProducerFactoryWithoutBroker() {
    var properties = new AppProperties();
    properties.getAuth().setSecret("A".repeat(43));
    properties.getOutbox().setMaxBlock(Duration.ofSeconds(2));
    properties.getOutbox().setRequestTimeout(Duration.ofSeconds(3));
    properties.getOutbox().setDeliveryTimeout(Duration.ofSeconds(4));

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
        .withUserConfiguration(KafkaConfig.class, OutboxPublisher.class)
        .withBean(AppProperties.class, () -> properties)
        .withBean(OutboxStore.class, () -> mock(OutboxStore.class))
        .withPropertyValues(
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.admin.auto-create=false",
            "spring.kafka.producer.key-serializer=" + StringSerializer.class.getName(),
            "spring.kafka.producer.value-serializer=" + StringSerializer.class.getName())
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ProducerFactory.class)
                  .hasSingleBean(KafkaTemplate.class)
                  .hasSingleBean(OutboxPublisher.class);
              var factory = context.getBean(ProducerFactory.class);
              assertThat(context.getBean(KafkaTemplate.class).getProducerFactory())
                  .isSameAs(factory);
              assertThat(factory.getConfigurationProperties())
                  .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, List.of("127.0.0.1:1"))
                  .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                  .containsEntry(
                      ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                  .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                  .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                  .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000L)
                  .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000)
                  .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4000)
                  .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 5);
            });
  }
}
