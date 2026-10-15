package dev.monitor.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class ApiErrors {
  private final ObjectMapper mapper;

  public static Map<String, Object> body(int status, String message, String path) {
    var body = new LinkedHashMap<String, Object>();
    body.put("code", "HTTP_" + status);
    body.put("message", message);
    body.put("timestamp", Instant.now());
    body.put("path", path);
    return body;
  }

  public void write(HttpServletRequest req, HttpServletResponse res, int status, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    mapper.writeValue(res.getOutputStream(), body(status, message, req.getRequestURI()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<?> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
    var b = body(400, "Проверьте поля запроса", req.getRequestURI());
    var fields = new LinkedHashMap<String, String>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
    b.put("fieldErrors", fields);
    return ResponseEntity.badRequest().body(b);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> bad(Exception ex, HttpServletRequest req) {
    return ResponseEntity.badRequest().body(body(400, "Некорректный запрос", req.getRequestURI()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> status(ResponseStatusException ex, HttpServletRequest req) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(body(ex.getStatusCode().value(), ex.getReason(), req.getRequestURI()));
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<?> auth(AuthenticationException ex, HttpServletRequest req) {
    return ResponseEntity.status(401)
        .body(body(401, "Неверные данные входа или сессия истекла", req.getRequestURI()));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<?> unavailable(DataAccessException ex, HttpServletRequest req) {
    log.error("Database operation unavailable", ex);
    return ResponseEntity.status(503)
        .body(body(503, "Хранилище временно недоступно", req.getRequestURI()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception ex, HttpServletRequest req) {
    log.error("Unexpected request failure", ex);
    return ResponseEntity.internalServerError()
        .body(body(500, "Внутренняя ошибка сервера", req.getRequestURI()));
  }
}
