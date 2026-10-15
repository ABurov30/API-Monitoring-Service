package dev.monitor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.monitor.config.AppProperties;
import dev.monitor.entity.ApiDataEntity;
import dev.monitor.repository.ApiDataRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxStore {
  private final JdbcTemplate jdbc;
  private final ApiDataRepository data;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final AppProperties properties;

  public record Event(
      int schemaVersion,
      UUID eventId,
      UUID dataId,
      Instant occurredAt,
      boolean success,
      String payload) {}

  public record Claim(
      UUID id, UUID dataId, String topic, String payload, int attempts, UUID token) {}

  @Transactional
  public UUID saveResult(boolean success, String payload) {
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var record = data.saveAndFlush(new ApiDataEntity(success, payload, now));
    UUID event = UUID.randomUUID();
    String snapshot;
    try {
      snapshot =
          mapper.writeValueAsString(new Event(1, event, record.getId(), now, success, payload));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
    jdbc.update(
        "insert into api_outbox(id,data_id,topic,event_payload,status,attempts,next_attempt_at,created_at) values(?,?,?,?,'PENDING',0,?,?)",
        event,
        record.getId(),
        success ? "api-data" : "api-errors",
        snapshot,
        Timestamp.from(now),
        Timestamp.from(now));
    return record.getId();
  }

  @Transactional
  public Claim claim() {
    var now = clock.instant();
    var options = properties.getOutbox();
    var rows =
        jdbc.query(
            "select * from api_outbox where (status='PENDING' and next_attempt_at<=?) or (status='IN_PROGRESS' and locked_until<=?) order by created_at,id limit 1 for update skip locked",
            (rs, n) ->
                new Claim(
                    rs.getObject("id", UUID.class),
                    rs.getObject("data_id", UUID.class),
                    rs.getString("topic"),
                    rs.getString("event_payload"),
                    rs.getInt("attempts"),
                    UUID.randomUUID()),
            Timestamp.from(now),
            Timestamp.from(now));
    if (rows.isEmpty()) return null;
    var row = rows.get(0);
    if (row.attempts() >= options.getMaxAttempts()) {
      jdbc.update(
          "update api_outbox set status='FAILED',locked_until=null,lock_token=null,last_error='Attempt limit exhausted' where id=?",
          row.id());
      log.warn("Outbox eventId={} state=FAILED", row.id());
      return null;
    }
    jdbc.update(
        "update api_outbox set status='IN_PROGRESS', attempts=attempts+1, lock_token=?,locked_until=? where id=?",
        row.token(),
        Timestamp.from(now.plus(options.getLease())),
        row.id());
    log.info("Outbox eventId={} attempt={} state=IN_PROGRESS", row.id(), row.attempts() + 1);
    return new Claim(
        row.id(), row.dataId(), row.topic(), row.payload(), row.attempts() + 1, row.token());
  }

  @Transactional
  public boolean sent(Claim claim) {
    int count =
        jdbc.update(
            "update api_outbox set status='SENT',sent_at=?,locked_until=null,lock_token=null,last_error=null where id=? and lock_token=? and status='IN_PROGRESS'",
            Timestamp.from(clock.instant()),
            claim.id(),
            claim.token());
    log.info("Outbox eventId={} state=SENT updated={}", claim.id(), count);
    return count == 1;
  }

  @Transactional
  public boolean failed(Claim claim) {
    var o = properties.getOutbox();
    String status = claim.attempts() >= o.getMaxAttempts() ? "FAILED" : "PENDING";
    long delay =
        (long)
            Math.min(
                o.getMaxDelay().toMillis(),
                o.getInitialDelay().toMillis() * Math.pow(o.getMultiplier(), claim.attempts() - 1));
    int count =
        jdbc.update(
            "update api_outbox set status=?,next_attempt_at=?,locked_until=null,lock_token=null,last_error='Kafka delivery not confirmed' where id=? and lock_token=? and status='IN_PROGRESS'",
            status,
            Timestamp.from(clock.instant().plusMillis(delay)),
            claim.id(),
            claim.token());
    log.warn(
        "Outbox eventId={} attempt={} state={} updated={}",
        claim.id(),
        claim.attempts(),
        status,
        count);
    return count == 1;
  }
}
