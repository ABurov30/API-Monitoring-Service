package dev.monitor.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.monitor.config.AppProperties;
import dev.monitor.exception.ApiErrors;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;

@Configuration
public class SecurityConfig {
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecretKeySpec jwtKey(AppProperties p) {
    byte[] key = Base64.getDecoder().decode(p.getAuth().getSecret());
    if (key.length < 32) throw new IllegalArgumentException("JWT secret requires >=256 bits");
    return new SecretKeySpec(key, "HmacSHA256");
  }

  @Bean
  public JwtEncoder jwtEncoder(SecretKeySpec key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  public JwtDecoder jwtDecoder(SecretKeySpec key, AppProperties p) {
    var d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    d.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),
            new JwtIssuerValidator(p.getAuth().getIssuer()),
            jwt ->
                jwt.getExpiresAt() != null
                        && jwt.getAudience() != null
                        && jwt.getAudience().contains(p.getAuth().getAudience())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return d;
  }

  @Bean
  public CookieCsrfTokenRepository csrfRepository(AppProperties p) {
    var r = CookieCsrfTokenRepository.withHttpOnlyFalse();
    r.setCookiePath("/");
    r.setCookieCustomizer(c -> c.secure(p.getAuth().isCookieSecure()).sameSite("Lax"));
    return r;
  }

  @Bean
  public SecurityFilterChain security(
      HttpSecurity http,
      ApiErrors errors,
      AppProperties p,
      Environment env,
      CookieCsrfTokenRepository csrf)
      throws Exception {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(
                        PathPatternRequestMatcher.withDefaults()
                            .matcher(HttpMethod.POST, "/auth/**")))
        .cors(
            c ->
                c.configurationSource(
                    r -> {
                      var config = new CorsConfiguration();
                      config.setAllowedOrigins(p.getCorsOrigins());
                      config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                      config.setAllowedHeaders(
                          List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
                      config.setAllowCredentials(true);
                      return config;
                    }))
        .authorizeHttpRequests(
            a -> {
              a.requestMatchers("/auth/csrf", "/auth/login", "/auth/refresh", "/auth/logout")
                  .permitAll();
              if (env.matchesProfiles("local"))
                a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll();
              a.requestMatchers("/status")
                  .hasAnyRole("USER", "ADMIN")
                  .requestMatchers("/data", "/data/**")
                  .hasRole("ADMIN")
                  .anyRequest()
                  .denyAll();
            })
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(
                        (r, s, e) -> errors.write(r, s, 401, "Требуется вход")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((r, s, x) -> errors.write(r, s, 401, "Требуется вход"))
                    .accessDeniedHandler(
                        (r, s, x) ->
                            errors.write(r, s, 403, "Доступ запрещён или неверный CSRF-токен")))
        .addFilterBefore(new RequestLimitFilter(errors), SecurityContextHolderFilter.class);
    return http.build();
  }
}
