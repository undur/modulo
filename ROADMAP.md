# Modulo Roadmap

This document captures where modulo is heading. It is intentionally
higher-level than the issue tracker — issues hold the detailed design
discussions; the roadmap is for orientation. Read this when you want to
know *what's coming and why*; read the issues when you want to know
*how*.

The roadmap is a living document. When an iteration completes, the
entries move from "Coming up" to "Done". When new direction emerges,
the document gets edited rather than appended to.

---

## Where we are today

Modulo started as a thin reverse-proxy replacement for Apache's
`mod_WebObjects`. It has now grown into a front-facing TLS-terminating
HTTPS server for WO/ng-objects deployments — replacing Apache + certbot
in production for a real fleet of sites.

Today modulo does:

- HTTPS termination on port 443 (TCP, with HTTP/1.1 + HTTP/2 via ALPN)
- HTTP/3 wired and ready (disabled in production pending upstream Jetty
  fix for per-SNI cert selection — see "Deliberate non-goals" below)
- SNI-keyed in-memory keystore with hot reload on cert-file change
- HTTP→HTTPS redirect, canonical-hostname redirect
- HTTP-01 challenge passthrough (so certbot keeps renewing certs)
- gzip compression of proxied responses
- Reverse-proxy hop to WO/ng-objects upstream apps (HTTP/1.1)
- HTTP/2 cookie-header coalescing for upstream compatibility
- Preserves the original Host authority on proxied requests

The legacy plain-HTTP connector on port 1400 continues to run alongside
the front-end as a safety net for deployments still transitioning.

Site configuration is sourced from existing Apache vhost files via
`ApacheConfigReader` — interim scaffolding. Domain → app routing is a
hardcoded `Map<String, String>` in `Modulo.java`. Both are slated for
replacement.

---

## Coming up

### Iteration 2 — Extract `modulo-frontend` as a separate module

The TLS termination, SNI cert handling, redirects, compression, and
ACME-challenge passthrough are not specific to multi-app proxying —
they're "front-facing HTTP server" concerns. Pull them out of
`modulo-core` into their own Maven module *now*, even though the
second consumer doesn't yet exist.

Doing this early gives us:

- A real package/dependency boundary so accidental coupling between the
  front-end and the proxy gets caught at build time rather than later.
- The right mental separation for iteration 3's config work — "is this
  a Site-config concern (front-end) or a routing concern (proxy)?"
  becomes a real question with a forced answer.
- A clear API surface to react against when `wo-adaptor-jetty` and
  other adaptors come to consume the library.

Out of scope for this iteration: a standalone "embedded TLS for single
WO/ng apps" story. That's later. This iteration is purely a *physical*
separation; the only consumer is still modulo.

(Issues: undur/modulo#2, undur/wo-adaptor-jetty#2)

### Iteration 3 — Native config replaces Apache import

Modulo's Site model becomes the operator-facing source of truth.
The Apache config reader and hardcoded domain map both go away. After
this iteration, modulo no longer reads any Apache config.

The work in this iteration:

- **Native config format and storage.** Design the on-disk shape of
  modulo's own config. File per site, single file, or directory of
  files — to be decided based on the schema that emerges. (Issues: TBD)
- **Site model becomes routing source.** App routing comes from the
  Site, not from a hardcoded map. The {primary hostname, aliases, cert
  paths, app name, policy flags} all live on the Site object as
  populated by the new config source. (Issues: TBD, parent #2)
- **Per-site rewrite rules.** Path-prefix rewrites with explicit choice
  of 301 redirect, 302 redirect, or internal passthrough. Replaces the
  Apache `RewriteRule` directives we've been ignoring. (Issue: #4)
- **Multiple hostnames per Site with canonical policy.** Already
  partially present from iteration 1; survives the config rewrite with
  the same defaults (canonical redirect on, HTTPS redirect on).

When this is done, the Apache config reader can be deleted and the
`domainToAppMap` in `Modulo.java` can be deleted.

### Iteration 4 — `modulo-frontend` opens up for second consumers

With the module already separated (iteration 2) and the config model
stable (iteration 3), this iteration is about making the front-end
genuinely consumable from outside modulo. Most likely consumer:
`wo-adaptor-jetty`, giving single WO apps a real front door without
needing modulo or Apache.

The shape of *how* an embedded consumer configures the front-end is
distinct from how modulo configures it — that's its own design
discussion, taken up when the work starts.

(Issues: undur/wo-adaptor-jetty#2)

### Iteration 5 — Native ACME, retire certbot dependency

Today modulo reads certbot-managed PEM files from disk and reloads on
mtime change. The next step is for modulo to issue and renew its own
certs via acme4j, removing certbot from the deployment.

Includes:

- ACME HTTP-01 (no DNS provider creds required)
- ACME DNS-01 for wildcards and where HTTP-01 is impractical
- Cert provider as a pluggable interface (ACME, manual upload,
  self-signed for dev) hung off the Site's TLS config
- Renewal scheduling, failure observability, expiry warnings

(Issues: TBD, parent #2)

### Iteration 6+ — Operability and polish

These items are filed and will be addressed in subsequent iterations as
they fit into related work, or when they become blockers:

- **Pipeline unification** — collapse `startPlain` and `startWithFrontend`
  into one pipeline with multiple connectors. (Issue: #6)
- **Nicer error pages** — replace Jetty's default 500/502/503 pages with
  configurable per-Site versions. (Issue: #5)
- **Native access logging** — per-site logs in CLF or structured JSON,
  rotation, log shipping integration.
- **Migrate WO apps to `X-Forwarded-Host`** so modulo can stop emulating
  Apache's `ProxyPreserveHost On`. (Issue: #7)
- **Multi-instance app routing.** Honor `woinst` cookie and
  `.woa/N/`-encoded instance numbers in URLs instead of always picking
  the first instance.
- **Metrics endpoint and basic observability.** Health/readiness probes,
  cert expiry surfaces, renewal failure alerts.

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
- **No web admin UI in modulo itself.** Operators configure via files.
  Long-term, JavaMonitor (which already owns app/instance lifecycle for
  WO apps) is the natural place for a unified config UI; modulo would
  expose an admin API that JavaMonitor drives.
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
