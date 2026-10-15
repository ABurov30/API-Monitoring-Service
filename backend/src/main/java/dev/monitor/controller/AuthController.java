package dev.monitor.controller;

import dev.monitor.config.AppProperties;
import dev.monitor.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
  private final AuthService service;
  private final AppProperties properties;
  private final CookieCsrfTokenRepository csrfRepository;

  public record Login(
      @NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {}

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/login")
  public AuthService.Access login(
      @Valid @RequestBody Login r, HttpServletRequest req, HttpServletResponse res) {
    var session = service.login(r.username(), r.password());
    setCookie(res, session.refresh(), properties.getAuth().getRefreshTtl());
    csrfRepository.saveToken(null, req, res);
    return session.access();
  }

  @PostMapping("/refresh")
  public AuthService.Access refresh(
      @CookieValue(name = "refresh", required = false) String token,
      HttpServletRequest req,
      HttpServletResponse res) {
    var session = service.refresh(token);
    setCookie(res, session.refresh(), properties.getAuth().getRefreshTtl());
    csrfRepository.saveToken(null, req, res);
    return session.access();
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = "refresh", required = false) String token,
      HttpServletRequest req,
      HttpServletResponse res) {
    service.logout(token);
    setCookie(res, "", Duration.ZERO);
    csrfRepository.saveToken(null, req, res);
    return ResponseEntity.noContent().build();
  }

  private void setCookie(HttpServletResponse res, String value, Duration age) {
    res.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("refresh", value)
            .httpOnly(true)
            .secure(properties.getAuth().isCookieSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(age)
            .build()
            .toString());
  }
}
