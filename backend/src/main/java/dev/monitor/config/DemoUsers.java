package dev.monitor.config;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@RequiredArgsConstructor
public class DemoUsers implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final Environment env;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    create("user", "ROLE_USER", env.getRequiredProperty("DEMO_USER_PASSWORD"));
    create("admin", "ROLE_ADMIN", env.getRequiredProperty("DEMO_ADMIN_PASSWORD"));
  }

  private void create(String name, String role, String password) {
    if (password.length() < 12)
      throw new IllegalArgumentException("Demo passwords require >=12 characters");
    if (jdbc.queryForObject("select count(*) from users where username=?", Long.class, name) > 0)
      return;
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into users(id,username,password) values(?,?,?)",
        id,
        name,
        passwords.encode(password));
    jdbc.update("insert into user_roles(user_id,role) values(?,?)", id, role);
  }
}
