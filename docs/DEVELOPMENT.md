# CanaryAgent — IntelliJ / Development Setup

Practical guide for opening, building, running, and debugging the CanaryAgent Burp Suite extension in IntelliJ IDEA.

## Prerequisites

- **IntelliJ IDEA** (Community Edition is fine; Ultimate also works)
- **JDK 21** installed and available to IntelliJ
- **Burp Suite** (Community or Professional) for loading and testing the extension
  - Collaborator features require **Professional**; they are hidden on Community.
- Git (optional, if you want version control)

## 1. Create or open the project

### Option A: Start from the Kotlin Burp template (recommended)

1. Clone or download the template:  
   https://github.com/ncoblentz/KotlinBurpExtensionBase
2. Open the folder in IntelliJ (`File → Open`).
3. Let Gradle import the project (IntelliJ usually detects `build.gradle.kts` automatically).
4. Rename the project:
   - Edit `settings.gradle.kts` → change `rootProject.name`
   - Edit `build.gradle.kts` → change `group` (e.g. `com.canaryagent`) and `version`
   - In IntelliJ: `File → Project Structure → Project` → set the project name
5. Replace the template's build file essentials with section 2 below (Shadow plugin ID, toolchain, pinned versions).

### Option B: New Gradle project

1. `File → New → Project` → Gradle → Kotlin DSL → JDK 21.
2. Make sure a Gradle wrapper exists (IntelliJ normally generates one; otherwise run `gradle wrapper` in the project root). Without it, `./gradlew` will not exist.
3. Add the Kotlin and Shadow plugins plus the Montoya dependency (see section 2).
4. Create the package `com.canaryagent` under `src/main/kotlin`.

## 2. Essential `build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"            // pin exact; dynamic versions are unreliable in plugins {}
    id("com.gradleup.shadow") version "9.4.1" // successor to com.github.johnrengelman.shadow
}

group = "com.canaryagent"
version = "1.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21) // aligns Kotlin and Java JVM targets; avoids target-mismatch errors
}

dependencies {
    // Verify the exact published version on Maven Central before pinning.
    compileOnly("net.portswigger.burp.extensions:montoya-api:<verified-version>")
    implementation(kotlin("stdlib"))
    // JSON parsing (only if Montoya utilities don't cover it):
    implementation("org.json:json:<current>")

    testImplementation(kotlin("test"))
    testImplementation("net.portswigger.burp.extensions:montoya-api:<verified-version>")
}

tasks.shadowJar {
    archiveBaseName.set("canaryagent") // fixes the JAR name regardless of rootProject.name
    archiveClassifier.set("all")
}

tasks.test {
    useJUnitPlatform()
}
```

After editing, click the Gradle refresh icon (or `View → Tool Windows → Gradle → Reload`).

## 3. Project layout (suggested)

```
src/main/kotlin/com/canaryagent/
├── CanaryAgentExtension.kt
├── model/
├── inventory/
├── markers/
├── annotation/
├── ui/
├── contextmenu/
├── handlers/
└── util/

src/test/kotlin/com/canaryagent/     # detector + marker scanner tests
src/test/resources/corpus/           # captured sample request/response bodies
```

You can reorganize packages as the code evolves. Keep the entry point class that implements `BurpExtension` easy to find.

## 4. Build the extension

**From the command line (project root):**
```bash
./gradlew shadowJar
```

**Run unit tests (detector, marker scanner):**
```bash
./gradlew test
```

**From IntelliJ:**
- Open the Gradle tool window (right side or `View → Tool Windows → Gradle`)
- Navigate to `Tasks → shadow → shadowJar` and double-click it

Output JAR:  
`build/libs/canaryagent-1.1.0-all.jar`

### In-Burp self-test (dev only)

```bash
./gradlew selfTestJar
```

Produces `build/libs/canaryagent-selftest-N.jar`, where N goes up by one per build (counter in the gitignored
`.selftest-build`; the task prints the path), so the highest number is always the newest. It is the extension
plus a **CanaryAgent Self-Test** tab. Load it instead of the production JAR, open the tab and press
**Run self-test**. It exercises the handlers
against httpbin.org and echo.websocket.org through Burp's networking, uses Collaborator on Professional, and
sends two requests through the Proxy listener on 127.0.0.1:8080. Results print in the tab and in Output.
Nothing from `src/selftest` is in the production JAR.

#### Headless, unattended

```bash
scripts/burp-headless-selftest.sh --burp-home /path/to/BurpSuitePro
```

Runs the newest self-test JAR in a second, headless Burp Pro beside your GUI instance: its own listener
on 127.0.0.1:8089, its own temporary settings files (only the self-test JAR loaded, output to console),
a temporary project, and only the process it started is stopped. It waits for the `RESULT:` line, prints
it with the log path (`build/harness-N.log`), and exits 0 on zero failures. `--dry-run` prints the
generated settings and the full command without launching. `--help` lists the options (JAR, port,
timeout, a persistent `--project-file`, `--no-use-defaults`). It uses the JRE bundled with Burp, so no
system Java is needed.

Three JVM properties, all dev-only and read by `src/selftest`, make this work; the script sets them:

- `canaryagent.selftest.autorun=true` starts the run on load (same as the tab's checkbox).
- `canaryagent.selftest.proxyPort=8089` points A2/A8/A11 at the harness listener.
- `canaryagent.selftest.forceMatching=true` makes "Automatically flag agent-related traffic" read ON.
  Headless Burp never renders settings panels, so every panel value reads as unset; without this A5
  and A8 skip. The production JAR ignores all three.

Expect Burp's own `no ComponentUI class` errors and a `NullPointerException` near the top of the log:
Burp initialises its tools UI under the headless flag and carries on. D2 (streaming) always skips;
it needs a live SSE endpoint and a GUI.

#### Long-session memory check

```bash
scripts/burp-headless-longsession.sh --burp-home /path/to/BurpSuitePro
scripts/burp-headless-longsession.sh --burp-home /path/to/BurpSuitePro --baseline
```

Same headless Burp, plus a loopback Python echo server (started and stopped by the script) and 10 000
corpus bodies sent with curl through the listener. The self-test JAR logs a `STATS` line every ten
seconds (`canaryagent.selftest.statsEverySeconds`): traffic rows, tools, markers, heap after a forced
GC. Pass: the traffic store reaches its 5000-row cap and never exceeds it, and the last three heap
samples after traffic stops are within 10 % of each other. `--baseline` leaves annotation off so the
stores stay empty; its series shows Burp's own growth (Proxy history), and the difference between the
two plateaus is the extension's steady-state cost. Nothing leaves the machine. Logs:
`build/longsession-N.log`. The runtime classes measured are the production ones; only the entry class
and the dev-only tab differ.

#### Clean unload

`--unload-check` adds the clean-unload proof the BApp criteria ask for: a fourth property,
`canaryagent.selftest.unloadAfterRun=true`, makes the self-test call `Extension.unload()` on itself
after RESULT (SIGTERM alone never runs unloading handlers headless). The script waits for `Unloaded.`,
sends SIGQUIT so the JVM writes a thread dump into the log, and exits 1 if any thread named
`CanaryAgent-…` is still listed.

## 5. Load in Burp Suite

1. Burp → **Extensions** → **Installed** → **Add**
2. Extension type: **Java**
3. Select the `*-all.jar` from `build/libs`
4. Click Next / Close

You should see "CanaryAgent" in the list and a log line in the Output tab if the skeleton is loaded.

**Tip:** After code changes, rebuild the JAR and reload the extension. Ctrl-click (Cmd-click on macOS) the **Loaded** checkbox in the extensions list is a quick reload in many Burp versions; unload + load always works.

## 6. Proxying the target application (required for useful results)

Browser → backend traffic often contains no tool structure. The tool-calling payloads usually go **backend → LLM provider**, so route the backend's outbound traffic through Burp too.

This is the short, same-host version. For a backend in Docker, per-runtime variables (JVM, Python, Node, Go,
.NET, Ruby), the libraries that ignore proxy env vars, and the invisible-proxy fallback, see
`docs/Proxying-Backend-Traffic.md`.

1. Export Burp's CA certificate (Proxy → Proxy settings → Import / export CA certificate) as PEM, e.g. `~/burp-ca.pem`.
2. Point the backend at Burp and trust the CA:

   ```bash
   # Common to most runtimes
   export HTTP_PROXY=http://127.0.0.1:8080
   export HTTPS_PROXY=http://127.0.0.1:8080

   # Python requests
   export REQUESTS_CA_BUNDLE=~/burp-ca.pem
   # Python httpx (used by OpenAI / Anthropic SDKs)
   export SSL_CERT_FILE=~/burp-ca.pem

   # Node
   export NODE_EXTRA_CA_CERTS=~/burp-ca.pem
   ```

   JVM backends: import the CA into a truststore and pass `-Djavax.net.ssl.trustStore=...` plus `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8080`.

3. If a client ignores proxy env vars, use Burp's **invisible proxying** (Proxy listener → Request handling → Support invisible proxying) with a hosts-file entry pointing the provider hostname at the listener.
4. **Streaming**: LLM responses are often SSE, and Burp treats `text/event-stream` as streaming by default. Streamed responses are passed straight to the client and **never reach extensions**, so CanaryAgent cannot see markers or tools in them (verified 2026-10-12). While testing, untick "Treat responses with the text/event-stream MIME type as streaming" under Settings → Network → HTTP → Streaming responses, make sure the LLM host is not in the streaming prefix list, or disable streaming in the client. CanaryAgent logs a one-time Output line ("A streamed response ... arrived incomplete") only for the other case, a partial body that does arrive.
5. Only proxy traffic for applications and API accounts you own or are authorized to test.

## 7. Remote debugging from IntelliJ

### Configure Burp for debugging

1. Find Burp's `user.vmoptions` file (usually next to the Burp executable or in the user config directory).
2. Add a line:
   ```
   -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005
   ```
   **Bind to `127.0.0.1` only.** JDWP is unauthenticated; `address=*:5005` exposes remote code execution on every network interface — especially dangerous on a testing machine connected to client networks.
3. Restart Burp.
4. Remove the line when you're done debugging.

### Attach IntelliJ debugger

1. `Run → Edit Configurations → + → Remote JVM Debug`
2. Name it (e.g. "Burp Debug")
3. Host: `localhost`, Port: `5005`
4. Click Debug

Set breakpoints in your Kotlin sources. When Burp loads or exercises the extension, execution will pause.

## 8. Useful IntelliJ tips for this project

- **Kotlin plugin** is included with IntelliJ; ensure it is enabled.
- Use `Ctrl+Shift+A` (or `Cmd+Shift+A`) and search for "Gradle" actions.
- Live templates / postfix completion work well for Kotlin (`data class`, `fun`, etc.).
- Structure view (`Alt+7` / `Cmd+7`) helps navigate packages.
- When adding new handlers or UI panels, register them from `CanaryAgentExtension.initialize()` so the entry point stays thin.
- Keep the Montoya dependency as `compileOnly` — Burp provides it at runtime. Shadow must still package the Kotlin standard library (and the JSON library, if used).
- Run detector/scanner unit tests from the gutter icons; they don't need Burp running.

## 9. Development workflow

1. Edit Kotlin sources in IntelliJ.
2. Run `./gradlew test` for detector/scanner changes.
3. Run `shadowJar` (Gradle tool window or terminal).
4. Reload the extension in Burp.
5. Exercise the feature (proxy browser + backend traffic, right-click menu, open the CanaryAgent tab).
6. Check Burp's Extensions → Output / Errors tabs for logs.
7. Attach the remote debugger when you need to step through code.

## 10. References

- Architecture outline: `CanaryAgent-Architecture.md`
- Agent guidance: `CLAUDE.md`
- Project sketch: `Agent-Project-Sketch-Revised.md`
- Kotlin Burp template: https://github.com/ncoblentz/KotlinBurpExtensionBase
- Montoya API Javadoc: https://portswigger.github.io/burp-extensions-montoya-api/javadoc/
- Official Montoya examples: https://github.com/PortSwigger/burp-extensions-montoya-api-examples
- PortSwigger extension docs: https://portswigger.net/burp/documentation/desktop/extend-burp/extensions/creating

---

*Keep this file next to the other project documents. Update it if the build or debugging steps change.*
