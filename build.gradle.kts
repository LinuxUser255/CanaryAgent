plugins {
    // Kotlin language support
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.jvm
    kotlin("jvm") version "2.3.21"

    // Provides the shadowJar task (fat JAR with Kotlin stdlib bundled)
    // https://plugins.gradle.org/plugin/com.gradleup.shadow
    id("com.gradleup.shadow") version "9.4.1"
}

group = "com.canaryagent"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    // Target JVM 21 bytecode: runs on Burp's bundled Java (26 at time of writing) and on older installs.
    jvmToolchain(21)
}

dependencies {
    // Burp provides the Montoya API at runtime; never bundle it.
    // https://central.sonatype.com/artifact/net.portswigger.burp.extensions/montoya-api
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.4")
    implementation(kotlin("stdlib"))
    // Small JSON parser for key matching on parsed structures (unit-testable outside Burp;
    // Montoya's JsonNode factory only works inside a running Burp instance).
    implementation("org.json:json:20250517")

    testImplementation(kotlin("test"))
    testImplementation("net.portswigger.burp.extensions:montoya-api:2026.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.shadowJar {
    archiveBaseName.set("canaryagent")
    archiveClassifier.set("all")
}

tasks.test {
    useJUnitPlatform()
    // Swing panel tests run without a display.
    systemProperty("java.awt.headless", "true")
}

// ---------------------------------------------------------------------------------------------
// Dev-only self-test build. `./gradlew selfTestJar` produces canaryagent-<version>-selftest-all.jar:
// the full extension plus an in-Burp "CanaryAgent Self-Test" tab that exercises the handlers
// against httpbin.org / echo.websocket.org through Burp's own networking. Nothing from this source
// set is in the normal shadowJar, and the self-test JAR drops the production entry class so Burp
// finds exactly one BurpExtension implementation.
// ---------------------------------------------------------------------------------------------
val selftest: SourceSet by sourceSets.creating {
    resources.srcDir("src/test/resources") // reuse the corpus bodies
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations["selftestImplementation"].extendsFrom(configurations.implementation.get())
configurations["selftestCompileOnly"].extendsFrom(configurations.compileOnly.get())

// Each build gets the next number so the highest N is always the newest file: canaryagent-selftest-N.jar.
// The counter lives in .selftest-build (gitignored) and survives `clean`.
val selfTestCounterFile = layout.projectDirectory.file(".selftest-build").asFile
val selfTestNumber: Int = (selfTestCounterFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1

val selfTestJar by tasks.registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
    group = "build"
    description = "Builds the dev-only extension JAR with the in-Burp self-test tab (numbered name)."
    archiveBaseName.set("canaryagent-selftest")
    archiveVersion.set("")
    archiveClassifier.set(selfTestNumber.toString())
    from(sourceSets.main.get().output)
    from(selftest.output)
    configurations.set(listOf(project.configurations.runtimeClasspath.get()))
    exclude("com/canaryagent/CanaryAgentExtension.class")
    mergeServiceFiles()
    doLast {
        selfTestCounterFile.writeText(selfTestNumber.toString())
        println("Self-test JAR: ${archiveFile.get().asFile.absolutePath}")
    }
}
