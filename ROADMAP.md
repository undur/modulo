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

- **Iteration 6 — WebSocket proxying** *(2026-08-30)*: raw tunnel
  after handshake — upgrades intercepted ahead of the proxy, routed
  with the proxy's own logic, handshake forwarded upstream, byte-pump
  both ways on 101 (Jetty core's UPGRADE_CONNECTION_ATTRIBUTE
  mechanism client-side, virtual-thread pump upstream-side). Verified
  end-to-end on hz1 against AjaxPlayground's /ws/echo (wo-adaptor-jetty)
  through wss://. SSE still unverified — carried as a note under
  operability.
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

### Config schema growth

The sites config works; these are the slots it still wants. They
should be designed together so the schema grows coherently:

- **The tuning surface** (decided 2026-08-29). The start page's
  configuration inventory enumerates every knob the server runs with —
  worker threads, upstream timeouts/pools/buffers, compression, cert
  poll and ACME timing, log retention, event buffer — most marked
  "configurable: not yet". Work down that list, introducing each into
  config with **scoped resolution**: hardcoded default ← global
  `settings` block (sites config) ← per-site override ← per-app
  override (once iteration 7's `apps` block exists). Not every knob
  gets every scope — worker threads are inherently global, compression
  is naturally per-site, upstream timeouts naturally per-app; the
  inventory's grouping is a first cut at which is which. Durations as
  human strings ("30s", "12h"). `modulo.conf` stays bootstrap-only
  (ports, file locations, admin password). The inventory page then
  becomes a progress bar: each knob's Source cell flips from
  "hardcoded" to its config location as it lands.

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
- **Read-only guest password.** A second password
  (`modulo.guest-password`) granting the status pages (dashboard,
  applications, overview, events, config) but not `/reload` or the ng
  dev routes. Motivated by sharing the admin UI with the WO community:
  today the single admin password unlocks mutating endpoints —
  including `/ng/dev/terminate` — along with the read-only views. A
  shareable view-only credential is generally useful beyond that
  occasion.
- **TOML as the operator-facing format** — analyzed and viable
  (dual-format via extension dispatch, same DTOs and validation, JSON
  stays as the admin-API wire format); deliberately parked until we
  have more operational experience with the current environment.
  (Issue: #10)

### HTTP/3 via a fleet certificate

Jetty's QUIC connector can present exactly one certificate (it exports
a single keystore alias to PEM for quiche — `findFirst()`, literally),
so multi-site h3 has been parked. Upstream won't unblock this soon:
quiche supports SNI cert selection since 2026-02 (quiche#2368) but
only via its Rust API — the C API Jetty binds exposes nothing — and
Jetty's QUIC-layer rewrite signals a future alternative (likely
pure-Java) QUIC implementation rather than deeper quiche investment.

Decided 2026-08-29 — sidestep it with what native ACME gives us: one
additional **fleet certificate** covering the hostnames of all
ACME-managed sites (well under Let's Encrypt's 100-SAN limit), fed to
the QUIC connector via its own SslContextFactory, while TCP/443 keeps
per-site certs. Cost at steady state is one extra ACME order per
renewal cycle; a hostname-set change triggers a fleet reissue (cheap —
LE caches recent authorizations).

Policy: **h3 stays disabled by default.** The fleet order is
all-or-nothing — one dead domain stalls it — so enabling h3 is an
operator's conscious opt-in. When enabled, modulo performs the fleet
dance and prefers h3 via the Alt-Svc advertisement, sent only for
sites the fleet cert covers. Every failure mode (stale fleet cert,
uncovered hostname, UDP blocked) degrades to TCP h2 — worst case is
"no h3", never "site down". Renewal failures log loudly; teaching the
fleet order to drop unvalidatable hostnames is a refinement for later.

*Implemented and field-tested 2026-08-29* — the modulo side works
end-to-end: fleet cert issued for 42 SANs in one order, QUIC handshake
from the internet completes with ALPN h3 serving the fleet cert. But
Jetty 12.1.12's h3 layer then fails writing its own control stream
(`QUICHE_ERR_STREAM_LIMIT` on stream 3), reproduced with both aioquic
and Jetty's own 12.1.12 h3 client — an apparently unreported upstream
bug ("HTTP/3+QUIC support is experimental", says Jetty's own startup
log). So h3 remains off in production; the fleet machinery sits ready
behind the `http3` flag, and re-testing on each Jetty upgrade is
cheap: flip the flag, restart twice (quiche exports its PEM at
connector start, so the first enablement wants a restart after the
initial fleet issuance), probe, flip back if still broken.

### Iteration 6+ — Operability and polish

These items are filed and will be addressed in subsequent iterations as
they fit into related work, or when they become blockers:

- **Pipeline unification** — collapse `startPlain` and `startWithFrontend`
  into one pipeline with multiple connectors. (Issue: #6)
- **Statistics / basic profiling pages.** Global and per-app/site views
  for health checks and spike detection — the display-side sibling of
  the event log (logging and event tracking being flip sides of the
  same coin). Not access-log analytics; think request counters, status
  distributions, recent-window rates. Long-term, prefer plugging into
  standard continuous-profiling streams (JFR) over inventing our own
  protocols. Event scoping already anticipates this: events carry
  optional site/app coordinates (instance scope reserved for the
  multi-instance work).
- **Migrate WO apps to `X-Forwarded-Host`** so modulo can stop emulating
  Apache's `ProxyPreserveHost On`. (Issue: #7)
- **Multi-instance app routing** — *core landed 2026-08-29, verified
  live against a six-instance app*: `.woa/N/` URL pins, `woinst`
  cookie stickiness, round-robin for unpinned requests,
  fall-back-with-event when a pinned instance is gone, and — since
  modulo owns the woinst cookie (apps only ever echo it) — browsers
  heal on the first response after a failover. Refusing-new-sessions
  is handled too (2026-08-29): both announcement headers observed,
  new traffic steered away while pinned sessions drain, sessionless
  pins rerouted past the WO rebalance-redirect, state cleared on the
  first announcement-free response. Wonder's `route_id` cookie is
  deliberately unneeded (it exists for balancers that can't read WO's
  native pinning; modulo can). ng-objects affinity (decided
  2026-08-29): ng apps are *deliberately instance-ignorant* — the
  proxy attaches/reads the affinity cookie on `ngsid` sessions
  entirely on its own, so ng-appserver carries no instance protocol
  at all. Optional future enrichment if ng apps ever want to know
  their instance (logging, diagnostics): an `x-modulo-instance`
  request header. For graceful-bounce parity, ng could simply adopt
  the existing refusal response headers modulo already understands. Failover landed 2026-08-29 (from
  the mod_WebObjects source review): dead cool-down (30s) on connect
  failure with instant proof-of-life recovery, body-less requests
  failed over across not-yet-attempted instances until exhaustion,
  out-of-band config re-poll on unknown apps (deployment turnaround =
  first request), and the config-declared `refuseNewSessions=YES`
  attribute honored. Remaining: request-body buffering to widen
  replayability (mod_WO buffered 1MB), draining on shutdown,
  strategies beyond round-robin. On load
  signals: WO's `x-webobjects-loadaverage` header is just the active
  session count — a poor measure of real load this century (idle
  sessions weigh nothing, sessionless/API traffic weighs plenty).
  Modulo can do better without asking the app anything: it directly
  observes per-instance in-flight request counts and response
  latencies, so least-outstanding-requests (optionally
  latency-weighted) is the natural strategy; the WO header at most a
  tiebreaker for legacy sympathy.
- **Metrics endpoint and basic observability.** Health/readiness probes,
  cert expiry surfaces, renewal failure alerts. (The overview page
  covers the human-eyes case; this is the automation case.)
- **ACME DNS-01** for wildcard certificates and where HTTP-01 is
  impractical — the remaining piece of iteration 5.
- **Classic mod_WebObjects compatibility bundle.** For adopters running
  unmodified classic WO apps: inject the chosen instance number into
  forwarded adaptor URLs (`/App.woa/<N>/...` — cookieless
  URL-session apps derive their generated-URL pinning from it), and
  emit the legacy header vocabulary where classic code expects it
  (`x-webobjects-remote-addr`/`-server-name`/`-server-port` family,
  `HTTPS`-style scheme markers — Jetty already sends the modern
  RFC 7239 `Forwarded` header, which classic WO predates). Modulo's
  own stack doesn't need any of this (cookie-based stickiness is
  proxy-owned); it's purely an adoption feature — verify against a
  real classic deployment before building.
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
- **HTTP/3 disabled by default.** The wiring is complete and tested,
  but Jetty's QUIC path presents a single certificate for all SNI
  handshakes. The plan to enable it anyway — an ACME "fleet
  certificate" covering all managed hostnames, opt-in per deployment —
  is on the roadmap above; default-off remains deliberate because the
  fleet order adds an all-or-nothing coupling an operator should
  consciously accept.

---

## How decisions land here

When something gets decided in a discussion or an issue thread, edit
this document. Don't write "we used to think X but now…" — just update
the relevant paragraph. The git history is the chronology; the document
itself is the current state.

If a "deliberate non-goal" later becomes a goal, move it to the
roadmap and explain why the reasoning changed in the commit message.
