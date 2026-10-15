package dev.monitor.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;

public record DataRequest(@NotNull Boolean success, @NotBlank String payload) {
  @JsonIgnore
  @Schema(hidden = true)
  @AssertTrue(message = "payload must be at most 65536 UTF-8 bytes")
  public boolean isPayloadSizeValid() {
    return payload == null || payload.getBytes(StandardCharsets.UTF_8).length <= 65536;
  }
}
