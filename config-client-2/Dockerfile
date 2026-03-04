FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY config-client-2/build/libs/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
