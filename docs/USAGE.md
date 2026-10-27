# Using CanaryAgent to test a web app

A hands-on walkthrough for assessing an application whose backend uses an LLM agent: a chatbot with tools,
a RAG assistant, a multi-step workflow. It assumes CanaryAgent is loaded in Burp and the app is one you own
or are authorised to test. For installing and building, see the README. For getting the backend's traffic
into Burp, see `Proxying-Backend-Traffic.md`.

## What you are testing for

CanaryAgent does not attack anything. It answers one question reliably: **does content I planted reach the
model, and what does the model do with it?** That question underlies the main agent risks:

| Risk | What it looks like in CanaryAgent |
|---|---|
| Indirect prompt injection: text in a document, page, email or tool result steers the model | A marker planted in that content later shows *seen in tool result* (delivered to the model) or *seen in tool call* (acted on) |
| Tool-call hijacking: injected content makes the agent call a tool, or call it with attacker data | *seen in tool call*, with the hit inside `arguments` |
| Data exfiltration through tools: the agent fetches an attacker URL or sends data out | *collaborator interaction* on a Collaborator payload planted in agent-readable content |
| Over-broad tool surface: more tools or parameters exposed than the feature needs | Tool Inventory lists them, with the parameter keys actually visible |
| Agent traffic hidden in ordinary endpoints | Orange or yellow Proxy rows on endpoints you did not expect |

## Setup checklist

- [ ] Burp Proxy listener on `127.0.0.1:8080`, browser proxied through it.
- [ ] Backend outbound traffic proxied through Burp and Burp's CA trusted in the backend
  (`Proxying-Backend-Traffic.md`). Without this you see only browser → backend chat JSON.
- [ ] Settings → Network → HTTP → Streaming responses: untick **Treat responses with the text/event-stream
  MIME type as streaming** while testing, otherwise streamed LLM responses never reach the extension.
  CanaryAgent logs a WARNING at load when this is still on.
- [ ] Settings → Extensions → CanaryAgent: **Automatically flag agent-related traffic** on. Leave
  **Only annotate in-scope traffic** off until you know which hosts matter.
- [ ] Open the **CanaryAgent** tab once so it is in view. Press **Clear table** on each table for a clean start (each asks for confirmation).

## Phase 1: map the agent

Use the app normally for a few minutes: ask questions, trigger every feature that might call a tool
(search, calendar, email, file lookup, order status, anything that acts).

Then read three places:

1. **Proxy → HTTP history.** Orange rows carry strong agent signals (`tools`, `tool_calls`, `messages` with
   roles, tool schemas). Yellow rows carry medium signals. The note column says why. Note which hops carry
   the structure: usually the backend → provider calls, sometimes browser → backend as well.
2. **CanaryAgent tab → Tool Inventory.** Every tool name the model was offered or called, the parameter
   keys that were visible, how many messages mentioned it, and the source URL. Select a tool to narrow the
   Annotated Traffic list to messages that mention it.
3. **Annotated Traffic.** The flagged request/response pairs. The Session column groups turns by a hashed
   cookie or Authorization header; **Show only this session** isolates one conversation.

Write down, per tool: what it does, which parameters it takes, and what content the model reads before it
decides to call it. That content is where markers go.

Right-click **Copy tool snippet** on a flagged message to copy `name(key, key)` lines for notes.

If a message is agent-related but was not flagged (unusual format), right-click it → **Extensions →
CanaryAgent → Mark as agent content**. It joins the Annotated Traffic list and its tools join the inventory.

## Phase 2: plant markers where the agent reads

A marker is a token like `cnryk3f9a2mz`: lowercase letters and digits only, nothing an LLM is tempted to
reformat. Three ways to place one:

| Situation | Action |
|---|---|
| You can edit the request in Repeater | Click at the spot to place the caret (right-clicking does not move it), then right-click → **Extensions → CanaryAgent → Insert new marker**, then send |
| The content is typed into the browser (chat box, profile field, document editor) | Right-click anywhere → **Copy new marker to clipboard**, paste it into the field, submit |
| The content already contains a distinctive string you want to follow | Select it → **Track selected text as marker** |
| You want a specific phrase tracked, for example an instruction sentence | **Insert custom marker...** and type it; matching is case-insensitive |

Placement is recorded immediately as *placed* in Marker Hits, with where it went. The request you placed
it in never counts as a hit, so a row only changes when the token comes back somewhere else.

Where to plant, from baseline to interesting:

1. **The user message itself.** Baseline. Confirms the pipe: the token should come back *seen in response*
   if you ask the model to repeat it. If this never fires, fix the proxy or streaming setup before going on.
2. **Content the agent retrieves, not what the user types.** This is the real target. A forum post, a
   product review, a document in a knowledge base, a profile field, an email body, a calendar note, a web
   page the agent fetches. Plant the marker there, through the app's own UI, then ask a question that makes
   the agent read it. If the marker comes back *seen in tool result*, content the agent read reached the
   model: the precondition for indirect prompt injection is met.
3. **An instruction the agent might obey.** Instead of a bare token, plant a short sentence that names a
   real tool from your inventory and asks the agent to call it, with the marker as the argument value. Keep
   the marker in the sentence so you can follow it. If the agent complies, the marker lands *seen in tool
   call*, inside that tool's `arguments`: the injected content steered an action.
4. **A Collaborator payload, to catch an outbound call (Professional).** If the inventory has a tool that
   fetches a URL or reaches another service, plant a Collaborator payload as the URL it should use
   (**Copy Collaborator payload to clipboard**). If the agent calls that tool, the backend reaches your
   Collaborator server and the row turns *collaborator interaction*, labelled *agent-attributed*. That is
   the agent itself induced into an out-of-band request.

A note on wording for 3 and 4: ask the agent to *do* something, not to *describe* a post. "Summarise the
latest reviews" invites a tool call; "what does the latest review say to do?" invites the model to quote
the instruction back harmlessly. A model that reads the marker but only quotes it has declined the
injection, which is itself a finding about that model, not a gap in the tool.

## Phase 3: read the results

Open **CanaryAgent → Marker Hits** and read each row's **Status**. The status only ever moves to a
stronger value, so the furthest a marker reached is what the row shows:

| Status | What it tells you |
|---|---|
| placed | Inserted, not seen again. The agent has not read it, or you have not triggered the path yet |
| seen in request | The token left in an outgoing request (often the one that re-serves it to the model) |
| seen in response | The model echoed it back in its reply |
| seen in tool result | The token was delivered to the model inside a tool's output. **Content the agent read reached the model** |
| seen in tool call | The token appeared inside a tool call's `arguments`. **The content steered an action** |
| collaborator interaction | A Collaborator payload was contacted out of band; the **Attribution** column says whether the agent or a browser caused it |

Other columns worth reading: **Encodings** (every form the token was seen in: raw, re-cased, JSON-escaped,
URL-encoded, base64), **Hits** (distinct messages it reappeared in; polling and reloads do not inflate it),
and **Last hit** (the most recent sighting, with its channel and URL).

To see the evidence behind a row, find the flagged message: search Proxy history for the provider host, or
use **Annotated Traffic** in the tab. The note on the row names the marker and tags it `(tool result)` or
`(tool call)`. In the request or response body, the marker sits inside a `role: tool` message (delivery) or
a `tool_calls[].arguments` string (steering).

**Scanner issues (Professional).** The same findings also appear in Burp's issue list: Burp's passive
scanning of Proxy traffic runs the check, and so does any audit you start on the flagged messages. A
marker inside a tool call raises a High-severity issue, a tool result Medium, a plain resurfacing
Informational. Nothing requires this: the Marker Hits table above is always the complete picture, on
both editions, scanned or not.

**Collaborator timing.** Interactions are fetched on the polling interval (30 seconds by default), so an
out-of-band hit can take up to that long to appear after the agent makes its call.

## Phase 4: escalate in Repeater

Once a path works from the browser, Repeater lets you iterate on the injection without re-driving the whole
app. Double-click an Annotated Traffic row, or right-click a Proxy row → **Send to Repeater**, to open the
request that carried the agent's turn.

- Edit the planted content or the surrounding instruction and resend, watching whether the status still
  reaches *seen in tool call*. This is how you narrow down which phrasing the model obeys.
- Keep non-ASCII out of what you type in Repeater: curly quotes and dashes get mangled into control bytes
  and the provider rejects the body. Plant the real markers through the app UI; use Repeater to vary the
  structure around them.
- The planting request never counts as a hit, and Repeater-origin requests are excluded from request-side
  hits by design, so a marker row changes only when the token genuinely reappears downstream.

When a marker reaches *seen in tool call* or *collaborator interaction / agent-attributed*, you have an
observed, reproducible finding: the exact request that carried it is in Proxy history, and the status ladder
records how far the planted content travelled.