# CanaryAgent

<p align="center">
  <img src="images/Canary-Agent-Logo-New-cropped.jpg" alt="CanaryAgent logo" width="320">
</p>

**CanaryAgent is a Burp Suite extension that finds indirect prompt injection in web apps that use LLM agents.**

## The how and what

- Plant a unique marker in something the agent will read: a forum post, a document, a profile field.

- Use the app normally. Ask the agent (the chatbot) something that needs a tool. A tool is a function
  the agent can call on your behalf: search the docs, read recent posts, look up an order, fetch a URL.

- CanaryAgent watches the traffic through Burp and reports where the marker came back.

- Marker in a tool result: your content reached the model.

- Marker inside a tool call's arguments: your content steered the agent.
  **Injection confirmed.**

- Plant a Burp Collaborator payload instead of a marker (Pro) and you also learn whether the agent
  actually reached out.

- A marker shows your content moved the agent. A Collaborator hit shows the agent made a real
  outbound request to a host you control. That is the exfiltration and SSRF case: content the agent
  read made a tool call out to Collaborator, or to a VPS in a real attack.

- Each Collaborator hit is labelled from its own headers, browser-attributed, agent-attributed or
  unattributed, so you know whether a human clicked it or the agent fetched it.

- It also highlights agent traffic in Proxy history and inventories every tool the agent is offered.

- Works with any LLM provider or framework. Reads traffic structure only. Never sends anything itself.


## Indirect prompt injection, caught on the wire

<table>
  <tr>
    <td width="50%"><a href="images/Canary-Agent-Clipboard.png"><img src="images/Canary-Agent-Clipboard.png" alt="CanaryAgent context menu in a Burp editor"></a></td>
    <td width="50%"><a href="images/marker-in-tool-result.png"><img src="images/marker-in-tool-result.png" alt="One provider transaction: the marker in a tool result, then in the model's tool call"></a></td>
  </tr>
  <tr>
    <td align="center"><sub><b>Step 1. Plant the marker.</b> Right-click → Extensions → CanaryAgent to insert a marker, or copy one to paste into the application's own UI</sub></td>
    <td align="center"><sub><b>Step 2. Delivered, then it steers.</b> In one provider transaction the planted marker arrives inside a <code>role: tool</code> message, then reappears inside the model's own tool-call <code>arguments</code>: indirect prompt injection, confirmed on the wire</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="images/marker-in-tool-call.png"><img src="images/marker-in-tool-call.png" alt="The model emitting a tool call that carries an attacker-supplied URL"></a></td>
    <td width="50%"><a href="images/agent-attributed-oob.png"><img src="images/agent-attributed-oob.png" alt="Marker Hits showing a collaborator interaction attributed to the agent"></a></td>
  </tr>
  <tr>
    <td align="center"><sub><b>Step 3. It steers an action.</b> The injection drives a complete tool call carrying an attacker-controlled URL in its <code>arguments</code></sub></td>
    <td align="center"><sub><b>Step 4. The action is caught.</b> The agent's outbound request lands as a Collaborator interaction, labelled <code>agent-attributed</code> — the strongest evidence the tool produces</sub></td>
  </tr>
</table>

Click any image to open it at full size.

## Features

- **Markers.** Right-click in any request, response or WebSocket editor to insert a unique `cnry...` token
  at the selection or caret, insert your own text, track selected text, or copy a fresh marker for pasting
  into a browser form. Each placement is recorded immediately.
- **Encoding-tolerant tracking.** Every message Burp sends or receives, in any tool and in or out of
  scope, is scanned once for markers as raw text, re-cased, JSON `\u`-escaped, percent-encoded and base64.
  A marker found inside `tool_calls`, `function_call`, `tool_use`, `arguments` or `input` is reported as
  "seen in tool call". Outbound traffic from Repeater, Intruder or other extensions never counts as a hit;
  that is you re-sending your own marker.
- **Collaborator payloads (Professional).** Insert a Collaborator hostname the same way. In-band echoes
  are tracked like markers, and a background poller correlates DNS/HTTP/SMTP interactions to the row,
  which then shows "collaborator interaction".
- **Passive detection.** Request and response bodies (JSON, NDJSON, SSE, form data) are parsed and matched
  structurally against strong signals (`tools`, `tool_calls`, `tool_use`, `messages` with roles, tool
  schemas) and medium signals (role arrays, schema keys, framework keys, agent-like paths). Proxy history
  rows get a highlight and a note: orange for strong, yellow for medium, red for a marker hit.
- **Tool Inventory.** Tool names and the parameter or argument keys that were visible, counted once per
  message, with first/last seen and source URL.
- **CanaryAgent tab.** Tool Inventory, Marker Hits and Annotated Traffic in one suite tab. Filter and sort
  each table, select a tool to narrow the traffic to it, view the selected request and response read-only,
  and double-click to send to Repeater. The tab never edits messages.

## Install

1. Build the JAR (below) or take `build/libs/canaryagent-1.0.0-all.jar`.
2. Burp → **Extensions** → **Installed** → **Add** → type **Java** → select the JAR.
3. The Output tab logs a startup line and a hint about proxying your backend.

## Quick start

1. **Proxy the backend, not just the browser.** Tool definitions and tool calls travel from your backend to
   the LLM provider. Point the backend's `HTTP_PROXY`/`HTTPS_PROXY` at Burp and trust Burp's CA. Step by
   step for Python, Node and the JVM: [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md), section 6.
2. Use the application normally. Agent-related requests turn orange or yellow in Proxy history; the Tool
   Inventory fills in.
3. Find content the agent will read (a document body, a tool result, a page the agent fetches), right-click
   in the editor → **Extensions → CanaryAgent → Insert new marker**, and send it.
4. Trigger the agent. Watch the Marker Hits row: *placed* → *seen in request* / *seen in response* →
   *seen in tool result* → *seen in tool call*, or *collaborator interaction* for an out-of-band fetch.
5. Double-click an annotated traffic row to continue in Repeater.

## Context menu reference

| Action | Where | What it does |
|---|---|---|
| Insert new marker | editable HTTP or WebSocket editor | Generates `cnry` + 8 base36 chars and inserts it at the selection or caret |
| Insert custom marker... | editable editor | Inserts and tracks text you type (matched case-insensitively) |
| Insert Collaborator payload | editable editor, Professional | Inserts a fresh Collaborator hostname and tracks it |
| Track selected text as marker | any editor with a selection | Tracks the selection without changing the message |
| Copy new marker / Collaborator payload to clipboard | anywhere | For pasting into a browser or another tool |
| Mark as agent content | editor or selected history rows | Forces annotation and inventory even when matching is off |
| Send to Repeater as tool: *name* | message containing a tool | Sends the original request to a Repeater tab named after the tool |
| Copy tool snippet | message containing tools | Copies `name(key, key)` lines |

## Settings

Settings → Extensions → CanaryAgent (saved per project):

- **Automatically flag agent-related traffic**: on by default. Off means only manually marked items are
  annotated. Marker tracking is never affected.
- **Only annotate in-scope traffic**: off by default. Markers are tracked everywhere regardless, because
  they usually reappear on LLM provider hosts outside your scope.
- **Do not persist markers and tool inventory in the project**: off by default, so state is saved. Ticked
  stops saving immediately; what is already stored still comes back at load until you press Clear table.
- **Collaborator polling interval**: 10 seconds by default, read before every poll.

All settings take effect immediately; nothing needs a reload.

## Reading the results

| Status | Meaning |
|---|---|
| placed | Inserted, not seen again yet |
| seen in request | Found in an outgoing Proxy request or client→server WebSocket frame |
| seen in response | Found in a response or server→client frame, outside tool-call keys |
| seen in tool result | Found inside a tool result handed to the model (a `role: tool` message, a `tool_result` block, `function_call_output`): content the agent read was delivered to it |
| seen in tool call | Found inside a tool call's arguments or input |
| collaborator interaction | Burp Collaborator recorded a DNS, HTTP or SMTP interaction for the payload. The **Attribution** column says what that interaction's own evidence suggests caused it: *browser-attributed* (Sec-Fetch headers or a `/favicon.ico` fetch; Sec-CH-UA, Upgrade-Insecure-Requests and Referer are listed as evidence but do not decide), *agent-attributed* (no browser evidence, and the payload had already been seen in agent traffic) or *unattributed* (most DNS lookups). The label never changes the status |

Status only ever moves to a stronger value. The Encodings column lists every form the marker was seen in.
**Hits** counts distinct messages: the same body from the same URL again (a poll, a page reload) updates
Last hit but not the count, while a new chat turn or a changed page counts. Collaborator interactions always count.

## Limitations

- Markers and the tool inventory are saved in the Burp project and come back after a reload or restart,
  including each marker's last hit with its request and response. The Clear table buttons remove the stored data
  too. The Collaborator client is saved with them, so payloads inserted before a reload keep polling for
  interactions. The Annotated Traffic list is in memory only. A setting turns persistence off.
- Parameter keys are shown alphabetically; the JSON library does not preserve source order.
- No conversation threading. Related turns are grouped only by a hashed session hint (cookie or
  Authorization header), shown in the Session column and selectable via "Show only this session".
- **Streamed responses are not observed.** When Burp treats a response as streaming, which it does by
  default for `text/event-stream`, the Proxy passes it straight to the client and extensions never see it.
  Markers echoed in a streamed LLM response go unseen. While testing, untick "Treat responses with the
  text/event-stream MIME type as streaming" under Settings → Network → HTTP → Streaming responses, or
  turn off streaming in the client. The extension logs a WARNING line at load when that setting or a
  streaming prefix is active. A separate one-time warning covers the other case, a partial body that
  does arrive.
- "Send to Repeater as tool" cannot carry the Proxy note; Burp's own send does.

## Build and test

Gradle fetches a JDK 21 toolchain on its own if none is installed; the JAR targets Java 21 bytecode and
runs on Burp's bundled runtime.

```bash
./gradlew test          # unit tests, no Burp needed
./gradlew shadowJar     # build/libs/canaryagent-1.0.0-all.jar
```

## Documentation

- [`docs/USAGE.md`](docs/USAGE.md): hands-on walkthrough for testing an app with CanaryAgent
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md): IntelliJ setup, proxying the backend, remote debugging
- [`docs/CanaryAgent-Architecture.md`](docs/CanaryAgent-Architecture.md): architecture and implementation notes
- Montoya API: https://portswigger.github.io/burp-extensions-montoya-api/javadoc/

## License and attribution

Licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).

Bootstrapped from [KotlinBurpExtensionBase](https://github.com/ncoblentz/KotlinBurpExtensionBase) by Nick Coblentz.
