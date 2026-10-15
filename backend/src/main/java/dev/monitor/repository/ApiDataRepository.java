package dev.monitor.repository;

import dev.monitor.entity.ApiDataEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiDataRepository extends JpaRepository<ApiDataEntity, UUID> {
  List<ApiDataEntity> findTop10ByOrderByCreatedAtDescIdDesc();
}
