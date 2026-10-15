package dev.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.monitor.config.AppProperties;
import dev.monitor.kafka.OutboxPublisher;
import dev.monitor.kafka.OutboxStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class KafkaDeliveryIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine3.22");

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  @Autowired OutboxStore store;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> template;

  @Test
  void unavailableBrokerRetainsSnapshotThenRecoveryPublishesBothTopics() throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("api-data", 1, (short) 1), new NewTopic("api-errors", 1, (short) 1)))
          .all()
          .get();
    }
    var id = store.saveResult(true, "original");
    String originalEvent =
        jdbc.queryForObject(
            "select event_payload from api_outbox where data_id=?", String.class, id);
    var failedConfig = new HashMap<String, Object>();
    failedConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
    failedConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    failedConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    failedConfig.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 200);
    var failedFactory = new DefaultKafkaProducerFactory<String, String>(failedConfig);
    var options = new AppProperties();
    options.getOutbox().setMaxEventsPerTick(1);
    try {
      new OutboxPublisher(store, new KafkaTemplate<>(failedFactory), options).tick();
    } finally {
      failedFactory.destroy();
    }
    assertThat(
            jdbc.queryForObject("select status from api_outbox where data_id=?", String.class, id))
        .isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("select count(*) from api_data", Long.class)).isEqualTo(1);
    jdbc.update("update api_outbox set next_attempt_at=created_at");
    new OutboxPublisher(store, template, options).tick();
    store.saveResult(false, "final poll failure");
    new OutboxPublisher(store, template, options).tick();
    assertThat(
            jdbc.queryForObject("select count(*) from api_outbox where status='SENT'", Long.class))
        .isEqualTo(2);
    var consumerConfig = new HashMap<String, Object>();
    consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
    consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    try (var consumer = new KafkaConsumer<String, String>(consumerConfig)) {
      consumer.subscribe(List.of("api-data", "api-errors"));
      var records = new HashMap<String, String>();
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (records.size() < 2 && System.nanoTime() < deadline) {
        for (var record : consumer.poll(Duration.ofMillis(200))) {
          records.put(record.topic(), record.value());
          if (record.topic().equals("api-data")) assertThat(record.key()).isEqualTo(id.toString());
        }
      }
      assertThat(records.get("api-data")).isEqualTo(originalEvent);
      assertThat(records.get("api-errors")).contains("final poll failure", "\"success\":false");
    }
  }
}
