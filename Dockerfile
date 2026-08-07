FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY target/mga-posture-corrector-service-0.0.1.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
