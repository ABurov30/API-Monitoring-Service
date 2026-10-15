package dev.monitor.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearer")
public class StatusController {
  private final Clock clock;

  public record Status(String status, Instant checkedAt) {}

  @GetMapping("/status")
  public Status status() {
    return new Status("UP", clock.instant());
  }
}
