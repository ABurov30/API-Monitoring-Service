package dev.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.monitor.exception.ApiErrors;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.server.ResponseStatusException;

class ApiErrorsTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final ApiErrors errors = new ApiErrors(mapper);
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/data");

  @Test
  void filterErrorsUseTheSameJsonContract() throws Exception {
    for (int status : new int[] {401, 403, 413}) {
      var response = new MockHttpServletResponse();
      errors.write(request, response, status, "Безопасное сообщение");
      assertThat(response.getStatus()).isEqualTo(status);
      var body = mapper.readTree(response.getContentAsByteArray());
      assertThat(body.path("code").asText()).isEqualTo("HTTP_" + status);
      assertThat(body.path("message").asText()).isEqualTo("Безопасное сообщение");
      assertThat(body.path("path").asText()).isEqualTo("/data");
      assertThat(body.hasNonNull("timestamp")).isTrue();
    }
  }

  @Test
  void authenticationAndMalformedRequestsDoNotExposeExceptionDetails() {
    var bad = errors.bad(new IllegalArgumentException("secret-detail"), request);
    var auth = errors.auth(new BadCredentialsException("secret-detail"), request);
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
    assertThat(auth.getStatusCode().value()).isEqualTo(401);
    assertThat(bad.getBody().toString()).doesNotContain("secret-detail");
    assertThat(auth.getBody().toString()).doesNotContain("secret-detail");
  }

  @Test
  void distinguishesDatabaseUnavailabilityFromUnexpectedFailures() {
    var unavailable =
        errors.unavailable(new DataAccessResourceFailureException("database-detail"), request);
    var unexpected = errors.unexpected(new IllegalStateException("internal-detail"), request);
    assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
    assertThat(unexpected.getStatusCode().value()).isEqualTo(500);
    assertThat(unavailable.getBody().toString()).doesNotContain("database-detail");
    assertThat(unexpected.getBody().toString()).doesNotContain("internal-detail");
  }

  @Test
  void preservesAnExplicitNotFoundStatus() {
    var response =
        errors.status(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена"), request);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var body = (Map<?, ?>) response.getBody();
    assertThat(body.get("code")).isEqualTo("HTTP_404");
    assertThat(body.get("message")).isEqualTo("Запись не найдена");
    assertThat(body.get("path")).isEqualTo("/data");
  }
}
