# ---------- Build stage ----------
FROM maven:3-eclipse-temurin-25 AS build

WORKDIR /app

# Копируем parent pom
COPY pom.xml /app/
RUN mkdir -p /app/target && touch /app/target/MCPulsor-1.0-SNAPSHOT.pom

# Копируем модуль
COPY mcpulsor-server/ /app/mcpulsor-server/

# Собираем fat-jar
RUN mvn -f /app/mcpulsor-server/pom.xml clean package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Копируем fat-jar из stage сборки
COPY --from=build /app/mcpulsor-server/target/mcpulsor-server-*-fat.jar /app/app.jar

# Порт сервера (из приложения)
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
