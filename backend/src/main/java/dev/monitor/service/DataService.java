package dev.monitor.service;

import dev.monitor.dto.DataRequest;
import dev.monitor.dto.DataResponse;
import dev.monitor.entity.ApiDataEntity;
import dev.monitor.repository.ApiDataRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class DataService {
  private final ApiDataRepository repository;
  private final Clock clock;

  public record Listing(List<DataResponse> items, long total) {}

  @Transactional(readOnly = true)
  public Listing list() {
    return new Listing(
        repository.findTop10ByOrderByCreatedAtDescIdDesc().stream()
            .map(DataResponse::from)
            .toList(),
        repository.count());
  }

  @Transactional(readOnly = true)
  public DataResponse get(UUID id) {
    return DataResponse.from(find(id));
  }

  public DataResponse create(DataRequest request) {
    return DataResponse.from(
        repository.save(new ApiDataEntity(request.success(), request.payload(), clock.instant())));
  }

  public DataResponse update(UUID id, DataRequest request) {
    var e = find(id);
    e.replace(request.success(), request.payload());
    return DataResponse.from(e);
  }

  public void delete(UUID id) {
    repository.delete(find(id));
  }

  private ApiDataEntity find(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена"));
  }
}
