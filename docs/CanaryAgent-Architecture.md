# CanaryAgent — Architecture Outline  
**Kotlin + Montoya API**

**Status**: Design / architecture phase  
**Language**: Kotlin (JVM 21)  
**API**: Burp Montoya API  
**Date**: 2026-10-12 (revised)  

## One-line description

Annotates agent-related traffic in Burp, builds a simple tool inventory, and makes it easy to place and track markers in content the agent will read.

## Purpose

Help web developers notice, organize, and work with LLM agent traffic in their own applications as it passes through Burp, so they can better understand and assess agent-related behavior with minimal friction.

## Stack decision

- **Kotlin** + **Montoya API** (officially supported; compiles to a standard JAR that Burp loads as a Java extension).
- **Gradle** (Kotlin DSL) + **Shadow plugin** (`com.gradleup.shadow`) for a fat JAR.
- Target JVM 21 via Kotlin toolchain.
- No reliance on the legacy Extender API.
- One small JSON parser dependency is permitted (see "Dependencies").

## Build setup

```kotlin
// build.gradle.kts (essentials)
plugins {
    kotlin("jvm") version "2.1.20"            // pin exact; no dynamic versions in plugins {}
    id("com.gradleup.shadow") version "8.3.5" // successor to com.github.johnrengelman.shadow; or current 9.x
}

group = "com.canaryagent"
version = "1.0.0"

repositories { mavenCentral() }

kotlin {
    jvmToolchain(21) // keeps Kotlin and Java JVM targets aligned
}

dependencies {
    // Verify the exact published version on Maven Central before pinning.
    compileOnly("net.portswigger.burp.extensions:montoya-api:<verified-version>")
    implementation(kotlin("stdlib"))
    implementation("org.json:json:<current>") // or kotlinx-serialization-json; see Dependencies

    testImplementation(kotlin("test"))
    testImplementation("net.portswigger.burp.extensions:montoya-api:<verified-version>")
}

tasks.shadowJar {
    archiveBaseName.set("canaryagent")
    archiveClassifier.set("all")
}

tasks.test { useJUnitPlatform() }
```

**Build command**: `./gradlew shadowJar`  
**Output**: `build/libs/canaryagent-1.0.0-all.jar` (name fixed by `archiveBaseName`)  
Load in Burp as a Java extension.

Remote debugging: add `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005` to Burp's `user.vmoptions` and attach from IntelliJ. **Bind to `127.0.0.1` only** — JDWP is unauthenticated remote code execution.

## Dependencies

- Montoya: `compileOnly` (Burp provides it at runtime).
- Kotlin stdlib: bundled via Shadow.
- JSON parsing: key matching on nested JSON with regex/`contains` is fragile (keys escaped inside string values cause false positives). Use one of:
  1. Montoya JSON utilities, **if** the pinned Montoya version provides them (check Javadoc for `Utilities`), or
  2. `org.json` (small, no transitive deps) or `kotlinx-serialization-json`.
- No other runtime dependencies without a clear core need.

## Suggested package structure

The layout below is a recommendation. The coding agent may adjust package names, merge related classes, or introduce additional packages if it improves clarity, as long as the overall separation of concerns is preserved.

```
src/main/kotlin/com/canaryagent/
├── CanaryAgentExtension.kt          # BurpExtension entry point
├── model/
│   ├── ToolEntry.kt
│   ├── MarkerHit.kt
│   └── AnnotatedTrafficItem.kt
├── inventory/
│   ├── ToolInventory.kt
│   └── ToolExtractor.kt
├── markers/
│   ├── MarkerTracker.kt
│   ├── MarkerGenerator.kt
│   ├── MarkerScanner.kt             # encoding-tolerant detection
│   └── CollaboratorPoller.kt        # Pro only
├── annotation/
│   ├── AgentTrafficDetector.kt
│   ├── BodyParser.kt                # size cap, MIME filter, depth limit
│   └── TrafficAnnotator.kt
├── ui/
│   ├── CanaryAgentTab.kt
│   ├── ToolInventoryPanel.kt
│   ├── MarkerHitsPanel.kt
│   └── AnnotatedTrafficPanel.kt
├── contextmenu/
│   └── CanaryAgentContextMenuProvider.kt
├── handlers/
│   ├── ProxyAnnotationHandler.kt    # highlight + notes in Proxy history
│   ├── HttpObservationHandler.kt    # markers + detection across all tools
│   └── WebSocketObservationHandler.kt
└── util/
    ├── Logging.kt
    ├── Edition.kt                   # Community vs Professional checks
    └── Extensions.kt

src/test/kotlin/com/canaryagent/      # detector + scanner unit tests
src/test/resources/corpus/            # captured sample bodies
```

Resources (if any icons, etc.) go under `src/main/resources`.

## Key classes and responsibilities

### `CanaryAgentExtension` (implements `BurpExtension`)
- `initialize(api: MontoyaApi?)` — null-check, set name "CanaryAgent", store API reference, register handlers, context menu, suite tab, and unloading handler.
- Detect Burp edition once; pass a capability flag (e.g. `collaboratorAvailable`) to components.
- Log a first-run hint pointing to the backend proxy setup docs.
- Keeps initialization thin; delegates to focused components.

### Models (`model/`)
- `ToolEntry`: name, observed parameter keys, first/last seen, message count, source identifier.
- `MarkerHit`: token/label, insertion location (request id + field), status (placed / seen in response / seen in tool call), encoding seen, timestamps, link to reappearance.
- `AnnotatedTrafficItem`: method, URL, short note, timestamp, session/correlation hint, reference to the stored message (temp-file copy, not a live object).

**Models are immutable** (`val` only, immutable collections). Stores replace entries atomically. This avoids `ConcurrentModificationException` when the EDT reads while handler threads write, and keeps `data class` `equals`/`hashCode` stable.

### `ToolInventory`
- Backed by `ConcurrentHashMap<String, ToolEntry>`, keyed by normalized (lowercased) tool name.
- `addOrUpdate` uses `compute` and `copy(...)` to produce a new immutable entry.
- `snapshot()` returns a list of immutable entries, safe to hand to the UI.
- Message count increments **once per message** in which a tool appears.

```kotlin
data class ToolEntry(
    val name: String,                 // first-seen display casing
    val parameterKeys: Set<String> = emptySet(),
    val messageCount: Int = 0,
    val firstSeen: Long,
    val lastSeen: Long,
    val source: String = "",
)

class ToolInventory {
    private val tools = ConcurrentHashMap<String, ToolEntry>()

    /** Call once per message per tool. */
    fun addOrUpdate(name: String, keys: Set<String>, source: String, now: Long = System.currentTimeMillis()) {
        tools.compute(name.lowercase()) { _, e ->
            e?.copy(
                parameterKeys = e.parameterKeys + keys,
                messageCount = e.messageCount + 1,
                lastSeen = now,
            ) ?: ToolEntry(name, keys.toSet(), 1, now, now, source)
        }
    }

    fun snapshot(): List<ToolEntry> = tools.values.toList()
}
```

### `MarkerGenerator`
- Tokens: lowercase alphanumeric, fixed prefix + random base36 suffix (≥ 8 chars), e.g. `cnry7f3a9k2m`.
- No hyphens/punctuation (LLMs tend to reformat them).
- Uses `SecureRandom`; checks for collision with existing tokens.

### `MarkerTracker`
- `ConcurrentHashMap<String, MarkerHit>` keyed by lowercased token.
- Records placement immediately; excludes the originating request from hits.
- Status transitions: placed → seen in request / response → seen in tool result → seen in tool call →
  collaborator interaction; only upward (tool call wins over tool result in the same hit). Tool results
  are recognised by structure: `role: tool`/`function` messages, `tool_result` / `function_call_output`
  blocks, `functionResponse` values (F4, 2026-10-22).

### `MarkerScanner`
- Single pass per message: search for the prefix (case-insensitive) in each encoding variant, then look up candidates in the tracker map. Never iterate all markers per message.
- Encoding variants:
  - Raw text
  - JSON-escaped (including `\u00XX`)
  - URL-encoded
  - Base64 of the token (three alignment offsets)
- Classifies the hit: **tool call** if the token is inside a value under `tool_calls`, `function_call`, `tool_use`, `arguments`, or `input` (or a descendant); otherwise **response**.
- Runs **regardless of the matching toggle** and **regardless of target scope**.

### `CollaboratorPoller` (Professional only)
- Created only when the edition supports Collaborator; otherwise the menu action is hidden.
- Polls interactions on a `ScheduledExecutorService` (never on the EDT).
- Correlates interactions to `MarkerHit` entries.
- Shut down in `extensionUnloaded()`.

### `BodyParser`
- Skips non-text MIME types.
- Skips bodies above a size cap (default ~2 MB).
- JSON recursion depth limit (default ~12).
- Handles SSE bodies by splitting `data:` lines and parsing each fragment.

### `AgentTrafficDetector` + `TrafficAnnotator`
- Apply the strong and medium signals from `Agent-Project-Sketch-Revised.md`.
- Matching toggle (on = strong + medium; off = manual only). Toggle affects annotation/inventory only, never marker observation.
- Annotate messages and feed observed tools into the inventory.
- Best-effort session grouping without full conversation reconstruction.
- Optional setting: annotate only in-scope traffic (default: annotate all).

### Handlers
- **`ProxyAnnotationHandler`** (Proxy response handler): sets highlight color + notes on Proxy history items. Montoya does not appear to support custom Proxy history columns; richer data lives in the CanaryAgent tab.
- **`HttpObservationHandler`** (`HttpHandler`): sees traffic from all tools (Proxy, Repeater, Intruder, extensions). Runs marker scanning and (when enabled) detection/inventory.
- **`WebSocketObservationHandler`**: marker scanning on WebSocket messages (both directions). Heuristic annotation on WebSockets is optional/stretch.
- **Streaming caveat**: verify how streamed (SSE/chunked) responses reach handlers, including the effect of Proxy streaming settings. If full bodies are unavailable, log a one-time warning and document the workaround.

### UI (`ui/`)
- Single suite tab with three sections: Tool Inventory, Marker Hits, Annotated Traffic.
- Panels are views only; no request editing inside the tab.
- **All table-model mutations happen on the EDT** via `SwingUtilities.invokeLater`. Handlers publish change events; panels pull `snapshot()`s. Coalesce rapid updates (e.g. a `javax.swing.Timer` refresh every ~250 ms) to avoid flooding the EDT.
- Parent any dialogs to `api.userInterface().swingUtils().suiteFrame()`.

### Context menu
- Actions: insert marker, insert Collaborator data (Pro only), mark as agent content, send tool call to Repeater, copy tool snippet.
- **Send tool call to Repeater (v1)**: sends the original request to Repeater with the tab named after the tool (e.g. `tool: search_docs`). Synthetic tool-call requests are deferred to v2.
- Operates on the currently open editor or selected history item.

### Memory
- Store `HttpRequestResponse.copyToTempFile()` results (or lightweight IDs) in `AnnotatedTrafficItem` and `MarkerHit`, never live message objects.
- Cap the Annotated Traffic list size (e.g. most recent 5,000 items) with oldest-first eviction.

### Unload
- Montoya registrations are cleaned up by Burp; the extension must still:
  - Shut down the Collaborator scheduler and any executors.
  - Stop Swing timers.
  - Clear stores.

## Design principles

- Protocol-agnostic, inexpensive heuristics.
- Focus on clear manual aids rather than automation.
- No automatic content generation, no full conversation state machine, no vendor-specific parsers.
- Stay inside the normal Proxy / Repeater workflow.
- Clean unload; avoid retaining large message objects longer than needed.
- Prefer Burp networking for any outbound requests.

## Implementation order

1. Gradle project + skeleton `CanaryAgentExtension` that loads and logs.
2. Immutable models + thread-safe `ToolInventory` and `MarkerTracker`.
3. **Marker generation, insertion (context menu), and observation** (`MarkerScanner`, `HttpObservationHandler`, `WebSocketObservationHandler`). Useful on its own, before any heuristics.
4. Test corpus + `BodyParser` + `AgentTrafficDetector` with unit tests.
5. Inventory extraction + Proxy annotation (highlight + notes).
6. Suite tab with the three panels.
7. Collaborator integration (Pro only, edition-gated).
8. Polish: filtering, session grouping hints, scope setting, unload cleanup, streaming verification.

## Success criteria (v1)

- Install, point at real traffic (with backend proxying documented), and immediately see agent-related requests and observed tools.
- Place and track a marker in a few clicks on content already in the request editor.
- Markers detected across raw / JSON-escaped / URL / base64 / case-changed echoes, in HTTP and WebSocket traffic.
- Works on Community (Collaborator hidden) and Professional.
- No noticeable slowdown; useful with zero configuration.

## Out of scope for v1

- Full conversation threading
- Automatic content suggestions or generation
- Deep schema visualization
- Editing or replaying directly from the CanaryAgent tab
- Vendor-specific protocol parsers
- Synthetic tool-call request construction

## References

- Project sketch: `Agent-Project-Sketch-Revised.md`
- Development setup: `DEVELOPMENT.md`
- Agent guidance: `CLAUDE.md`
- Montoya API: https://portswigger.github.io/burp-extensions-montoya-api/javadoc/
- Official examples: https://github.com/PortSwigger/burp-extensions-montoya-api-examples
- Kotlin template: https://github.com/ncoblentz/KotlinBurpExtensionBase
- BApp criteria: https://portswigger.net/burp/documentation/desktop/extend-burp/extensions/creating/bapp-store-acceptance-criteria

---

*This document is the architecture handoff for implementation.*

## Implementation notes (deviations from the outline above)

Recorded as the code lands; see git history for details.

- **JSON library**: `org.json` is used instead of Montoya's `JsonNode`. Montoya's parser is only usable
  inside a running Burp (its static factory resolves through Burp's object factory), which would make
  the detector and scanner untestable. `org.json` has no transitive dependencies and is bundled by Shadow.
- **Marker hits on requests**: hits are recorded on outgoing requests too (backend → LLM provider is the
  main place a planted marker reappears), not only on responses and WebSocket messages. The originating
  request is excluded by matching method + host + path of the placement, so re-sending it from Repeater
  is never a hit.
- **Custom markers**: user-supplied or selected text has no shared prefix, so each custom token is searched
  directly. This is the one bounded exception (64 tokens) to "never loop all markers per message".
- **`TrafficRef`** stores `copyToTempFile()` copies of request and response separately rather than an
  `HttpRequestResponse` pair, so request-only hits need no synthetic empty response.
- **`MarkerOrigin.location`** is the field name for the in-message position (the natural name `field` is
  Kotlin's backing-field keyword inside getters).
- **Marker observation logging**: until the suite tab exists, placements and hits are logged to the
  extension's Output tab, one line each.
- **Proxy annotation lives in the `HttpHandler`**, not a separate `ProxyResponseHandler`. Annotations
  returned from `RequestToBeSentAction`/`ResponseReceivedAction.continueWith(message, annotations)` are
  applied by Burp to the Proxy history item, and the same handler already sees every tool's traffic.
  Request-side analysis is parked by message id and merged when the response arrives.
- **Settings** use Montoya's settings panel (Settings → Extensions → CanaryAgent, project-persisted):
  the matching toggle and an "in-scope only" option. Both gate annotation/inventory only.
- **Heuristic additions**: NDJSON bodies are split per line; `tool_input`/`action_input`/`tool_name`
  (LangChain-style) count as a medium "framework keys" signal; `name` + `parameters` is only a signal
  when the parameters object looks like a schema (`type`/`properties`/`required`) or sits under a tool list,
  so product catalogues and similar do not fire. The cross-message "arguments absent from the user
  message" medium signal is not implemented (it needs conversation state).
- **Hit policy** (`markers/HitPolicy`): outbound traffic (requests, client→server WebSocket frames) from
  user-driven tools such as Repeater, Intruder or extensions never produces a hit; only Proxy-originated
  outbound traffic counts, and never for the request the marker was inserted into. Inbound traffic always
  counts. `MarkerStatus` gained `SEEN_IN_REQUEST`; status is derived from the channel and only moves up.
- **Scanner input**: `MarkerScanner.scan(text, structuredBody)` finds encodings over the whole message but
  classifies tool-call context on the body alone, because a start line and headers defeat the JSON parser.
- **WebSockets**: Proxy sockets are handled by `ProxyWebSocketObservationHandler`, which annotates hit frames
  (highlight + note) via `InterceptedTextMessage.annotations()`; the generic `WebSocketCreatedHandler` skips
  Proxy sockets so each frame is scanned once. Both share `WebSocketMarkerObserver`.
- **Suite tab** (`ui/`): store listeners run on Burp threads and only poke a `RefreshCoalescer`
  (non-repeating 250 ms `javax.swing.Timer`); the EDT then pulls `snapshot()` into a read-only
  `SnapshotTableModel`. Column definitions live in `ui/Columns.kt` as plain functions so they are
  unit-tested without Swing. Selecting a Tool Inventory row narrows Annotated Traffic to rows whose
  note mentions the tool (no cross-message linking yet). Annotated Traffic has a read-only
  request/response viewer built from Montoya's `createHttpRequestEditor(READ_ONLY)` /
  `createHttpResponseEditor(READ_ONLY)`; the tab never edits messages.
- **Collaborator** (`collaborator/`, Professional only): a payload is tracked by its interaction **id**
  (lowercase) with the full hostname as label, so the existing custom-token scan catches in-band echoes of
  either form and `Interaction.id()` is the direct lookup key. `CollaboratorService` restores the client
  from the key stored in the project (`restoreIfStored()` at load, before the poller starts) or creates it
  lazily and queues the new key through `PersistenceService`; the pure `ClientBootstrap.resolve` holds the
  restore-or-create decision. It goes unavailable after one failure (Collaborator disabled). `CollaboratorPoller` runs a daemon
  `ScheduledExecutorService` (10 s fixed delay) and hands interactions to the pure `CollaboratorCorrelator`.
  New `MarkerStatus.OOB_INTERACTION` ranks above tool call; new `TrafficChannel.COLLABORATOR("oob")` is
  inbound, so `HitPolicy` always counts it. Each interaction also carries an `Attribution` label
  (`browser-attributed` / `agent-attributed` / `unattributed`) with the signals that produced it, computed by
  the pure `InteractionAttribution` from the captured request's browser-only headers and the row's
  `inBandStatus` (the highest status reached in-band, which no interaction raises; the plain status is
  already promoted by the first DNS lookup before the HTTP fetch is evaluated). The label sits beside the
  status and never changes it: attribution, not suppression.
- **Settings are read live, never at load.** Burp's settings panel returns stale values during
  `initialize` (verified: four reloads logged the same old values while the UI showed new ones). Every
  consumer reads at use time: the annotator per message, `PersistenceService` per flush, `CollaboratorPoller`
  before each tick.
- **Persistence** (`persistence/`): `KeyValueNode` is a small seam over Burp's `PersistedObject`
  (`MontoyaNode`; `MemoryNode` in tests). `MarkerCodec`/`ToolCodec` are pure. `PersistenceService` restores
  at load, then writes behind (1 s daemon scheduler) from store listeners; `dispose()` detaches and flushes
  before the runtime clears the stores. Tree: `extensionData()/canaryagent/{markers/<token>, tools/<name>}`.
- **Streaming settings hint** (`annotation/StreamingSettingsHint`): at load, the runtime exports
  `project_options.http` and logs one WARNING when Burp treats `text/event-stream` as streaming or lists
  streaming prefixes, because streamed responses never reach extensions. Key names are matched tolerantly
  (first object whose key contains "streaming"; booleans mentioning "event"; prefix arrays).
- **Streaming** (`annotation/StreamingCheck`): an SSE body that is empty or lacks the terminating blank
  line, or an empty chunked text body, is taken as "arrived before the server finished"; the HTTP handler
  logs one warning with the workaround and still scans what it has. The Collaborator polling interval is
  a project setting (5–300 s, read at load).
- **Origin exclusion** is endpoint + body hash (`MarkerOrigin.bodyHash`), never endpoint alone: LLM APIs
  reuse one path every turn, so only the exact message the marker was inserted into is excluded.
- **`CanaryAgentRuntime`** holds all wiring; `CanaryAgentExtension` is a two-line shell. A dev-only
  `src/selftest` source set reuses the runtime under a second entry class and ships as a separate
  numbered `canaryagent-selftest-N.jar` (production entry class excluded) for in-Burp verification.
- **UI seams**: panels depend on `UiActions` (Repeater, confirm, clipboard) and `MessageViewer` so they are
  unit-tested headless; Montoya implementations live next to them.
- **Context menu**: "Mark as agent content" works on the open editor or selected history rows (≤ 50);
  "Send to Repeater as tool: X" sends the original request with a named tab; "Copy tool snippet"
  copies `name(key, key)` lines. Menu-time parsing is capped at 256 KB per body to keep the EDT responsive.
