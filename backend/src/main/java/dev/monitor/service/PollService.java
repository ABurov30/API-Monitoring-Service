package dev.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.monitor.config.AppProperties;
import dev.monitor.kafka.OutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PollService {
  private final RateClient client;
  private final AppProperties properties;
  private final Clock clock;
  private final OutboxStore store;
  private final ObjectMapper mapper;
  private volatile Instant deferredUntil = Instant.MIN;

  public boolean poll() {
    if (clock.instant().isBefore(deferredUntil)) return false;
    var options = properties.getMonitor();
    var retry = options.getRetry();
    UUID cycle = UUID.randomUUID();
    long started = System.nanoTime();
    long budget = options.getCycleTimeout().toNanos();
    int[] attempts = {0};
    var template = new RetryTemplate();
    template.setRetryPolicy(
        new SimpleRetryPolicy(retry.getMaxAttempts(), java.util.Map.of(PollFailure.class, true)) {
          @Override
          public boolean canRetry(RetryContext c) {
            if (c.getLastThrowable() instanceof PollFailure f && !f.isRetryable()) return false;
            return super.canRetry(c);
          }
        });
    template.setBackOffPolicy(
        new BackOffPolicy() {
          @Override
          public BackOffContext start(RetryContext context) {
            return new BackOffContext() {};
          }

          @Override
          public void backOff(BackOffContext context) throws BackOffInterruptedException {
            long delay =
                (long)
                    Math.min(
                        retry.getMaxDelay().toMillis(),
                        retry.getInitialDelay().toMillis()
                            * Math.pow(retry.getMultiplier(), attempts[0] - 1));
            if (deferredUntil.isAfter(clock.instant()))
              delay =
                  Math.max(delay, Duration.between(clock.instant(), deferredUntil).toMillis() + 1);
            if (Duration.ofMillis(delay).toNanos() + options.getRequestTimeout().toNanos()
                > budget - (System.nanoTime() - started))
              throw new BackOffInterruptedException("Cycle budget exhausted");
            try {
              Thread.sleep(delay);
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new BackOffInterruptedException("Interrupted", ex);
            }
          }
        });
    PollFailure[] last = {null};
    String payload;
    boolean success;
    try {
      payload =
          template.execute(
              c -> {
                long remaining = budget - (System.nanoTime() - started);
                if (remaining < options.getRequestTimeout().toNanos())
                  throw new PollFailure("CYCLE_TIMEOUT", null, false, null);
                attempts[0]++;
                log.info("Poll cycle={} attempt={}", cycle, attempts[0]);
                try {
                  return client.fetch(Duration.ofNanos(remaining));
                } catch (PollFailure ex) {
                  last[0] = ex;
                  if (ex.getRetryAfter() != null && ex.getRetryAfter().isAfter(deferredUntil))
                    deferredUntil = ex.getRetryAfter();
                  throw ex;
                }
              });
      success = true;
    } catch (Exception ex) {
      success = false;
      var failure = last[0];
      var error = new LinkedHashMap<String, Object>();
      error.put("category", failure == null ? "CYCLE_TIMEOUT" : failure.getCategory());
      error.put("httpStatus", failure == null ? null : failure.getStatus());
      error.put("attempts", attempts[0]);
      try {
        payload = mapper.writeValueAsString(error);
      } catch (JsonProcessingException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    store.saveResult(success, payload);
    log.info("Poll cycle={} attempts={} success={}", cycle, attempts[0], success);
    return true;
  }
}
