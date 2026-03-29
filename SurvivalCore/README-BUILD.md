# Build Instructions

## Prérequis
- Java JDK 21+
- Connexion internet (première build seulement, pour télécharger Gradle + dépendances)

## Première build
```bash
cd SurvivalCore

# Générer le Gradle wrapper (nécessite Gradle installé, ou utiliser sdkman)
gradle wrapper --gradle-version 8.10

# Build le plugin
./gradlew build

# Le JAR sera dans build/libs/SurvivalCore-1.0.0.jar
```

## Alternative avec sdkman
```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 21.0.2-tem
sdk install gradle 8.10
cd SurvivalCore
gradle wrapper
./gradlew build
```
