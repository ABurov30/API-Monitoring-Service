FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml checkstyle.xml ./
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17.0.16_8-jre-jammy
RUN groupadd --system monitor && useradd --system --gid monitor monitor
WORKDIR /app
COPY --from=build /workspace/target/monitor-1.0.0.jar app.jar
USER monitor
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=4s --start-period=60s CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
