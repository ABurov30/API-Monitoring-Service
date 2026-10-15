package dev.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.monitor.exception.ApiErrors;
import dev.monitor.security.RequestLimitFilter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLimitFilterTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final RequestLimitFilter filter = new RequestLimitFilter(new ApiErrors(mapper));

  @Test
  void preservesBodyAtTheLimitForPostAndPut() throws Exception {
    byte[] payload = "я".repeat(262144).getBytes(StandardCharsets.UTF_8);
    for (String method : new String[] {"POST", "PUT"}) {
      var request = new MockHttpServletRequest(method, "/data");
      request.setContent(payload);
      var response = new MockHttpServletResponse();
      var called = new AtomicBoolean();
      filter.doFilter(
          request,
          response,
          (forwarded, result) -> {
            called.set(true);
            assertThat(forwarded.getInputStream().readAllBytes()).isEqualTo(payload);
            assertThat(forwarded.getInputStream().isReady()).isTrue();
          });
      assertThat(called).isTrue();
      assertThat(response.getStatus()).isEqualTo(200);
    }
  }

  @Test
  void rejectsOversizedBodyWithoutCallingTheController() throws Exception {
    for (String method : new String[] {"POST", "PUT"}) {
      var request = new MockHttpServletRequest(method, "/data");
      request.setContent(new byte[524289]);
      var response = new MockHttpServletResponse();
      var called = new AtomicBoolean();
      filter.doFilter(request, response, (forwarded, result) -> called.set(true));
      assertThat(called).isFalse();
      assertThat(response.getStatus()).isEqualTo(413);
      assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
      var error = mapper.readTree(response.getContentAsByteArray());
      assertThat(error.path("code").asText()).isEqualTo("HTTP_413");
      assertThat(error.path("path").asText()).isEqualTo("/data");
      assertThat(error.path("timestamp").isMissingNode()).isFalse();
    }
  }

  @Test
  void doesNotReadBodiesOfOtherMethods() throws Exception {
    var request = new MockHttpServletRequest("GET", "/status");
    request.setContent(new byte[524289]);
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (forwarded, response) -> {
          assertThat(forwarded).isSameAs(request);
          assertThat(forwarded.getInputStream().read()).isZero();
        });
  }
}
