# Modulo Brainstorming

This document captures ideas worth thinking about but not yet committed to.
Some will graduate to `ROADMAP.md` as iterations; some will stay here
indefinitely as "good to know"; some will eventually become non-goals.

The distinction from `ROADMAP.md`: the roadmap is what we *intend to do*,
brainstorming is what we're *thinking about*.

When an item moves from brainstorming to roadmap, delete it here — git
history captures the migration. When an item is decided against, move
it to the "Deliberate non-goals" section of the roadmap.

---

## Survey of the field

Modulo enters a space with mature competitors. None of them are aimed
specifically at WO/ng-objects deployments, which is the niche modulo
fills, but many of them have spent decades thinking about reverse-proxy
concerns we'll eventually want answers for. Worth knowing what they do
and why.

### The titans

- **nginx** — the default "I need a reverse proxy" answer for ~15 years.
  Excellent performance, mature module ecosystem. Config language is its
  own beast. Reload semantics have weird edge cases.
- **Apache httpd** — the elder. Excellent module ecosystem, painful to
  operate well at scale.
- **HAProxy** — best-in-class TCP+HTTP load balancing, exceptional
  stability and observability (the stats page is iconic). Not really an
  "HTTP server"; you put it in front of other servers.
- **Envoy** — modern cloud-native, foundation of service meshes (Istio,
  Linkerd). xDS dynamic config, observability deeply baked in,
  programmable filter chain. Scope creep — needs infrastructure to manage.

### The modern entrants

- **Caddy** — Go, philosophically closest to modulo. Automatic HTTPS by
  default is the killer feature. Pleasant config language. Worth reading
  closely.
- **Traefik** — Go, container-native. Discovers config from Docker /
  Kubernetes / Consul labels.
- **Pingora** — Cloudflare's edge proxy, Rust. Library not server,
  ridiculous performance.

### The single-purpose tools worth knowing

- **acme.sh / lego / certbot** — pure ACME clients.
- **certmagic** — Caddy's cert management as a separate Go library.
  Tracks renewal, OCSP, storage.

---

## Ideas worth thinking about

These came out of the field survey. Some are obvious extensions of
modulo's current direction; some are more speculative. None are
committed.

### Automatic HTTPS as a first-class concept

Caddy's model: operator declares hostnames, Caddy gets the cert via
ACME, renews automatically. Operator never touches PEM paths in
config.

For modulo, this means **the Site model probably shouldn't have
`certPath` / `keyPath` as first-class fields**. It should have
`hostnames` and `tls: { mode: acme }` or `tls: { mode: manual, cert:
/path/to/pem }`. The default — and dominant case — should be ACME;
manual paths are the fallback.

This is iteration 5 in the roadmap (native ACME) but it informs
iteration 3's config schema design. Worth designing the Site model
now so "no cert configured, ACME automatic" is the natural shape.

### Dynamic configuration without process restart

Modulo currently re-reads config at startup; adding a Site means a
restart. Fine for ~17 sites; not fine for "operator adds 5 sites per
day" or multi-tenant scenarios.

Two flavors:
- **File-watch + reload** (nginx style): when config files change,
  reload. Easy to implement (we already poll cert mtimes). Fits
  operator mental model.
- **API-driven** (Caddy, Envoy style): config mutated via HTTP API;
  on-disk file is just a snapshot. Better for automation. This is
  the natural shape for the eventual JavaMonitor-as-admin-UI vision.

### Request/response observability — stats page or metrics endpoint

Probably the **single highest-leverage thing modulo doesn't currently
have**. Every serious proxy has a way to answer "what's happening
right now?" without `tail -f`'ing a log file.

What to expose:
- Connection counts (per Site, per upstream)
- Request rate, latency p50/p95/p99
- Upstream health (which apps are responsive?)
- Cert expiry countdown
- Active vs. closed connections, queue depths
- Recent errors (502s, cert load failures, etc.)

HAProxy's stats page is the gold standard for visual operators. A
Prometheus `/metrics` endpoint is the gold standard for automated
monitoring. Modulo could ship both with relatively little code; Jetty
exposes most of the metrics internally already.

This is what distinguishes "tool I trust in production" from "tool that
works for now."

### Active upstream health checks

When a WO app stops responding (OOM, deadlock, restart), modulo
currently forwards to it and returns 502. Modern proxies actively check
upstream health (HTTP `/health` endpoint or TCP-level) and remove dead
upstreams from rotation until they recover.

Even with one instance per app, this enables "app is restarting,
return a maintenance page instead of 502" — which is what humans
expect to see. Naturally pairs with the per-Site error pages work
(#5).

### Connection-level rate limiting and limits

A single client opening 10K connections to modulo is currently
undefended. HAProxy has been doing connection caps and rate limiting
for ~20 years; patterns are well understood.

Becomes important the day someone (intentionally or accidentally) hits
modulo with high concurrency. Cheap to add to Jetty; easy to forget
until it bites.

### Unix domain sockets for modulo → app

When modulo and the WO apps share a host, the proxy → app hop could
go over a Unix domain socket instead of loopback TCP. Typical wins on
small-payload requests: 10–30% latency, 10–40% throughput. Also gives
a filesystem-permission trust boundary instead of "trust that nothing
else binds the port." Cost: wo-adaptor-jetty needs to bind UDS,
wotaskd's model has to learn about socket paths, and the easy
debugging escape hatch ("curl the app directly on its port") goes
away. Not urgent — our per-request hop is already in the microseconds
on loopback and isn't the bottleneck — but worth picking up the day
performance or local-trust become design pressures.

### WebSocket / streaming-aware proxying

Long-lived connections (WebSocket, SSE, gRPC) need different handling
than request/response: idle timeouts, half-close semantics, careful
header forwarding. WO/ng now have WebSocket support; modulo's proxy
path should be verified end-to-end.

Lower-effort than the other items — mostly a "test, then fix what
breaks" exercise, not a design effort.

### Multiple upstream instances per site — real load balancing

The roadmap mentions "multi-instance app routing" as a polish item,
but it's actually a substantial subsystem when treated seriously:

- Load-balancing strategies (round-robin, least-connections,
  hash-by-cookie, hash-by-URL).
- Sticky sessions — `woinst` cookie handling is the WO-specific case;
  more general "consistent hashing of session cookie" is the generic
  case.
- Graceful failover when an upstream is unreachable.
- Connection draining when an upstream is being taken out of rotation.

When you grow from one to two instances of any app, all of this
becomes load-bearing. Worth not under-scoping.

### Whole-site redirects as a first-class Site shape

URL routing within a site is the app's job. But "this entire hostname
just redirects to that other one" is genuinely a deployment-level
concern with no app on the receiving end of the old hostname. Comes
up for rebrands, domain consolidation, typo-domains pointed at the
canonical site, and temporary redirects during cutovers.

The cleanest config shape is probably a Site whose primary intent is
to redirect — distinct from a Site that routes to an app:

```
site:
  hostnames: [strimillinn.is, www.strimillinn.is]
  cert: { ... }
  redirect_to: https://www.neytandinn.is
  status: 301
```

No app, no routing rules. Legible at a glance.

Alternative shapes — generalising `target` to {app, redirect, …}, or
expressing it as a catch-all redirect rule on a regular Site — both
work but tax every reader of the config for a feature most Sites
don't use. The dedicated `redirect_to` field is honest about the
intent.

Worth deciding when iteration 3's schema is designed.

### Authentication / ACL at the proxy layer

"This site requires auth — check OAuth / mTLS / a header before
forwarding." nginx (`auth_request`), Traefik (middleware), and Envoy
all have rich versions. For WO apps it's mostly the app's concern, but
admin endpoints, mTLS-protected internal sites, etc. live at the proxy
layer.

Probably out of modulo's scope. Worth knowing the pattern.

### A Caddy-style admin API model

The most copy-worthy idea in the field. Caddy exposes a JSON config
over HTTP, you `POST` to update it, internal state reloads atomically.
The on-disk file is a projection of the API state, not the source of
truth.

If JavaMonitor is going to drive modulo's config eventually, **modulo's
admin API should probably look like Caddy's** — a single endpoint where
you POST a complete config, atomic apply, errors-or-success. Cleaner
than dozens of specific REST endpoints.

This deserves its own design discussion before iteration 3 lands its
config format, since the choice here shapes the format.

---

## Reading list

If we want to keep learning from the field, in priority order:

1. **Caddy's docs (caddyserver.com)** — concepts, automatic HTTPS,
   JSON config structure, the Caddyfile adapter pattern (human-friendly
   text format desugaring to canonical JSON).
2. **HAProxy's configuration guide** — health checks, stick tables,
   stats. Decades of operational experience condensed.
3. **Envoy's listener/filter chain documentation** — architecture: how a
   sophisticated proxy structures its pipeline as composable filters.
   Validates (or challenges) modulo's current handler chain.

---

## What modulo gets right that the field generally doesn't

Worth saying explicitly so feature-FOMO doesn't pull modulo in the
wrong direction:

- **Opinionated about its domain.** Caddy and nginx are generic;
  modulo *understands WO apps* — wotaskd integration, `woinst` cookies,
  URL prefix conventions. That's a real differentiator and shouldn't be
  lost in a feature race.
- **Small enough to fully understand.** Envoy is impressive but
  operationally heavy. Modulo right now you could re-read end-to-end
  in an afternoon. That's worth preserving.
- **JVM is actually fine.** Caddy/Go gets more buzz, but modern Jetty
  is competitive with nginx for proxy workloads, and modulo lives in
  a JVM-shop deployment context. Java is the right choice here.
