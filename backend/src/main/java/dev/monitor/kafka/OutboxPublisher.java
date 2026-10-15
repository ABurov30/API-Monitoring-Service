package dev.monitor.kafka;

import dev.monitor.config.AppProperties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {
  private final OutboxStore store;
  private final KafkaTemplate<String, String> kafka;
  private final AppProperties properties;
  private final AtomicBoolean running = new AtomicBoolean();

  @Scheduled(fixedDelayString = "${app.outbox.poll-delay:1s}")
  public void tick() {
    if (!properties.getOutbox().isEnabled() || !running.compareAndSet(false, true)) return;
    try {
      for (int i = 0; i < properties.getOutbox().getMaxEventsPerTick(); i++) {
        var claim = store.claim();
        if (claim == null) break;
        try {
          kafka
              .send(claim.topic(), claim.dataId().toString(), claim.payload())
              .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
          store.sent(claim);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          store.failed(claim);
          break;
        } catch (Exception ex) {
          store.failed(claim);
        }
      }
    } catch (Exception ex) {
      log.error("Outbox tick failed", ex);
    } finally {
      running.set(false);
    }
  }
}
