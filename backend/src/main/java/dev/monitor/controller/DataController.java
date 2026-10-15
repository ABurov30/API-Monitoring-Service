package dev.monitor.controller;

import dev.monitor.dto.DataRequest;
import dev.monitor.dto.DataResponse;
import dev.monitor.service.DataService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearer")
public class DataController {
  private final DataService service;

  @GetMapping
  public DataService.Listing list() {
    return service.list();
  }

  @GetMapping("/{id}")
  public DataResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping
  public ResponseEntity<DataResponse> create(@Valid @RequestBody DataRequest r) {
    var e = service.create(r);
    return ResponseEntity.created(URI.create("/data/" + e.id())).body(e);
  }

  @PutMapping("/{id}")
  public DataResponse update(@PathVariable UUID id, @Valid @RequestBody DataRequest r) {
    return service.update(id, r);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
