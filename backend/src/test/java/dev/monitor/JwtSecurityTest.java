package dev.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.monitor.config.AppProperties;
import dev.monitor.security.SecurityConfig;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtSecurityTest {
  SecurityConfig config = new SecurityConfig();
  AppProperties properties = settings((byte) 1);

  private AppProperties settings(byte seed) {
    var result = new AppProperties();
    byte[] secret = new byte[32];
    java.util.Arrays.fill(secret, seed);
    result.getAuth().setSecret(Base64.getEncoder().encodeToString(secret));
    return result;
  }

  private String token(String issuer, String audience, Instant expiry, AppProperties settings) {
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("user")
            .audience(List.of(audience))
            .issuedAt(Instant.now().minusSeconds(600))
            .expiresAt(expiry)
            .claim("roles", List.of("ROLE_USER"))
            .build();
    return config
        .jwtEncoder(config.jwtKey(settings))
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  @Test
  void validatesSignatureIssuerAudienceAndExpiry() {
    var decoder = config.jwtDecoder(config.jwtKey(properties), properties);
    assertThat(
            decoder
                .decode(
                    token("api-monitor", "monitor-ui", Instant.now().plusSeconds(60), properties))
                .getSubject())
        .isEqualTo("user");
    for (String invalid :
        List.of(
            token("wrong", "monitor-ui", Instant.now().plusSeconds(60), properties),
            token("api-monitor", "wrong", Instant.now().plusSeconds(60), properties),
            token("api-monitor", "monitor-ui", Instant.now().minusSeconds(1), properties),
            token("api-monitor", "monitor-ui", Instant.now().plusSeconds(60), settings((byte) 2)),
            "not-a-jwt")) {
      assertThatThrownBy(() -> decoder.decode(invalid)).isInstanceOf(JwtException.class);
    }
  }

  @Test
  void weakOrMalformedSecretsFailStartup() {
    properties.getAuth().setSecret("invalid");
    assertThatThrownBy(() -> config.jwtKey(properties))
        .isInstanceOf(IllegalArgumentException.class);
    properties.getAuth().setSecret(Base64.getEncoder().encodeToString(new byte[16]));
    assertThatThrownBy(() -> config.jwtKey(properties))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
