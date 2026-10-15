package dev.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.monitor.config.AppProperties;
import dev.monitor.dto.DataRequest;
import jakarta.validation.Validation;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
  @Test
  void validatesRequiredValuesAndUtf8Payload() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      var p = new AppProperties();
      assertThat(validator.validate(p)).isNotEmpty();
      p.getAuth().setSecret("A".repeat(43));
      assertThat(validator.validate(p)).isEmpty();
      p.getOutbox().setLease(Duration.ofSeconds(10));
      p.getMonitor().setCycleTimeout(Duration.ofSeconds(1));
      p.getMonitor().getRetry().setInitialDelay(Duration.ZERO);
      p.getAuth().setAccessTtl(Duration.ZERO);
      assertThat(validator.validate(p)).hasSizeGreaterThanOrEqualTo(4);
      assertThat(validator.validate(new DataRequest(false, "я".repeat(32768)))).isEmpty();
      assertThat(validator.validate(new DataRequest(null, " "))).isNotEmpty();
      assertThat(validator.validate(new DataRequest(true, "я".repeat(32769)))).isNotEmpty();
    }
  }

  @Test
  void rejectsInvalidEndpointAndScheduleAtStartup() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      for (Consumer<AppProperties> invalid :
          List.<Consumer<AppProperties>>of(
              p -> p.getMonitor().setUrl(URI.create("file:///tmp/rates")),
              p -> p.getMonitor().setUrl(URI.create("/rates")),
              p -> p.getMonitor().setUrl(URI.create("https://user:password@example.com/rates")),
              p -> p.getMonitor().setUrl(null),
              p -> p.getMonitor().setCron("not a cron"),
              p -> p.getMonitor().setCron(null),
              p -> p.getMonitor().setZone("Unknown/Zone"),
              p -> p.getMonitor().setZone(null))) {
        var settings = new AppProperties();
        settings.getAuth().setSecret("A".repeat(43));
        invalid.accept(settings);
        assertThat(validator.validate(settings)).isNotEmpty();
      }
    }
  }

  @Test
  void rejectsUnsafeOutboxTimeoutsAndRetryIntervals() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      for (Consumer<AppProperties.Outbox> invalid :
          List.<Consumer<AppProperties.Outbox>>of(
              o -> o.setRequestTimeout(Duration.ZERO),
              o -> o.setDeliveryTimeout(Duration.ZERO),
              o -> o.setDeliveryTimeout(o.getRequestTimeout()),
              o -> o.setSendTimeout(o.getDeliveryTimeout().minusMillis(1)),
              o -> o.setInitialDelay(Duration.ZERO),
              o -> o.setMaxDelay(Duration.ZERO),
              o -> o.setMaxDelay(o.getInitialDelay().minusMillis(1)))) {
        var settings = new AppProperties();
        settings.getAuth().setSecret("A".repeat(43));
        invalid.accept(settings.getOutbox());
        assertThat(validator.validate(settings)).isNotEmpty();
      }
    }
  }

  @Test
  void rejectsDurationsThatRoundToZeroOrOverflowTransportLimits() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      for (Consumer<AppProperties> invalid :
          List.<Consumer<AppProperties>>of(
              p -> p.getMonitor().setConnectTimeout(Duration.ofNanos(1)),
              p -> p.getMonitor().setCycleTimeout(Duration.ofDays(365000)),
              p -> p.getMonitor().getRetry().setInitialDelay(Duration.ofNanos(1)),
              p -> p.getOutbox().setMaxBlock(Duration.ofNanos(1)),
              p -> {
                var outbox = p.getOutbox();
                outbox.setRequestTimeout(Duration.ofMillis(2147483648L));
                outbox.setDeliveryTimeout(outbox.getRequestTimeout().plusMillis(5));
                outbox.setSendTimeout(outbox.getDeliveryTimeout().plusSeconds(1));
                outbox.setLease(outbox.getSendTimeout().plusSeconds(30));
              },
              p -> {
                var outbox = p.getOutbox();
                outbox.setDeliveryTimeout(Duration.ofMillis(2147483648L));
                outbox.setSendTimeout(outbox.getDeliveryTimeout().plusSeconds(1));
                outbox.setLease(outbox.getSendTimeout().plusSeconds(30));
              },
              p -> p.getAuth().setAccessTtl(Duration.ofMillis(999)),
              p -> p.getAuth().setRefreshTtl(Duration.ofMillis(999)))) {
        var settings = new AppProperties();
        settings.getAuth().setSecret("A".repeat(43));
        invalid.accept(settings);
        assertThat(validator.validate(settings)).isNotEmpty();
      }
      var settings = new AppProperties();
      settings.getAuth().setSecret("A".repeat(43));
      settings.getMonitor().setConnectTimeout(Duration.ofMillis(1));
      settings.getAuth().setAccessTtl(Duration.ofSeconds(1));
      settings.getAuth().setRefreshTtl(Duration.ofSeconds(1));
      assertThat(validator.validate(settings)).isEmpty();
    }
  }
}
