FROM eclipse-temurin:21.0.11_10-jdk-jammy AS build
WORKDIR /workspace/apps/api
COPY apps/api/.mvn/ .mvn/
COPY apps/api/mvnw apps/api/pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY apps/api/src/ src/
COPY docs/contracts/extraction-proposal.schema.json /workspace/docs/contracts/extraction-proposal.schema.json
COPY scripts/demo_seed/pdfs/ /workspace/scripts/demo_seed/pdfs/
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21.0.11_10-jre-jammy
RUN groupadd --gid 1000 spring && useradd --uid 1000 --gid spring --shell /bin/false spring
WORKDIR /app
COPY --from=build /workspace/apps/api/target/api.jar app.jar
RUN mkdir -p /app/var/storage && chown -R spring:spring /app/var
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
