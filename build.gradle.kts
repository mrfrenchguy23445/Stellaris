plugins {
    java
}

group = "dev.stellaris"
version = "1.0.0"
description = "Lightweight performance suite for Paper/Purpur/Folia."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
    maven("https://repo.codemc.io/repository/maven-public/") { name = "CodeMC" }
    maven("https://repo.extendedclip.com/releases/") { name = "PlaceholderAPI" }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.200-stable")
    compileOnly("it.unimi.dsi:fastutil:8.5.19")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0") {
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
    compileOnly("me.clip:placeholderapi:2.12.3")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
}
tasks.jar {
    archiveFileName.set("Stellaris-${project.version}.jar")
}
