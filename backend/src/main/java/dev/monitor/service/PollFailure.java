package dev.monitor.service;

import java.time.Instant;
import lombok.Getter;

@Getter
public class PollFailure extends RuntimeException {
  private final String category;
  private final Integer status;
  private final boolean retryable;
  private final Instant retryAfter;

  public PollFailure(String category, Integer status, boolean retryable, Instant retryAfter) {
    super(category);
    this.category = category;
    this.status = status;
    this.retryable = retryable;
    this.retryAfter = retryAfter;
  }
}
