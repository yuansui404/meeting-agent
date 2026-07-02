FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg nodejs npm && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/meeting-agent-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
