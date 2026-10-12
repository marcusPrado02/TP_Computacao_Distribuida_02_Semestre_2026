# Build
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/aco-node.jar /app/aco-node.jar
COPY data ./data
EXPOSE 50051
ENTRYPOINT ["java", "-jar", "/app/aco-node.jar"]
