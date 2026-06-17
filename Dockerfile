# ---------------------------------------------------------------------------
# Étape 1 — Build Maven
# ---------------------------------------------------------------------------
FROM maven:3.9.8-eclipse-temurin-21 AS build

WORKDIR /app

# Pré-télécharger les dépendances (cache Docker)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Compiler et packager (tests sautés — ils s'exécutent en CI)
COPY src/ src/
RUN mvn package -DskipTests -q

# ---------------------------------------------------------------------------
# Étape 2 — Image d'exécution légère
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Répertoire de données (volume monté en prod)
RUN mkdir -p /data

# Copie du fat-jar depuis l'étape de build
COPY --from=build /app/target/abrid-*.jar app.jar

# Sonde de santé
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8080/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
