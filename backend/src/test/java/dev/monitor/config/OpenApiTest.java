package dev.monitor.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class OpenApiTest {
  Operation operation() {
    return new Operation()
        .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")));
  }

  @Test
  void describesRolesCsrfCreatedAndEmptyResponses() {
    var api =
        new OpenAPI()
            .components(new Components())
            .paths(
                new Paths()
                    .addPathItem("/status", new PathItem().get(operation()))
                    .addPathItem("/data", new PathItem().get(operation()).post(operation()))
                    .addPathItem("/data/{id}", new PathItem().delete(operation()))
                    .addPathItem("/auth/login", new PathItem().post(operation()))
                    .addPathItem("/auth/logout", new PathItem().post(operation()))
                    .addPathItem("/auth/csrf", new PathItem().get(operation())));
    new OpenApiConfig().describeContracts().customise(api);
    assertThat(api.getComponents().getSchemas()).containsKey("ApiError");
    assertThat(api.getPaths().get("/data").getPost().getResponses())
        .containsKeys("201", "400", "401", "403", "404", "413", "500", "503")
        .doesNotContainKey("200");
    assertThat(api.getPaths().get("/data/{id}").getDelete().getResponses()).containsKey("204");
    assertThat(api.getPaths().get("/auth/login").getPost().getParameters().get(0).getName())
        .isEqualTo("X-XSRF-TOKEN");
    assertThat(api.getPaths().get("/status").getGet().getDescription()).contains("ROLE_USER");
  }
}
