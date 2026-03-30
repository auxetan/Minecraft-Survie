plugins {
    java
    // io.github.goooler.shadow est le fork maintenu de johnrengelman.shadow
    // qui supporte les class files Java 21+ (ASM 9.7+)
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "com.survivalcore"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
