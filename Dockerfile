# Build the jar first: ./mvnw package -DskipTests
FROM eclipse-temurin:21-jre
COPY target/paycore-0.1.0-SNAPSHOT.jar /app/paycore.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/paycore.jar"]
