package dev.monitor.security;

import dev.monitor.exception.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class RequestLimitFilter extends OncePerRequestFilter {
  private final ApiErrors errors;

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (!"POST".equals(req.getMethod()) && !"PUT".equals(req.getMethod())) {
      chain.doFilter(req, res);
      return;
    }
    byte[] bytes = req.getInputStream().readNBytes(524289);
    if (bytes.length > 524288) {
      errors.write(req, res, 413, "Запрос слишком большой");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(req) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
              @Override
              public int read() {
                return input.read();
              }

              @Override
              public boolean isFinished() {
                return input.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
              }
            };
          }
        },
        res);
  }
}
