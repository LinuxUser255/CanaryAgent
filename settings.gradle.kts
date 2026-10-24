plugins {
    // Resolves JDK toolchains automatically if the requested JDK is not installed.
    // https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "CanaryAgent"
