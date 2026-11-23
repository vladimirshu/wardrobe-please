# Use Eclipse Temurin JDK 25 LTS as the base image for building
FROM eclipse-temurin:25-jdk AS builder

# Set working directory
WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts runtime-jvm.args ./

# Make gradlew executable
RUN chmod +x gradlew

# Copy source code
COPY src/ src/

# Build the application
RUN ./gradlew bootJar --no-daemon

# Use Eclipse Temurin JRE 25 LTS runtime for the final image
FROM eclipse-temurin:25-jre

# Set working directory
WORKDIR /app

# Copy the built jar from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

COPY runtime-jvm.args /app/runtime-jvm.args

# Expose port 8080
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "@/app/runtime-jvm.args", "-jar", "app.jar"]
