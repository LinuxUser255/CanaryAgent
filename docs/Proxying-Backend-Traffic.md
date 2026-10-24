# Proxying a backend's LLM traffic through Burp

CanaryAgent's useful traffic is **backend → LLM provider** (the `messages` array, `tools`, `tool_calls`), not
browser → backend. This page gets that traffic into Burp when the backend runs in a Docker container, with
exact settings per runtime. `DEVELOPMENT.md` section 6 is the short, same-host version.

Only proxy applications and API accounts you own or are authorised to test.

## 0. Answer these first

They decide which block of section 3 applies.

| Question | Why it matters |
|---|---|
| Runtime and HTTP library of the service that calls the provider | Decides proxy and CA variables; several libraries ignore proxy env vars |
| Tools / function calling, or chat only | Chat only still flags on the `messages` array, but the inventory stays empty |
| Streaming on or off | Streamed (SSE) responses never reach extensions unless Burp's streaming setting is changed |
| `docker run` or `docker compose` | Where the env vars, mount and host alias go |

## 1. A Burp listener the container can reach

Containers cannot reach the host's loopback. Add a second listener and leave `127.0.0.1:8080` for the browser
and the self-test.

- [ ] Proxy → Proxy settings → Proxy listeners → **Add**. Port `8081`. Bind to address: **All interfaces**
  (or the Docker bridge address, usually `172.17.0.1`, if the machine is on an untrusted network).
- [ ] How the container names the host:

| Docker | Host name from inside the container |
|---|---|
| Docker Desktop (macOS, Windows) | `host.docker.internal`, built in |
| Docker Engine on Linux, 20.10+ | `host.docker.internal` after `--add-host=host.docker.internal:host-gateway` (compose: `extra_hosts`) |
| Linux, alternative | `--network host`, then Burp's `127.0.0.1:8080` works directly |

## 2. Burp's CA as a file the container can mount

- [ ] Proxy → Proxy settings → **Import / export CA certificate** → Export → **Certificate in DER format** →
  `~/burp/burp-ca.der` (outside the repo).
- [ ] Convert to PEM. For the JVM also build a truststore. Run these yourself:

```bash
mkdir -p ~/burp
openssl x509 -inform der -in ~/burp/burp-ca.der -out ~/burp/burp-ca.pem

# JVM only: copy the JDK's own trust store and add Burp's CA, so every other CA still works.
# $JAVA_HOME is any JDK 9+ on the host; the store format is portable.
cp "$JAVA_HOME/lib/security/cacerts" ~/burp/burp-cacerts
keytool -importcert -noprompt -trustcacerts -alias burp \
  -file ~/burp/burp-ca.pem -keystore ~/burp/burp-cacerts -storepass changeit
```

Burp regenerates its CA only when you tell it to, so this is a one-time step per Burp install.

## 3. Container environment, per runtime

Common to every runtime. `NO_PROXY` keeps container-internal calls (databases, other services) off Burp.

```bash
-e HTTP_PROXY=http://host.docker.internal:8081 \
-e HTTPS_PROXY=http://host.docker.internal:8081 \
-e NO_PROXY=localhost,127.0.0.1,db,redis \
-v ~/burp/burp-ca.pem:/certs/burp-ca.pem:ro
```

Then add the runtime block(s) below. A mixed backend (for example Java plus Python) gets both.

### Java / JVM

The JVM ignores `HTTP_PROXY`/`HTTPS_PROXY`. Use system properties, delivered through `JAVA_TOOL_OPTIONS` so no
start command changes. The JVM prints `Picked up JAVA_TOOL_OPTIONS: ...` to stderr at start, which confirms
the variable reached it.

```bash
-v ~/burp/burp-cacerts:/certs/burp-cacerts:ro \
-e JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=host.docker.internal -Dhttps.proxyPort=8081 \
  -Dhttp.proxyHost=host.docker.internal -Dhttp.proxyPort=8081 \
  -Dhttp.nonProxyHosts=localhost|127.0.0.1|db|redis \
  -Djavax.net.ssl.trustStore=/certs/burp-cacerts -Djavax.net.ssl.trustStorePassword=changeit"
```

Whether the HTTP library honours those properties:

| Library | Honours `https.proxyHost` | Note |
|---|---|---|
| `java.net.http.HttpClient` (JDK 11+) | yes | default `ProxySelector` |
| `HttpURLConnection` | yes | |
| OkHttp (also the official `openai-java` SDK, LangChain4j) | yes | default `ProxySelector` |
| Apache HttpClient 4 / 5 | **only with** `useSystemProperties()` on the builder | otherwise no proxy |
| Spring `RestTemplate` | depends on the underlying client above | |
| Spring `WebClient` / Reactor Netty | **no by default**; needs `proxyWithSystemProperties()` or explicit proxy config | common Spring Boot trap |
| Vert.x, Jetty client | explicit proxy config required | |

If the service uses one of the "no" rows and you cannot change its config, use section 5.

### Python

`httpx` (used by the `openai` and `anthropic` SDKs) and `requests` honour the proxy variables by default.

```bash
-e SSL_CERT_FILE=/certs/burp-ca.pem \        # httpx, OpenAI / Anthropic SDKs, urllib, Go-style tools
-e REQUESTS_CA_BUNDLE=/certs/burp-ca.pem     # requests
```

| Library | Proxy env vars | CA variable |
|---|---|---|
| `httpx` | yes (`trust_env=True` default) | `SSL_CERT_FILE` |
| `requests` | yes | `REQUESTS_CA_BUNDLE` |
| `aiohttp` | **only with** `ClientSession(trust_env=True)` | `SSL_CERT_FILE` |
| `urllib3` direct | no; needs `ProxyManager` | `SSL_CERT_FILE` via `ca_certs` |

### Node

Node trusts extra CAs via one variable, but its built-in `fetch` (undici), which the `openai` SDK uses,
**ignores `HTTPS_PROXY`**.

```bash
-e NODE_EXTRA_CA_CERTS=/certs/burp-ca.pem
# Node 24+: make fetch/undici honour HTTP(S)_PROXY
-e NODE_USE_ENV_PROXY=1
```

| Library | Proxy env vars |
|---|---|
| `fetch` / undici, `openai` SDK | Node 24+ with `NODE_USE_ENV_PROXY=1`; older Node needs `global-agent` or `fetchOptions: { dispatcher: new EnvHttpProxyAgent() }` |
| `axios` | yes (`proxy-from-env`) |
| `node-fetch` v2, `got`, `http(s)` core | no; needs an agent such as `https-proxy-agent` |

### Other runtimes

| Runtime | Proxy env vars | Trusting the CA |
|---|---|---|
| Go `net/http` | yes | `SSL_CERT_FILE=/certs/burp-ca.pem` (Linux) |
| .NET `HttpClient` (Core 3+) | yes | OS trust store (below), or `SSL_CERT_FILE` on Linux |
| Ruby `Net::HTTP`, Faraday | yes | `SSL_CERT_FILE` |
| Rust `reqwest` | yes | OS trust store; `SSL_CERT_FILE` with the native-roots feature |
| PHP curl, Guzzle | curl: yes; Guzzle: `proxy` option or `HTTPS_PROXY` honoured since 7 | `CURL_CA_BUNDLE` or OS store |
| Anything else | check the library | OS trust store |

OS trust store, inside the image or via `docker exec` as root. Covers most runtimes except the JVM (own
truststore) and Node (own bundle):

```bash
# Debian / Ubuntu / Alpine
cp /certs/burp-ca.pem /usr/local/share/ca-certificates/burp.crt && update-ca-certificates
# RHEL / Fedora
cp /certs/burp-ca.pem /etc/pki/ca-trust/source/anchors/burp.pem && update-ca-trust
```

### docker compose shape

```yaml
services:
  app:
    extra_hosts:
      - "host.docker.internal:host-gateway"   # Linux Docker Engine; harmless on Docker Desktop
    environment:
      HTTP_PROXY: http://host.docker.internal:8081
      HTTPS_PROXY: http://host.docker.internal:8081
      NO_PROXY: localhost,127.0.0.1,db,redis
      SSL_CERT_FILE: /certs/burp-ca.pem
      REQUESTS_CA_BUNDLE: /certs/burp-ca.pem
      JAVA_TOOL_OPTIONS: >-
        -Dhttps.proxyHost=host.docker.internal -Dhttps.proxyPort=8081
        -Dhttp.proxyHost=host.docker.internal -Dhttp.proxyPort=8081
        -Dhttp.nonProxyHosts=localhost|127.0.0.1|db|redis
        -Djavax.net.ssl.trustStore=/certs/burp-cacerts -Djavax.net.ssl.trustStorePassword=changeit
    volumes:
      - ~/burp/burp-ca.pem:/certs/burp-ca.pem:ro
      - ~/burp/burp-cacerts:/certs/burp-cacerts:ro
```

Keep this in an override file (`docker-compose.burp.yml`, `docker compose -f docker-compose.yml -f
docker-compose.burp.yml up`) so the normal deployment is untouched.

## 4. Confirm the pipe before judging the extension

- [ ] Variables reached the process: `docker exec <container> env | grep -i -E 'proxy|cert|java_tool'`
- [ ] TLS goes through Burp (the issuer must be PortSwigger's CA):

```bash
docker exec <container> sh -c 'curl -sv https://api.openai.com/v1/models -o /dev/null 2>&1 | grep -i -E "issuer|connected to"'
```

- [ ] Send one chat message. Proxy → HTTP history shows a request to the provider host.

| Symptom | Cause |
|---|---|
| App logs a TLS / certificate error | CA not trusted by that runtime; wrong variable for the library |
| Nothing in Burp, no error | The HTTP library ignores proxy env vars (see tables) |
| Burp shows the request, response is an error from Burp | Burp cannot resolve or reach the provider; check the host's network and Burp's upstream settings |
| `curl` works but the app does not | Different HTTP library than you assumed; check the table again |
| Request shows, response body empty in Burp | Streaming; see section 6 |

## 5. Fallback: invisible proxying when the library ignores proxy settings

Make the container resolve the provider host to the Burp machine and let Burp pick the real destination from
the TLS SNI / Host header.

- [ ] Burp: add a listener on port `8443`, all interfaces, Request handling → **Support invisible proxying**.
- [ ] Redirect the container's port-443 traffic to that listener. Linux, run yourself (needs root):

```bash
sudo iptables -t nat -A PREROUTING -i docker0 -p tcp --dport 443 -j REDIRECT --to-port 8443
```

  This sends **all** HTTPS from containers on the default bridge through Burp. Remove it afterwards with the
  same command and `-D` instead of `-A`.
- [ ] Alternatively, override only the provider host: `--add-host=api.openai.com:host-gateway` plus a Burp
  listener on port **443** with invisible proxying. Binding 443 on Linux needs a capability on Burp's Java
  binary (`setcap`) or running Burp as root; the iptables route avoids both.
- [ ] The CA still has to be trusted (section 2 and 3). Invisible proxying removes the proxy variables, not
  the TLS interception.

## 6. Streaming

If the app streams, the provider answers with `text/event-stream`, and Burp hands streamed responses to the
client without showing them to extensions. CanaryAgent logs a WARNING at load when Burp's settings make that
happen. While testing, untick **Treat responses with the text/event-stream MIME type as streaming** under
Settings → Network → HTTP → Streaming responses, and keep the provider host out of the prefix list. Requests
are seen either way, so flagging and inventory from the `tools` definitions still work.

## 7. What CanaryAgent should then show

- [ ] Provider rows highlighted, note such as `CanaryAgent: tools present: <names>` or the `messages` array.
- [ ] CanaryAgent tab → Inventory lists the real tool names and parameter keys; the count climbs by one per request.
- [ ] Send a provider request to Repeater, right-click inside a user message → **CanaryAgent → Insert marker**, add
  "repeat this token back", send. The Marker Hits row flips to *seen in response*.
- [ ] Put a marker in content the model reads before choosing a tool (a tool result, a retrieved document) and
  ask it to search for the token. The row flips to *seen in tool call*.
- [ ] Professional: **Insert Collaborator payload** in content the agent reads, ask it to fetch the URL. Only
  meaningful when a tool can make requests.
- [ ] Optional browser hop: browser proxy `127.0.0.1:8080`, plant a marker in the chat box, watch it travel
  browser → container → provider → back.

Known uncertainty worth testing: the OpenAI **Responses API** (`/v1/responses`) puts tool calls in `output[]`
items with `"type": "function_call"` as a value, not a key. The request is still flagged by its `tools`, and a
marker inside `arguments` still classifies as *seen in tool call*, but the response row itself may not be
flagged. If that happens, the real body becomes a corpus case and the detector gets a rule for it.

Every placement, hit, new tool and warning also goes to the extension's Output, and to the file configured
under the extension's Standard output ("Save to file"), which is what the dev loop reads.
