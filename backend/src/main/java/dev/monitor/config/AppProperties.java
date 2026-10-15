package dev.monitor.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("app")
public class AppProperties {
  @Valid private Monitor monitor = new Monitor();
  @Valid private Outbox outbox = new Outbox();
  @Valid private Auth auth = new Auth();
  private List<String> corsOrigins = List.of();

  @Data
  public static class Monitor {
    private boolean enabled = true;
    @NotBlank private String cron = "0 * * * * *";
    @NotBlank private String zone = "UTC";

    @NotNull
    private URI url =
        URI.create("https://api.frankfurter.dev/v2/rates?base=EUR&quotes=USD,GBP,CHF");

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2),
        readTimeout = Duration.ofSeconds(5),
        requestTimeout = Duration.ofSeconds(7),
        cycleTimeout = Duration.ofSeconds(50);

    @Min(1)
    @Max(65536)
    private int maxResponseBytes = 65536;

    @Valid private Retry retry = new Retry();

    @AssertTrue(message = "Monitor URL must be an absolute HTTP(S) URL without credentials")
    public boolean isValidUrl() {
      return url != null
          && ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
          && url.getHost() != null
          && url.getUserInfo() == null;
    }

    @AssertTrue(message = "Monitor cron and time zone must be valid")
    public boolean isValidSchedule() {
      if (cron == null || !CronExpression.isValidExpression(cron) || zone == null) return false;
      try {
        ZoneId.of(zone);
        return true;
      } catch (java.time.DateTimeException ex) {
        return false;
      }
    }

    @AssertTrue(
        message = "Monitor durations must be at least 1ms and connect/read <= request <= cycle")
    public boolean isValidDurations() {
      return positive(connectTimeout)
          && positive(readTimeout)
          && positive(requestTimeout)
          && positive(cycleTimeout)
          && connectTimeout.compareTo(requestTimeout) <= 0
          && readTimeout.compareTo(requestTimeout) <= 0
          && requestTimeout.compareTo(cycleTimeout) <= 0;
    }
  }

  @Data
  public static class Retry {
    @Min(1)
    private int maxAttempts = 3;

    @NotNull
    private Duration initialDelay = Duration.ofSeconds(1), maxDelay = Duration.ofSeconds(30);

    @DecimalMin("1.0")
    private double multiplier = 2;

    @AssertTrue
    public boolean isValidDurations() {
      return positive(initialDelay) && positive(maxDelay) && maxDelay.compareTo(initialDelay) >= 0;
    }
  }

  @Data
  public static class Outbox {
    private boolean enabled = true;
    @NotNull
    private Duration pollDelay = Duration.ofSeconds(1),
        initialDelay = Duration.ofSeconds(5),
        maxDelay = Duration.ofMinutes(5),
        lease = Duration.ofSeconds(60),
        sendTimeout = Duration.ofSeconds(25),
        maxBlock = Duration.ofSeconds(5),
        requestTimeout = Duration.ofSeconds(10),
        deliveryTimeout = Duration.ofSeconds(20);
    @Min(1)
    private int maxEventsPerTick = 10, maxAttempts = 20;

    @DecimalMin("1.0")
    private double multiplier = 2;

    @AssertTrue(
        message =
            "Outbox durations must fit Kafka millisecond limits and lease must exceed max.block + send timeout + 5 seconds")
    public boolean isValidDurations() {
      return positive(pollDelay)
          && positive(initialDelay)
          && positive(maxDelay)
          && maxDelay.compareTo(initialDelay) >= 0
          && positive(sendTimeout)
          && positive(lease)
          && positive(maxBlock)
          && positive(requestTimeout)
          && positive(deliveryTimeout)
          && requestTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0
          && deliveryTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0
          && deliveryTimeout.compareTo(requestTimeout.plusMillis(5)) >= 0
          && sendTimeout.compareTo(deliveryTimeout) >= 0
          && lease.compareTo(sendTimeout.plus(maxBlock).plusSeconds(5)) > 0;
    }
  }

  @Data
  public static class Auth {
    @NotBlank
    @Size(min = 43)
    private String secret;

    @NotBlank private String issuer = "api-monitor", audience = "monitor-ui";
    @NotNull private Duration accessTtl = Duration.ofMinutes(15), refreshTtl = Duration.ofDays(7);
    private boolean cookieSecure = true;

    @AssertTrue
    public boolean isValidDurations() {
      return positive(accessTtl)
          && positive(refreshTtl)
          && accessTtl.compareTo(Duration.ofSeconds(1)) >= 0
          && refreshTtl.compareTo(Duration.ofSeconds(1)) >= 0;
    }
  }

  private static boolean positive(Duration d) {
    return d != null
        && d.compareTo(Duration.ofMillis(1)) >= 0
        && d.compareTo(Duration.ofNanos(Long.MAX_VALUE)) <= 0;
  }
}
