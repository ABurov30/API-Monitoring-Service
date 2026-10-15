package dev.monitor;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.monitor.config.AppProperties;
import dev.monitor.kafka.OutboxPublisher;
import dev.monitor.kafka.OutboxStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherTest {
  @Test
  void onlyMarksSentAfterBrokerAcknowledges() {
    var store = mock(OutboxStore.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var claim =
        new OutboxStore.Claim(
            UUID.randomUUID(), UUID.randomUUID(), "api-data", "{}", 1, UUID.randomUUID());
    when(store.claim()).thenReturn(claim, null);
    when(kafka.send(claim.topic(), claim.dataId().toString(), claim.payload()))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    new OutboxPublisher(store, kafka, new AppProperties()).tick();
    var order = inOrder(store, kafka);
    order.verify(store).claim();
    order.verify(kafka).send(claim.topic(), claim.dataId().toString(), claim.payload());
    order.verify(store).sent(claim);
    verify(store, never()).failed(claim);
  }

  @Test
  void brokerFailureKeepsResultAndSchedulesRetry() {
    var store = mock(OutboxStore.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var claim =
        new OutboxStore.Claim(
            UUID.randomUUID(), UUID.randomUUID(), "api-errors", "{}", 1, UUID.randomUUID());
    when(store.claim()).thenReturn(claim, null);
    when(kafka.send(claim.topic(), claim.dataId().toString(), claim.payload()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
    new OutboxPublisher(store, kafka, new AppProperties()).tick();
    verify(store).failed(claim);
    verify(store, never()).sent(claim);
  }

  @Test
  void disabledPublisherDoesNotTouchDatabase() {
    var store = mock(OutboxStore.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var p = new AppProperties();
    p.getOutbox().setEnabled(false);
    new OutboxPublisher(store, kafka, p).tick();
    verifyNoInteractions(store, kafka);
  }

  @Test
  void databaseFailureDoesNotEscapeScheduler() {
    var store = mock(OutboxStore.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(store.claim()).thenThrow(new IllegalStateException());
    new OutboxPublisher(store, kafka, new AppProperties()).tick();
    verifyNoInteractions(kafka);
  }
}
