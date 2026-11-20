pluginManagement {
    repositories {
        gradlePluginPortal()
        // Fallback: the portal sometimes fails to proxy artifacts hosted on
        // Maven Central (Kotlin and Spring Boot plugins, their BOMs).
        mavenCentral()
    }
}
plugins {
    // Lets Gradle download a JDK 21 automatically if one isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kirim-risk-service"
