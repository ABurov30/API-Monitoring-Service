package dev.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.monitor.config.AppProperties;
import dev.monitor.kafka.OutboxStore;
import dev.monitor.service.PollFailure;
import dev.monitor.service.PollService;
import dev.monitor.service.RateClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateClientTest {
  static final String RATES =
      "[{\"date\":\"2026-10-14\",\"base\":\"EUR\",\"quote\":\"USD\",\"rate\":1.1},{\"date\":\"2026-10-14\",\"base\":\"EUR\",\"quote\":\"GBP\",\"rate\":0.8},{\"date\":\"2026-10-14\",\"base\":\"EUR\",\"quote\":\"CHF\",\"rate\":0.9}]";
  MockWebServer server;
  AppProperties properties;
  RateClient client;
  OutboxStore store;
  PollService poll;

  @BeforeEach
  void setup() throws Exception {
    server = new MockWebServer();
    server.start();
    properties = new AppProperties();
    properties.getMonitor().setUrl(server.url("/rates").uri());
    properties.getMonitor().getRetry().setInitialDelay(Duration.ofMillis(1));
    properties.getMonitor().getRetry().setMaxDelay(Duration.ofMillis(2));
    client = new RateClient(properties, new ObjectMapper(), Clock.systemUTC());
    store = mock(OutboxStore.class);
    poll = new PollService(client, properties, Clock.systemUTC(), store, new ObjectMapper());
  }

  @AfterEach
  void close() throws Exception {
    client.close();
    server.shutdown();
  }

  @Test
  void preservesOriginalBody() {
    server.enqueue(new MockResponse().setBody(RATES));
    assertThat(client.fetch(Duration.ofSeconds(1))).isEqualTo(RATES);
  }

  @Test
  void retriesTwiceThenStoresExactlyOneSuccess() {
    server.enqueue(new MockResponse().setResponseCode(503));
    server.enqueue(new MockResponse().setResponseCode(408));
    server.enqueue(new MockResponse().setBody(RATES));
    poll.poll();
    assertThat(server.getRequestCount()).isEqualTo(3);
    verify(store).saveResult(true, RATES);
    verify(store, times(1)).saveResult(anyBoolean(), anyString());
  }

  @Test
  void exhaustedRetriesProduceOneSafeError() {
    for (int i = 0; i < 3; i++)
      server.enqueue(new MockResponse().setResponseCode(500).setBody("private server details"));
    poll.poll();
    assertThat(server.getRequestCount()).isEqualTo(3);
    verify(store).saveResult(false, "{\"category\":\"HTTP\",\"httpStatus\":500,\"attempts\":3}");
  }

  @Test
  void clientErrorIsNotRetried() {
    server.enqueue(new MockResponse().setResponseCode(403));
    poll.poll();
    assertThat(server.getRequestCount()).isEqualTo(1);
    verify(store).saveResult(false, "{\"category\":\"HTTP\",\"httpStatus\":403,\"attempts\":1}");
  }

  @Test
  void invalidJsonIsNotRetried() {
    server.enqueue(new MockResponse().setBody("<html>invalid</html>"));
    poll.poll();
    assertThat(server.getRequestCount()).isEqualTo(1);
    verify(store)
        .saveResult(false, "{\"category\":\"INVALID_RESPONSE\",\"httpStatus\":200,\"attempts\":1}");
  }

  @Test
  void rejectsMissingEmptyDuplicateAndNegativeRates() {
    for (String body :
        new String[] {
          "",
          "[]",
          "{}",
          RATES.replace("GBP", "USD"),
          RATES.replace("0.8", "-1"),
          RATES.replace("2026-10-14", "bad-date")
        }) {
      server.enqueue(new MockResponse().setBody(body));
      assertThatThrownBy(() -> client.fetch(Duration.ofSeconds(1)))
          .isInstanceOf(PollFailure.class)
          .hasMessage("INVALID_RESPONSE");
    }
  }

  @Test
  void limitsBytesBeforeParsing() {
    properties.getMonitor().setMaxResponseBytes(20);
    server.enqueue(new MockResponse().setBody(RATES));
    assertThatThrownBy(() -> client.fetch(Duration.ofSeconds(1)))
        .isInstanceOf(PollFailure.class)
        .hasMessage("RESPONSE_TOO_LARGE");
  }

  @Test
  void readTimeoutIsBounded() {
    properties.getMonitor().setReadTimeout(Duration.ofMillis(50));
    server.enqueue(new MockResponse().setBody(RATES).setBodyDelay(1, TimeUnit.SECONDS));
    long start = System.nanoTime();
    assertThatThrownBy(() -> client.fetch(Duration.ofMillis(200))).isInstanceOf(PollFailure.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void longRetryAfterDefersNextTick() {
    server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120"));
    assertThat(poll.poll()).isTrue();
    assertThat(poll.poll()).isFalse();
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void httpDateRetryAfterDefersNextTick() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "Wed, 14 Oct 2037 12:00:00 GMT"));
    poll.poll();
    assertThat(poll.poll()).isFalse();
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void invalidRetryAfterUsesNormalBackoff() {
    server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "invalid"));
    server.enqueue(new MockResponse().setBody(RATES));
    poll.poll();
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void retryAfterSecondsIsHonored() {
    server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
    server.enqueue(new MockResponse().setBody(RATES));
    long start = System.nanoTime();
    poll.poll();
    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void cycleBudgetDoesNotStartAnotherRequest() {
    properties.getMonitor().setRequestTimeout(Duration.ofMillis(100));
    properties.getMonitor().setCycleTimeout(Duration.ofMillis(150));
    properties.getMonitor().getRetry().setInitialDelay(Duration.ofMillis(100));
    properties.getMonitor().getRetry().setMaxDelay(Duration.ofMillis(100));
    server.enqueue(new MockResponse().setResponseCode(500));
    poll.poll();
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void clockControlsDeferral() {
    var fixed = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
    try (var c = new RateClient(properties, new ObjectMapper(), fixed)) {
      var service = new PollService(c, properties, fixed, store, new ObjectMapper());
      server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120"));
      service.poll();
      assertThat(service.poll()).isFalse();
    }
  }

  @Test
  void totalTimeoutStopsAResponseThatKeepsSendingBytes() {
    properties.getMonitor().setReadTimeout(Duration.ofMillis(200));
    server.enqueue(new MockResponse().setBody(RATES).throttleBody(1, 30, TimeUnit.MILLISECONDS));
    long start = System.nanoTime();
    assertThatThrownBy(() -> client.fetch(Duration.ofMillis(300))).isInstanceOf(PollFailure.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
  }
}
