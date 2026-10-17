/*
 * Stellaris - lightweight performance suite for Paper / Purpur / Folia.
 *
 * Build philosophy
 * ────────────────
 *  - paper-api, fastutil, PacketEvents and PlaceholderAPI are `compileOnly`:
 *    they are all provided by the server or by the soft-depend plugin at
 *    runtime. Nothing is shaded, nothing is relocated, the output is a single
 *    small jar with zero duplicate classes.
 *  - The Kotlin DSL + the `java` plugin is all we need. No Shadow, no
 *    run-paper, no annotation processors: fewer moving parts, faster builds.
 *  - paper-api 26.3 is compiled for Java 25, so the toolchain is 25 while the
 *    emitted bytecode is pinned to release 21 (runs on any Java 21+ runtime).
 *
 * If you ever target a fork that does NOT ship fastutil, move the fastutil
 * dependency to `implementation` and add the com.gradleup.shadow plugin with
 * relocate("it.unimi.dsi.fastutil", "dev.stellaris.libs.fastutil").
 */

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

    // Provided by the Paper server itself. Do not shade: see header.
    compileOnly("it.unimi.dsi:fastutil:8.5.19")

    // Optional soft-dependencies. Stellaris detects them at runtime and simply
    // leaves the related layer off when they are missing.
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
    // Emit Java 21 bytecode: the APIs we touch exist on every modern Paper
    // fork, and the class files stay loadable by 1.20.5+ era runtimes.
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
}

// plugin.yml and config.yml are shipped verbatim. We deliberately do NOT enable
// resource filtering: MiniMessage tags (<red>, <gray>) and permission wildcards
// must survive untouched, and there is no ${version} placeholder to expand.
tasks.jar {
    archiveFileName.set("Stellaris-${project.version}.jar")
}
