plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.survivalcore"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.release.set(21)
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
