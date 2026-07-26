FROM eclipse-temurin:21.0.11_10-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21.0.11_10-jre-jammy
RUN groupadd --gid 1000 spring && useradd --uid 1000 --gid spring --shell /bin/false spring
WORKDIR /app
COPY --from=build /workspace/target/api.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
