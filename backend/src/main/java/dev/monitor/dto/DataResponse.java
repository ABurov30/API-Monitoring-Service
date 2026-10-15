package dev.monitor.dto;

import dev.monitor.entity.ApiDataEntity;
import java.time.Instant;
import java.util.UUID;

public record DataResponse(UUID id, Instant createdAt, boolean success, String payload) {
  public static DataResponse from(ApiDataEntity e) {
    return new DataResponse(e.getId(), e.getCreatedAt(), e.isSuccess(), e.getPayload());
  }
}
