package dev.monitor.config;

import dev.monitor.dto.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenApiCustomizer describeContracts() {
    return api -> {
      ModelConverters.getInstance().read(ApiError.class).forEach(api.getComponents()::addSchemas);
      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperationsMap()
                      .forEach(
                          (method, operation) -> {
                            boolean authPost =
                                path.startsWith("/auth/") && method.name().equals("POST");
                            operation.setDescription(
                                (path.startsWith("/data")
                                        ? "Только ROLE_ADMIN. "
                                        : path.equals("/status")
                                            ? "ROLE_USER или ROLE_ADMIN. "
                                            : "Служебная авторизация для обеих ролей. ")
                                    + (authPost
                                        ? "Требуется CSRF-токен из GET /auth/csrf. Refresh передаётся cookie HttpOnly, Path=/, SameSite=Lax."
                                        : ""));
                            if (authPost)
                              operation.addParametersItem(
                                  new HeaderParameter()
                                      .name("X-XSRF-TOKEN")
                                      .required(true)
                                      .description(
                                          "Поле token из GET /auth/csrf; после login/refresh получите новый токен.")
                                      .schema(new StringSchema()));
                            for (String code :
                                new String[] {"400", "401", "403", "404", "413", "500", "503"}) {
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      code,
                                      new ApiResponse()
                                          .description(
                                              switch (code) {
                                                case "400" -> "Некорректный JSON, UUID или поля";
                                                case "401" -> "Нет действующей авторизации";
                                                case "403" ->
                                                    "Недостаточная роль или неверный CSRF";
                                                case "404" -> "Запись не найдена";
                                                case "413" -> "Превышен размер запроса";
                                                case "503" ->
                                                    "Необходимая инфраструктура недоступна";
                                                default -> "Безопасное сообщение внутренней ошибки";
                                              })
                                          .content(
                                              new Content()
                                                  .addMediaType(
                                                      "application/json",
                                                      new MediaType()
                                                          .schema(
                                                              new io.swagger.v3.oas.models.media
                                                                      .Schema<>()
                                                                  .$ref(
                                                                      "#/components/schemas/ApiError")))));
                            }
                            if (path.equals("/data") && method.name().equals("POST")) {
                              var success = operation.getResponses().remove("200");
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      "201",
                                      success == null
                                          ? new ApiResponse()
                                              .description("Создано; Location=/data/{id}")
                                          : success.description("Создано; Location=/data/{id}"));
                            }
                            if (method.name().equals("DELETE") || path.equals("/auth/logout")) {
                              operation.getResponses().remove("200");
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      "204",
                                      new ApiResponse().description("Выполнено, тело отсутствует"));
                            }
                          }));
    };
  }
}
