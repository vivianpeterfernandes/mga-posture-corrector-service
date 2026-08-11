# ==============================================================================
# STAGE 1: Build the Maven JAR artifact
# ==============================================================================
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy pom.xml and download dependencies (leveraging Docker layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and compile
COPY src ./src
RUN mvn clean package -DskipTests

# ==============================================================================
# STAGE 2: Lightweight Production Runtime Image
# ==============================================================================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the compiled JAR dynamically regardless of version numbers
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8087

# Execute app
ENTRYPOINT ["java", "-jar", "app.jar"]