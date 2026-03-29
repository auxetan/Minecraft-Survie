plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.survivalcore"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // TriumphGUI — framework GUI (3.1.12 sur Maven Central)
    implementation("dev.triumphteam:triumph-gui:3.1.12")

    // Vault API — économie
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("dev.triumphteam.gui", "com.survivalcore.libs.gui")
        minimize()
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}
