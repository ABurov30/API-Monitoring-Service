package dev.monitor.service;

import dev.monitor.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final JwtEncoder encoder;
  private final AppProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public record User(UUID id, String username, List<String> roles) {}

  public record Access(String accessToken, String tokenType, long expiresIn, User user) {}

  public record Session(Access access, String refresh) {}

  private record Account(UUID id, String username, String password) {}

  private record Refresh(UUID owner, Instant expiry, boolean used, boolean revoked) {}

  @Transactional
  public Session login(String username, String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalid();
    var users =
        jdbc.query(
            "select * from users where username=?",
            (rs, n) ->
                new Account(
                    rs.getObject("id", UUID.class),
                    rs.getString("username"),
                    rs.getString("password")),
            username);
    if (users.isEmpty() || !passwords.matches(password, users.get(0).password())) throw invalid();
    return issue(users.get(0).id());
  }

  @Transactional
  public Session refresh(String raw) {
    if (raw == null) throw invalid();
    var tokens =
        jdbc.query(
            "select * from refresh_tokens where hash=? for update",
            (rs, n) ->
                new Refresh(
                    rs.getObject("user_id", UUID.class),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getBoolean("used"),
                    rs.getBoolean("revoked")),
            hash(raw));
    if (tokens.isEmpty()) throw invalid();
    var token = tokens.get(0);
    if (token.used() || token.revoked() || !token.expiry().isAfter(clock.instant()))
      throw invalid();
    jdbc.update("update refresh_tokens set used=true where hash=?", hash(raw));
    return issue(token.owner());
  }

  @Transactional
  public void logout(String raw) {
    if (raw != null) jdbc.update("update refresh_tokens set revoked=true where hash=?", hash(raw));
  }

  private Session issue(UUID id) {
    var user =
        jdbc.queryForObject(
            "select * from users where id=?",
            (rs, n) ->
                new User(
                    id,
                    rs.getString("username"),
                    jdbc.queryForList(
                        "select role from user_roles where user_id=? order by role",
                        String.class,
                        id)),
            id);
    var now = clock.instant();
    var a = properties.getAuth();
    var claims =
        JwtClaimsSet.builder()
            .subject(id.toString())
            .issuer(a.getIssuer())
            .audience(List.of(a.getAudience()))
            .issuedAt(now)
            .expiresAt(now.plus(a.getAccessTtl()))
            .claim("roles", user.roles())
            .build();
    String access =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.update(
        "insert into refresh_tokens(hash,user_id,expires_at) values(?,?,?)",
        hash(refresh),
        id,
        java.sql.Timestamp.from(now.plus(a.getRefreshTtl())));
    return new Session(new Access(access, "Bearer", a.getAccessTtl().toSeconds(), user), refresh);
  }

  public static String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private BadCredentialsException invalid() {
    return new BadCredentialsException("Invalid session");
  }
}
