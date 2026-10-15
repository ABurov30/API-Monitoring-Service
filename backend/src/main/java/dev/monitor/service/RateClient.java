package dev.monitor.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.monitor.config.AppProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
public class RateClient implements AutoCloseable {
  private final AppProperties properties;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ScheduledExecutorService timeout =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var thread = new Thread(r, "http-deadline");
            thread.setDaemon(true);
            return thread;
          });

  public RateClient(AppProperties properties, ObjectMapper mapper, Clock clock) {
    this.properties = properties;
    this.mapper = mapper;
    this.clock = clock;
  }

  public String fetch(Duration budget) {
    var p = properties.getMonitor();
    long limit = Math.max(1, Math.min(budget.toMillis(), p.getRequestTimeout().toMillis()));
    var request = new HttpGet(p.getUrl());
    request.setHeader("Accept", "application/json");
    request.setConfig(
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(limit))
            .setConnectTimeout(
                Timeout.ofMilliseconds(Math.min(limit, p.getConnectTimeout().toMillis())))
            .setResponseTimeout(
                Timeout.ofMilliseconds(Math.min(limit, p.getReadTimeout().toMillis())))
            .build());
    AtomicBoolean expired = new AtomicBoolean();
    // Cancellation closes the active transport; the request itself stays synchronous.
    var deadline =
        timeout.schedule(
            () -> {
              expired.set(true);
              request.cancel();
            },
            limit,
            TimeUnit.MILLISECONDS);
    try (var http =
        HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build()) {
      return http.execute(
          request,
          response -> {
            int status = response.getCode();
            if (status < 200 || status >= 300) {
              var header = response.getFirstHeader("Retry-After");
              throw new PollFailure(
                  "HTTP",
                  status,
                  status == 408 || status == 429 || status >= 500,
                  status == 429 && header != null ? retryAfter(header.getValue()) : null);
            }
            if (response.getEntity() == null)
              throw new PollFailure("INVALID_RESPONSE", status, false, null);
            try (var input = response.getEntity().getContent();
                var output = new ByteArrayOutputStream()) {
              byte[] buffer = new byte[4096];
              int n;
              while ((n = input.read(buffer)) != -1) {
                if (expired.get()) throw new SocketTimeoutException();
                if (output.size() + n > p.getMaxResponseBytes())
                  throw new PollFailure("RESPONSE_TOO_LARGE", status, false, null);
                output.write(buffer, 0, n);
              }
              if (expired.get()) throw new SocketTimeoutException();
              String raw = output.toString(StandardCharsets.UTF_8);
              validate(raw, status);
              return raw;
            }
          });
    } catch (PollFailure ex) {
      throw ex;
    } catch (IOException ex) {
      throw new PollFailure(
          expired.get() || ex instanceof SocketTimeoutException ? "TIMEOUT" : "NETWORK",
          null,
          true,
          null);
    } finally {
      deadline.cancel(false);
      request.cancel();
    }
  }

  private void validate(String raw, int status) {
    try {
      JsonNode root =
          mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
      if (root == null || !root.isArray() || root.size() != 3) throw new IllegalArgumentException();
      Set<String> quotes = new HashSet<>();
      for (JsonNode item : root) {
        if (!item.path("date").isTextual()
            || !item.path("base").asText().equals("EUR")
            || !Set.of("USD", "GBP", "CHF").contains(item.path("quote").asText())
            || !item.path("rate").isNumber()
            || item.path("rate").decimalValue().signum() <= 0) throw new IllegalArgumentException();
        LocalDate.parse(item.path("date").asText());
        quotes.add(item.path("quote").asText());
      }
      if (quotes.size() != 3) throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw new PollFailure("INVALID_RESPONSE", status, false, null);
    }
  }

  private Instant retryAfter(String value) {
    try {
      long seconds = Long.parseLong(value);
      return seconds >= 0 ? clock.instant().plusSeconds(seconds) : null;
    } catch (Exception ignored) {
      try {
        return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      } catch (Exception invalid) {
        return null;
      }
    }
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    timeout.shutdownNow();
  }
}
