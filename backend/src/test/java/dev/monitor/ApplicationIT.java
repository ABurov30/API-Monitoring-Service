package dev.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.monitor.config.AppProperties;
import dev.monitor.dto.DataRequest;
import dev.monitor.kafka.OutboxStore;
import dev.monitor.service.AuthService;
import dev.monitor.service.DataService;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ApplicationIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine3.22");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @Autowired AuthService auth;
  @Autowired DataService data;
  @Autowired OutboxStore outbox;
  @Autowired ObjectMapper mapper;
  @Autowired AppProperties properties;

  @BeforeEach
  void clean() {
    jdbc.execute("truncate refresh_tokens,user_roles,users,api_outbox,api_data");
    for (String name : List.of("user", "admin")) {
      UUID id = UUID.randomUUID();
      jdbc.update(
          "insert into users values(?,?,?)", id, name, passwords.encode("correct-password"));
      jdbc.update("insert into user_roles values(?,?)", id, "ROLE_" + name.toUpperCase());
    }
  }

  @Test
  void roleMatrix() throws Exception {
    mvc.perform(get("/status")).andExpect(status().isUnauthorized());
    mvc.perform(get("/data")).andExpect(status().isUnauthorized());
    mvc.perform(get("/status").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.total").doesNotExist());
    for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
      mvc.perform(
              request(
                      org.springframework.http.HttpMethod.valueOf(method),
                      method.equals("GET") || method.equals("POST")
                          ? "/data"
                          : "/data/" + UUID.randomUUID())
                  .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                  .contentType("application/json")
                  .content("{\"success\":false,\"payload\":\"text\"}"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void crudContractAndValidation() throws Exception {
    var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    String body =
        mvc.perform(
                post("/data")
                    .with(admin)
                    .contentType("application/json")
                    .content("{\"success\":false,\"payload\":\"text\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = mapper.readTree(body);
    String id = node.path("id").asText();
    mvc.perform(
            put("/data/" + id)
                .with(admin)
                .contentType("application/json")
                .content("{\"success\":true,\"payload\":\"changed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdAt").value(node.path("createdAt").asText()));
    mvc.perform(get("/data/" + id).with(admin)).andExpect(jsonPath("$.payload").value("changed"));
    for (String invalid :
        List.of(
            "{}",
            "{\"success\":true,\"payload\":\" \"}",
            "{\"success\":false,\"payload\":\"text\",\"id\":\"bad\"}"))
      mvc.perform(post("/data").with(admin).contentType("application/json").content(invalid))
          .andExpect(status().isBadRequest());
    mvc.perform(get("/data/not-a-uuid").with(admin)).andExpect(status().isBadRequest());
    mvc.perform(delete("/data/" + id).with(admin)).andExpect(status().isNoContent());
    mvc.perform(get("/data/" + id).with(admin)).andExpect(status().isNotFound());
    assertThat(jdbc.queryForObject("select count(*) from api_outbox", Long.class)).isZero();
  }

  @Test
  void listingIsLimitedAndTotalIsFull() {
    for (int i = 0; i < 13; i++) data.create(new DataRequest(true, "record-" + i));
    var result = data.list();
    assertThat(result.total()).isEqualTo(13);
    assertThat(result.items()).hasSize(10);
    assertThat(result.items().get(0).payload()).isEqualTo("record-12");
  }

  @Test
  void refreshIsSingleUseAndLogoutRevokesIt() {
    var login = auth.login("user", "correct-password");
    var fresh = auth.refresh(login.refresh());
    assertThat(fresh.refresh()).isNotEqualTo(login.refresh());
    assertThatThrownBy(() -> auth.refresh(login.refresh()))
        .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    auth.logout(fresh.refresh());
    auth.logout(fresh.refresh());
    assertThatThrownBy(() -> auth.refresh(fresh.refresh()))
        .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    assertThatThrownBy(() -> auth.refresh(null))
        .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
  }

  @Test
  void concurrentRefreshHasOneWinner() throws Exception {
    var login = auth.login("admin", "correct-password");
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> exchange =
          () -> {
            try {
              auth.refresh(login.refresh());
              return true;
            } catch (org.springframework.security.core.AuthenticationException e) {
              return false;
            }
          };
      var results = pool.invokeAll(List.of(exchange, exchange));
      assertThat(
              results.stream()
                  .filter(
                      f -> {
                        try {
                          return f.get();
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      })
                  .count())
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void expiredRefreshAndWrongLoginAreRejected() {
    var s = auth.login("user", "correct-password");
    jdbc.update(
        "update refresh_tokens set expires_at=?", Timestamp.from(Instant.now().minusSeconds(10)));
    assertThatThrownBy(() -> auth.refresh(s.refresh()))
        .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    assertThatThrownBy(() -> auth.login("user", "wrong"))
        .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    assertThatThrownBy(() -> auth.login("absent", "wrong"))
        .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
  }

  @Test
  void realCsrfCookieFlowAndBearerAuthentication() throws Exception {
    for (String path : List.of("/auth/refresh", "/auth/logout")) {
      mvc.perform(post(path)).andExpect(status().isForbidden());
    }
    mvc.perform(
            post("/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"user\",\"password\":\"correct-password\"}"))
        .andExpect(status().isForbidden());
    var csrfResult =
        mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
    Cookie csrfCookie = csrfResult.getCookie("XSRF-TOKEN");
    var token = mapper.readTree(csrfResult.getContentAsString()).path("token").asText();
    mvc.perform(
            post("/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", "invalid")
                .contentType("application/json")
                .content("{\"username\":\"user\",\"password\":\"correct-password\"}"))
        .andExpect(status().isForbidden());
    var result =
        mvc.perform(
                post("/auth/login")
                    .cookie(csrfCookie)
                    .header("X-XSRF-TOKEN", token)
                    .contentType("application/json")
                    .content("{\"username\":\"user\",\"password\":\"correct-password\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(result.getHeaders("Set-Cookie").toString())
        .contains("HttpOnly", "Path=/", "SameSite=Lax");
    String access = mapper.readTree(result.getContentAsString()).path("accessToken").asText();
    mvc.perform(get("/status").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
    mvc.perform(get("/data").cookie(result.getCookie("refresh")))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/auth/logout").with(csrf()).cookie(result.getCookie("refresh")))
        .andExpect(status().isNoContent());
    mvc.perform(post("/auth/refresh").with(csrf()).cookie(result.getCookie("refresh")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void outboxSnapshotSurvivesCrudAndLeaseRejectsOldWorker() {
    UUID id = outbox.saveResult(true, "original");
    var first = outbox.claim();
    assertThat(first.topic()).isEqualTo("api-data");
    data.update(id, new DataRequest(false, "edited"));
    data.delete(id);
    assertThat(jdbc.queryForObject("select event_payload from api_outbox", String.class))
        .contains("original")
        .doesNotContain("edited");
    assertThat(outbox.claim()).isNull();
    jdbc.update(
        "update api_outbox set locked_until=?", Timestamp.from(Instant.now().minusSeconds(1)));
    var second = outbox.claim();
    assertThat(second.token()).isNotEqualTo(first.token());
    assertThat(outbox.sent(first)).isFalse();
    assertThat(outbox.failed(first)).isFalse();
    assertThat(outbox.sent(second)).isTrue();
    assertThat(jdbc.queryForObject("select status from api_outbox", String.class))
        .isEqualTo("SENT");
  }

  @Test
  void finalFailureAndExplicitReplayPreserveEventId() {
    outbox.saveResult(false, "failed poll");
    jdbc.update("update api_outbox set attempts=?", properties.getOutbox().getMaxAttempts() - 1);
    var claim = outbox.claim();
    assertThat(claim.topic()).isEqualTo("api-errors");
    outbox.failed(claim);
    assertThat(jdbc.queryForObject("select status from api_outbox", String.class))
        .isEqualTo("FAILED");
    assertThat(outbox.claim()).isNull();
    jdbc.update(
        "update api_outbox set status='PENDING', attempts=0,next_attempt_at=created_at where id=?",
        claim.id());
    assertThat(outbox.claim().id()).isEqualTo(claim.id());
  }

  @Test
  void expiredFinalLeaseBecomesFailed() {
    outbox.saveResult(true, "x");
    jdbc.update(
        "update api_outbox set attempts=?,status='IN_PROGRESS',locked_until=now()-interval '1 second'",
        properties.getOutbox().getMaxAttempts());
    assertThat(outbox.claim()).isNull();
    assertThat(jdbc.queryForObject("select status from api_outbox", String.class))
        .isEqualTo("FAILED");
  }

  @Test
  void resultAndOutboxRollbackTogether() {
    jdbc.execute("alter table api_outbox add constraint test_reject check (topic='rejected')");
    try {
      assertThatThrownBy(() -> outbox.saveResult(true, "x")).isInstanceOf(RuntimeException.class);
      assertThat(jdbc.queryForObject("select count(*) from api_data", Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from api_outbox", Long.class)).isZero();
    } finally {
      jdbc.execute("alter table api_outbox drop constraint test_reject");
    }
  }

  @Test
  void concurrentClaimOnlyHasOneWinner() throws Exception {
    outbox.saveResult(true, "x");
    var pool = Executors.newFixedThreadPool(2);
    try {
      var results =
          pool.invokeAll(List.<Callable<OutboxStore.Claim>>of(outbox::claim, outbox::claim));
      int count = 0;
      for (var result : results) if (result.get() != null) count++;
      assertThat(count).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void utf8AndTotalRequestLimits() throws Exception {
    var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mvc.perform(
            post("/data")
                .with(admin)
                .contentType("application/json")
                .content(mapper.writeValueAsString(new DataRequest(true, "я".repeat(32769)))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/data").with(admin).contentType("application/json").content("x".repeat(524289)))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void completeHttpCycleCommitsExactlyOneResultAndOneSnapshot() throws Exception {
    try (var server = new okhttp3.mockwebserver.MockWebServer()) {
      server.start();
      var settings = new AppProperties();
      settings.getMonitor().setUrl(server.url("/rates").uri());
      settings.getMonitor().getRetry().setInitialDelay(java.time.Duration.ofMillis(1));
      settings.getMonitor().getRetry().setMaxDelay(java.time.Duration.ofMillis(2));
      server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503));
      server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503));
      server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody(RateClientTest.RATES));
      try (var client =
          new dev.monitor.service.RateClient(settings, mapper, java.time.Clock.systemUTC())) {
        new dev.monitor.service.PollService(
                client, settings, java.time.Clock.systemUTC(), outbox, mapper)
            .poll();
      }
      assertThat(server.getRequestCount()).isEqualTo(3);
      assertThat(data.list().total()).isEqualTo(1);
      assertThat(data.list().items().get(0).payload()).isEqualTo(RateClientTest.RATES);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from api_outbox where topic='api-data'", Long.class))
          .isEqualTo(1);
    }
  }
}
