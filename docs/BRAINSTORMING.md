# Modulo Brainstorming

Ideas worth thinking about but not yet committed to. Some will
graduate to `ROADMAP.md`; some stay here indefinitely as "good to
know"; some become non-goals.

When an item moves to the roadmap, delete it here — git history
captures the migration. When an item is decided against, move it to
the roadmap's "Deliberate non-goals".

---

## Survey of the field

Modulo enters a space with mature competitors. None are aimed at
WO/ng-objects deployments — that's the niche — but many have spent
decades on reverse-proxy concerns we'll eventually want answers for.

### The titans

- **nginx** — the default answer for ~15 years. Excellent performance,
  mature modules. Config language is its own beast; reload semantics
  have edge cases.
- **Apache httpd** — the elder. Excellent modules, painful to operate
  well at scale.
- **HAProxy** — best-in-class TCP+HTTP load balancing, exceptional
  stability and observability (the stats page is iconic).
- **Envoy** — cloud-native, foundation of service meshes. xDS dynamic
  config, deep observability, programmable filter chain. Needs
  infrastructure to manage.

### The modern entrants

- **Caddy** — Go, philosophically closest to modulo. Automatic HTTPS
  by default is the killer feature. Worth reading closely.
- **Traefik** — Go, container-native config discovery.
- **Pingora** — Cloudflare's edge proxy, Rust. Library not server.

### Single-purpose tools worth knowing

- **acme.sh / lego / certbot** — pure ACME clients.
- **certmagic** — Caddy's cert management as a standalone Go library.

---

## Ideas worth thinking about

### A Caddy-style admin API (and its cousins)

The most copy-worthy idea in the field: config as JSON over HTTP,
POST a complete config, atomic apply, errors-or-success — the on-disk
file a projection of API state. If JavaMonitor eventually drives
modulo's config, modulo's admin API should probably look like this
(one endpoint, whole config, atomic) rather than dozens of REST
endpoints; JSON stays the natural wire format even though TOML is the
operator format. Related smaller idea: file-watch auto-reload (nginx
style) — trivial now that validated reload exists, but implicit
reloads of half-saved edits are a real hazard; the explicit POST may
simply be better.

### Active upstream health checks

Today's health signals are passive (connect-failure cool-down,
proof-of-life on response). Active checks (periodic probe, remove
from rotation, restore on recovery) enable "app is restarting → serve
a maintenance page instead of the first user eating a 502". Pairs
naturally with per-site error responses (#5).

### Connection-level rate limiting and caps

A single client opening 10K connections is currently undefended.
Cheap to add on Jetty; easy to forget until it bites.

### Unix domain sockets for modulo → app

Same-host proxy→app over UDS instead of loopback TCP: typical wins
10–30% latency on small requests, plus a filesystem-permission trust
boundary. Costs: wo-adaptor-jetty binds UDS, wotaskd's model learns
socket paths, and "curl the app on its port" debugging goes away. Not
urgent — the loopback hop is microseconds — but worth picking up if
performance or local-trust become design pressures.

### Authentication / ACL at the proxy layer

"This site requires auth before forwarding" (OAuth, mTLS, header
checks). For WO apps mostly the app's concern; probably out of
modulo's scope, but worth knowing the pattern (nginx `auth_request`,
Traefik middleware).

### Friendlier TOML footgun error

When strict parsing finds a known root key (`include`) inside a table
(`acme.include`), hint that top-level keys must precede the first
`[section]` header instead of the raw "Unrecognized property"
message. Parked 2026-08-30 until the mistake recurs in the wild.

---

## Reading list

1. **Caddy's docs** — automatic HTTPS, JSON config structure, the
   Caddyfile-desugars-to-JSON adapter pattern.
2. **HAProxy's configuration guide** — health checks, stick tables,
   stats; decades of operational experience condensed.
3. **Envoy's listener/filter-chain docs** — how a sophisticated proxy
   structures its pipeline as composable filters.

---

## What modulo gets right that the field generally doesn't

Said explicitly so feature-FOMO doesn't pull in the wrong direction:

- **Opinionated about its domain.** Caddy and nginx are generic;
  modulo *understands WO apps* — wotaskd, `woinst`, adaptor URL
  conventions. A real differentiator; don't lose it in a feature race.
- **Small enough to fully understand.** You can re-read modulo
  end-to-end in an afternoon. Worth preserving.
- **JVM is actually fine.** Modern Jetty is competitive with nginx for
  proxy workloads, and modulo lives in a JVM-shop context.
