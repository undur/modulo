# Modulo Roadmap

This document captures where modulo is heading. It is intentionally
higher-level than the issue tracker — issues hold the detailed design
discussions; the roadmap is for orientation. Read this when you want to
know *what's coming and why*; read the issues when you want to know
*how*.

The roadmap is a living document. When an iteration completes, the
entries move from "Coming up" to "Done". When new direction emerges,
the document gets edited rather than appended to.

For ideas being considered but not yet committed to an iteration, see
[`BRAINSTORMING.md`](BRAINSTORMING.md). For how to set up and operate
what exists today, see [`SETUP.md`](SETUP.md).

---

## Where we are today

Modulo started as a thin reverse-proxy replacement for Apache's
`mod_WebObjects`. It is now a standalone front-facing HTTPS server for
WO/ng-objects deployments — Apache, mod_WebObjects and certbot fully
retired in production for a real fleet of sites.

Today modulo does:

- HTTPS termination on port 443 (TCP, with HTTP/1.1 + HTTP/2 via ALPN),
  SNI across all configured sites
- Automatic certificates: native ACME issuance and renewal
  (Let's Encrypt by default), self-signed placeholders so TLS starts
  before first issuance, hot-swapped certs without restarts
- Native JSON sites config — a main file plus optional per-app
  fragment files via `include` globs; strict validation; an importer
  that converts an Apache + mod_WebObjects vhost setup in one command
- Zero-restart config reload (`POST /reload`), validation-first — a
  bad config is rejected with the exact error and changes nothing
- Hostname → app routing from the sites config; reverse-proxy hop to
  WO/ng-objects upstream apps (HTTP/1.1) with the WO-specific headers
  and quirks handled (cookie coalescing, Host preservation)
- HTTP→HTTPS redirect, canonical-hostname redirect, gzip compression
- Typed error conditions (app down, no instances, unreachable,
  timeout, unknown host) with correct statuses, a clean default error
  page, and per-condition assignable responders
- A password-guarded admin UI in modulo-runner: start page, adaptor
  (apps/instances), configuration overview with cert expiry, reload
- HTTP/3 wired and ready (disabled in production pending upstream
  Jetty fix for per-SNI cert selection — see "Deliberate non-goals")

The plain-HTTP proxy connector (port 1400) still runs alongside the
front-end, serving as a safety net and as the whole story for
deployments that put modulo behind another web server.

## Done

Compact record; the details live in git history and SETUP.md.

- **Iteration 1 — Front-end mode** *(2026)*: TLS termination, SNI
  keystore with hot reload, redirects, compression, challenge
  passthrough; sites imported from Apache vhost files as scaffolding.
- **Iteration 2 — `modulo-frontend` extracted** as its own Maven
  module, giving the front-end/proxy boundary a build-time wall.
  (Issues: #2, undur/wo-adaptor-jetty#2)
- **Iteration 3 — Native config** *(2026-08-28/29)*: the JSON sites
  config (single file + `include` fragments) became the sole source of
  truth for sites, TLS and routing; strict parsing; zero-restart
  reload. The Apache *runtime* import path and the hardcoded domain
  map were deleted; the Apache reader lives on only as the one-shot
  migration importer (`modulo.config.ApacheConfigImporter`) — the
  right migration story for adopters coming from Apache +
  mod_WebObjects. Remaining schema slots are under "Config schema
  growth" below.
- **Iteration 5 — Native ACME** *(2026-08-28)*: HTTP-01
  issuance/renewal via acme4j; ACME is the config default (omit `tls`);
  PEMs on disk under modulo's storage dir; placeholder-then-hot-swap
  startup; renewal every 12h on missing/placeholder/expiring/
  SAN-coverage triggers. The whole production fleet migrated in one
  evening; certbot retired. Remaining: DNS-01 (below).
- **Error handling, global layer** *(2026-08-29)*: `ErrorCondition` +
  assignable responders + the default 🤖 error page. (Issue: #5, the
  per-Site half remains.)
- **Admin UI** *(2026-08-29)*: `/`, `/adaptor`, `/overview` (sites,
  ACME, cert expiry), `/reload` — all behind one dispatch-level
  password guard (which also closed ng's unauthenticated
  `/ng/dev/terminate`, reachable due to deployment-mode misdetection;
  the durable fix is undur/vermilingua-maven-plugin#52).

---

## Coming up

### Iteration 6 — WebSocket proxying

WebSocket through modulo **does not work today**, established by source
inspection (2026-08-29) rather than suspicion: Jetty's `ProxyHandler`
(which `ModuloProxy` extends) has no handling for `Upgrade`, 101
responses or post-upgrade tunneling — and it strips the `Connection`
header as hop-by-hop, so the handshake headers never even reach the
app. The app sees a plain GET, the browser expects a 101, the WS
connection fails.

This hasn't bitten because classic WO apps don't use WebSockets — but
ng-objects now supports them, so this must land before ng apps behind
modulo start relying on that.

Chosen approach: **raw tunnel after handshake**. Detect
`Upgrade: websocket` ahead of the proxy handler, forward the handshake
to the upstream on a dedicated connection, and on 101 switch both
sides to raw byte-pumping. Jetty's `ConnectHandler` does this dance
for CONNECT tunnels (`TunnelSupport` / `EndPoint` upgrade) — the
machinery to crib from. Transparent to WS extensions, no frame
parsing or re-encoding per message.

Considered and set aside: terminate-and-reinitiate (front-side WS
server + upstream WS client) — supported APIs and a path to
WS-over-HTTP/2 (RFC 8441) later, but heavier and re-encodes every
frame; waiting for upstream Jetty `ProxyHandler` support — nothing in
12.1.x, not worth blocking on.

Notes for the implementation:

- The h2 front-end doesn't complicate this: browsers use HTTP/1.1 for
  `wss://` unless the server advertises extended CONNECT (we don't),
  so the upgrade always arrives on the HTTP/1.1 path.
- Long-lived connections need idle-timeout attention on both the
  connector and the proxy-to-upstream hop.
- SSE appears structurally fine already (streamed response;
  `text/event-stream` isn't gzip'd so no buffering) — verify while in
  here.

### Config schema growth

The sites config works; these are the slots it still wants. They
should be designed together so the schema grows coherently:

- **Per-site rewrite rules.** Path-prefix rewrites with explicit choice
  of 301 redirect, 302 redirect, or internal passthrough. Replaces the
  Apache `RewriteRule` directives we've been ignoring. (Issue: #4)
- **Security response headers.** HSTS and (opt-in) a small set of other
  proxy-level security headers, per site. (Issue: #8)
- **Per-Site error responses.** The global `ErrorCondition` → responder
  layer exists; this is its config-schema surface (custom page,
  redirect, per condition per site). (Issue: #5)
- **Redirect-only sites.** A Site whose whole purpose is redirecting a
  hostname elsewhere (`redirect_to`), no app — rebrands, domain
  consolidation, typo-domains. Currently in brainstorming; decide the
  shape alongside the above.

### Iteration 6+ — Operability and polish

These items are filed and will be addressed in subsequent iterations as
they fit into related work, or when they become blockers:

- **Pipeline unification** — collapse `startPlain` and `startWithFrontend`
  into one pipeline with multiple connectors. (Issue: #6)
- **Native access logging** — per-site logs in CLF or structured JSON,
  rotation, log shipping integration.
- **Migrate WO apps to `X-Forwarded-Host`** so modulo can stop emulating
  Apache's `ProxyPreserveHost On`. (Issue: #7)
- **Multi-instance app routing.** Honor `woinst` cookie and
  `.woa/N/`-encoded instance numbers in URLs instead of always picking
  the first instance.
- **Metrics endpoint and basic observability.** Health/readiness probes,
  cert expiry surfaces, renewal failure alerts. (The overview page
  covers the human-eyes case; this is the automation case.)
- **ACME DNS-01** for wildcard certificates and where HTTP-01 is
  impractical — the remaining piece of iteration 5.
- **Ship the operational skeleton.** systemd unit templates, the
  `/opt/webobjects/{apps,conf,log}` layout, an install script — the
  knowledge a newcomer currently can't get without an existing
  installation to copy from. Surfaced by the first deployment done by
  someone other than the author. Interim step toward iteration 7.

### Iteration 4 — `modulo-frontend` opens up for second consumers

With the module separated and the config model stable, this iteration
is about making the front-end genuinely consumable from outside
modulo. Most likely consumer: `wo-adaptor-jetty`, giving single WO
apps a real front door without needing modulo or Apache.

The shape of *how* an embedded consumer configures the front-end is
distinct from how modulo configures it — that's its own design
discussion, taken up when the work starts.

(Issues: undur/wo-adaptor-jetty#2)

### Iteration 7 — Single-service deployments: modulo-managed app lifecycle

Setting up a simple single-server deployment today means installing
and operating three services — wotaskd, JavaMonitor, and modulo. For
that case, the trio should collapse to one.

The resolution (decided 2026-08-29): **don't host the apps — absorb
the functionality.** Literally embedding wotaskd/JavaMonitor in
modulo's JVM is blocked by WO's one-WOApplication-per-JVM design and
would couple the proxy's restart cycle to app supervision. But the
slice of their functionality a single server actually needs is small:
launch app processes, assign ports, health-check, restart on death,
feed the topology to the proxy. Modulo re-implements that natively,
configured from the sites config — an `apps` declaration alongside
`sites`:

```json
{ "sites": [ { "hostnames": [ "www.example.com" ], "app": "MyApp" } ],
  "apps":  [ { "name": "MyApp", "path": "/opt/apps/MyApp.woa",
               "instances": 1, "port": 2001, "autoRestart": true } ] }
```

One service, one config file; SiteConfig.xml and JavaMonitor become
things a newcomer never meets. In-process supervision also removes the
startup race (modulo knows when an instance is up instead of polling
wotaskd every 10 seconds). The wotaskd adaptor-config source remains
as the fleet-scale mode — this is an additional adaptor-config
source, not a replacement (the `Modulo.java` FIXME about holding
multiple adaptor configuration sources is exactly this).

Design work to take up when this starts: process supervision model
(restart backoff, graceful stop), port allocation, instance health
checks, log capture/rotation for supervised apps, and how `woinst`
multi-instance routing interacts with it.

---

## Deliberate non-goals

What modulo does *not* do, and why. These exist as much as the
features do — knowing what's out of scope is what keeps the project
small and focused.

- **No filesystem serving.** Modulo does not serve files from disk.
  Static content is served by the WO/ng-objects apps themselves via
  their existing `/res/...` URL handling. `DocumentRoot`-style serving
  has been considered explicitly and deferred until a concrete need
  arises. The blast radius of filesystem-serving security concerns
  (path traversal, symlink escape, hidden-file leakage, MIME confusion)
  is large enough that we'd rather not own them without a real driver.
- **No HTTP/2 (h2c) on the backend hop.** Modulo talks to upstream apps
  over HTTP/1.1. HTTP/2 multiplexing brings little benefit for a
  single-upstream proxy hop with keep-alive already enabled, and
  introduces operational complexity for marginal gain.
- **No config-editing web UI in modulo itself.** Operators configure
  via files; the admin pages in modulo-runner are read-only status
  views (plus the reload trigger), not a config editor. Long-term,
  JavaMonitor (which already owns app/instance lifecycle for WO apps)
  is the natural place for a unified config UI; modulo would expose an
  admin API that JavaMonitor drives.
- **HTTP/3 disabled in production.** The wiring is complete and tested
  end-to-end against a single-cert deployment, but Jetty's
  `QuicheServerConnector` selects one cert from the keystore at startup
  and presents it for all SNI handshakes. Multi-site deployments would
  serve the wrong cert. Re-enable once Jetty supports per-SNI cert
  selection in its QUIC path.

---

## How decisions land here

When something gets decided in a discussion or an issue thread, edit
this document. Don't write "we used to think X but now…" — just update
the relevant paragraph. The git history is the chronology; the document
itself is the current state.

If a "deliberate non-goal" later becomes a goal, move it to the
roadmap and explain why the reasoning changed in the commit message.
