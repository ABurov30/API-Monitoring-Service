package dev.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "api_data")
@Getter
@NoArgsConstructor
public class ApiDataEntity {
  @Id private UUID id;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private boolean success;

  @Column(nullable = false, columnDefinition = "text")
  private String payload;

  public ApiDataEntity(boolean success, String payload, Instant now) {
    id = UUID.randomUUID();
    createdAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    replace(success, payload);
  }

  public void replace(boolean success, String payload) {
    this.success = success;
    this.payload = payload;
  }
}
