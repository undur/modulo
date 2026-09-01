# Modulo Roadmap

A worklist: what's next, roughly in the order it matters. Issues hold
detailed design discussions; [BRAINSTORMING.md](BRAINSTORMING.md)
holds ideas not yet committed to; [SETUP.md](SETUP.md) describes what
exists today. When something ships it moves to Done (one line); when
direction changes, the entry is edited, not appended to — git history
is the chronology.

---

## Up next

- **Upstream timeouts, adaptor-config-driven.** Honor the per-instance
  `sendTimeout`/`recvTimeout`/`cnctTimeout` attributes wotaskd already
  publishes (JavaMonitor's timeout settings), falling back to Jetty's
  defaults (currently 5s connect / 30s idle, not configurable).
  Long-running apps (file processing, uploads) need this; the config
  channel and the operator UI for it already exist.
- **Request filtering and rate limiting.** Deny rules for scanner
  paths (reusing the rewrite machinery), a per-client token bucket
  answering 429, and accept-time auto-tempban — design sketch in
  BRAINSTORMING.md, prompted by the 2026-08-31 scan (6.2k requests
  in 6 minutes from one IP against www.rebbi.is).
- **The tuning surface.** The config page inventories every knob the
  server runs with, most marked "configurable: not yet". Work down the
  list into the root config with scoped resolution: hardcoded default
  ← `[settings]` ← per-site ← per-app (once the `apps` table exists).
  Durations as human strings ("30s", "12h"). The inventory page then
  doubles as the progress bar.

## Sites config, remaining slots

- **Static upstream sites** — `upstream = "localhost:8080"` on a site
  instead of `app`: proxy a hostname to a fixed address, no wotaskd
  involved. TLS/ACME/redirects/logs/WebSockets apply unchanged; code
  needed is the config field, a bypass of adaptor-config resolution,
  and honest admin-UI display. Small and high-leverage: it's the
  static instance source of DEPLOYMENT-MODELS.md rule 1 in minimal
  form (the Docker DNS source later extends this seam), it's the
  first real exercise of "modulo never requires wotaskd", and it lets
  betterbuild's Jenkins run behind modulo instead of Caddy.
- **Wildcard hosts and certificates** (`"*.example.com"`). Routing is
  the easy half: wildcard entries matched after exact ones. Certs are
  the decision — wildcards require DNS-01 (needs a DNS-provider API),
  alternatives are a manually-managed wildcard cert or Caddy-style
  on-demand per-subdomain HTTP-01 issuance (no DNS API, gated to
  configured wildcard sites). Use case modeled, implementation
  deliberately deferred until actually needed.
- **Security response headers.** HSTS and an opt-in set of proxy-level
  security headers, per site. (Issue: #8)
- **Per-site error responses.** The global condition→responder layer
  exists; this is its config surface (custom page, redirect, per
  condition per site). (Issue: #5)
- **Redirect-only sites, first-class.** Expressible today as a
  one-rule rewrite site (in production use); a dedicated `redirectTo`
  field would be sugar plus clearer intent.
- **Read-only guest password.** A second password granting the status
  pages but not `/reload` or the ng dev routes — a shareable view-only
  credential.

## Operability

- **WebSocket hardening**: move upstream writes off the selector
  thread; an end-to-end smoke test in the build (guards the Jetty
  upgrade-mechanism coupling across version bumps); an open-tunnels
  gauge on the dashboard. Also: verify SSE (structurally believed
  fine, never proven).
- **Multi-instance remainders**: request-body buffering to widen
  failover replayability (mod_WO buffered 1MB), draining on shutdown,
  balancing strategies beyond round-robin (least-outstanding-requests
  from modulo's own observations beats WO's session-count
  `loadaverage`).
- **Statistics / profiling**: per-app/site request counters, status
  distributions, recent-window rates; long-term prefer plugging into
  JFR streams over inventing protocols.
- **Metrics endpoint**: health/readiness probes, cert-expiry and
  renewal-failure surfaces for automation (the admin pages cover the
  human case).
- **Pipeline unification** — collapse `startPlain`/`startWithFrontend`
  into one pipeline with multiple connectors. (Issue: #6)
- **Migrate WO apps to `X-Forwarded-Host`** so modulo can stop
  emulating Apache's `ProxyPreserveHost On`. (Issue: #7)
- **Release archives**: downloadable prebuilt bundles, collapsing
  setup-server.sh's clone-and-build section to download-and-unpack —
  and removing the hidden dependency on the author's Maven settings.
- **Deploy hardening**: stream `admin/deploy` uploads to disk instead
  of buffering in memory (today: 512m heaps and a mandatory
  `application/octet-stream` content type); graceful and rolling
  bounce variants; pruning of moved-aside `x<App>_…woa` backups.

## Larger arcs

- **HTTP/3 via a fleet certificate** — machinery implemented and
  field-tested (one ACME cert covering all managed hostnames feeds the
  QUIC connector; TCP keeps per-site certs; everything degrades to
  h2). Blocked on an upstream Jetty 12.1.12 bug (server fails writing
  its own h3 control stream, `QUICHE_ERR_STREAM_LIMIT`, reproduced
  with Jetty's own client). Re-test per Jetty upgrade: flip the
  `http3` flag, restart twice, probe. Stays opt-in even when fixed —
  the fleet order is all-or-nothing across hostnames.
- **Iteration 4 — modulo-frontend for second consumers.** Make the
  front-end consumable outside modulo; likeliest consumer is
  wo-adaptor-jetty, giving a single WO app a real front door. (Issue:
  undur/wo-adaptor-jetty#2)
- **Iteration 7 — single-service deployments.** For simple servers,
  collapse wotaskd + JavaMonitor + modulo into modulo alone: an
  `[[apps]]` table beside `[[sites]]` (launch, port assignment,
  health-check, restart) as an *additional* adaptor-config source —
  wotaskd remains the fleet-scale mode. SiteConfig.xml and JavaMonitor
  become things a newcomer never meets.

  ```toml
  [[sites]]
  hostnames = [ "www.example.com" ]
  app = "MyApp"

  [[apps]]
  name = "MyApp"
  path = "/opt/apps/MyApp.woa"
  instances = 1
  ```
- **WebServerResources serving** — opt-in per site:
  `woa = "<path to .woa or split-install dir>"` and
  modulo owns that site's `/WebObjects/<App>.woa/…` URL space, mapped
  onto exactly two whitelisted subtrees — `WebServerResources/` and
  `Frameworks/*/WebServerResources/` — never `Resources/` (the
  boundary the classic split install enforced physically, kept here by
  path rule). GET/HEAD only, normalized, symlinks re-checked,
  ETag/Last-Modified. The adoption feature for plain WO/Wonder apps
  (deployment WO has no resource request handler — only Wonder's
  ERXResourceRequestHandler apps can self-serve), and the last thing
  Apache was still needed for. Live motivating case: SW on linode-4
  has 404'd all its framework resources since the Apache cutover.
  Narrows the "no filesystem serving" non-goal by one word: no
  docroots, no listings — a bundle-scoped resource map.
- **Classic mod_WebObjects compatibility bundle** — instance numbers
  injected into adaptor URLs, the legacy header vocabulary — purely an
  adoption feature for unmodified classic apps; verify against a real
  classic deployment before building.

---

## Done

One line each; details in git history and SETUP.md.

- **Front-end mode** — TLS, SNI keystore w/ hot reload, redirects, compression *(iteration 1)*
- **modulo-frontend extracted** as its own module *(iteration 2)*
- **Native sites config** — fragments via `include`, strict parsing, zero-restart validated reload *(iteration 3, 2026-08-28/29)*
- **Native ACME** — HTTP-01 issue/renew, placeholder-then-hot-swap, whole fleet migrated, certbot retired *(iteration 5, 2026-08-28)*
- **Typed error conditions** + default error page + assignable responders *(2026-08-29)*
- **Admin UI** — dashboard w/ traffic charts and app/total response split, applications, overview, events (+ clear), config inventory, reload *(2026-08-29/30)*
- **Multi-instance routing** — pins, stickiness (proxy-owned woinst/ngsid affinity), round-robin, refusal steering, failover w/ dead cool-down, OOB config re-poll, unregistered-instance last-resort routing *(2026-08-29/30)*
- **Observability base** — per-site access logs w/ vhost field, rotation, event buffer, unmatched-host tally, request stats *(2026-08-29/30)*
- **Per-site rewrite rules** — regex, captures, redirects, first-match-wins, adaptor-space guard *(2026-08-30, issue #4)*
- **TOML config, one root file** — modulo.toml absorbs bootstrap + sites, per-setting reload notices, JSON removed *(2026-08-30, issue #10)*
- **WebSocket proxying** — raw tunnel after handshake, routed like HTTP, verified through wss:// *(iteration 6, 2026-08-30)*
- **Operational skeleton** — standard layout on both servers, setup-server.sh (standalone full-stack installer), unified deploy scripts *(2026-08-30)*
- **Performance validated** — 5× Apache+mod_WebObjects throughput at equal latency on a modern-platform A-B-A lab; report in performance-test-results-2026-08-30.md *(2026-08-30)*
- **setup-server.sh options** — JDK distribution/version as parameters (openjdk default, latest resolved live), stack password written into SiteConfig at install *(2026-08-31)*
- **Deploy through the stack** — `admin/deploy` on JavaMonitor fans a .tar.gz out to each host's wotaskd, which swaps the bundle and bounces instances; replaces the rsync post_build scripts *(2026-08-31, in wonder-slim-deployment)*

---

## Deliberate non-goals

- **No filesystem serving.** Static content is the apps' job; the
  blast radius of path-traversal/symlink/MIME concerns isn't worth
  owning without a real driver.
- **No h2 on the backend hop.** HTTP/1.1 with keep-alive to upstreams;
  multiplexing buys little on a single-upstream hop.
- **No config-editing web UI in modulo.** Files are the interface; the
  admin pages stay read-only status views (plus reload). If a config
  UI ever exists, JavaMonitor drives a modulo admin API.
- **HTTP/3 off by default** even once unblocked — the fleet-cert
  coupling is an operator's conscious opt-in.
