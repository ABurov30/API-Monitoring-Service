package dev.monitor.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "API Monitor",
            version = "1",
            description =
                "USER: /status. ADMIN: /status and /data CRUD. Auth POST requires X-XSRF-TOKEN from /auth/csrf."))
@SecurityScheme(
    name = "bearer",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class RuntimeConfig {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("monitor-");
    return scheduler;
  }
}
