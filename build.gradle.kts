import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val ktor_version: String by project

plugins {
    id("org.graalvm.buildtools.native") version "0.9.8"
    kotlin("jvm") version "1.8.20"
    kotlin("plugin.serialization") version "1.8.20"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

group = "tech.frittenbude"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("ch.qos.logback:logback-classic:1.4.6")
    testImplementation(kotlin("test"))
}

graalvmNative {
    binaries {
        named("main"){
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(17))
            })

            fallback.set(false)
            verbose.set(true)

            buildArgs.add("--initialize-at-build-time=io.ktor,kotlin,kotlinx.serialization,ch.qos.logback,kotlinx.coroutines,org.slf4j.LoggerFactory")

            buildArgs.add("-H:+InstallExitHandlers")
            buildArgs.add("-H:+ReportUnsupportedElementsAtRuntime")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("--trace-class-initialization=ch.qos.logback.core.status.InfoStatus")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("MainKt")
}