package dev.monitor.scheduler;

import dev.monitor.config.AppProperties;
import dev.monitor.service.PollService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PollScheduler {
  private final PollService poller;
  private final AppProperties properties;
  private final AtomicBoolean running = new AtomicBoolean();

  @Scheduled(cron = "${app.monitor.cron:0 * * * * *}", zone = "${app.monitor.zone:UTC}")
  public void tick() {
    if (!properties.getMonitor().isEnabled() || !running.compareAndSet(false, true)) return;
    try {
      poller.poll();
    } catch (Exception ex) {
      log.error("Poll persistence failed; next cycle remains scheduled", ex);
    } finally {
      running.set(false);
    }
  }
}
