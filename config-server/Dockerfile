FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY config-server/build/libs/*.jar app.jar

EXPOSE 8888

ENTRYPOINT ["java", "-jar", "app.jar"]
