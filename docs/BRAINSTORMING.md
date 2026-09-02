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

### Request filtering and rate limiting

Grounded in a real sample: on 2026-08-31 a single IP fired 6,204
requests at www.rebbi.is in ~6 minutes (~17/sec, curl UA, HTTP/2) —
a vulnerability scanner walking `/etc/passwd`, `/.env` and friends —
on top of the constant background of `wp-json`/`/admin` probes. The
apps answered 404 in 0–1 ms so it cost nothing, but the same scan
against an expensive 404 path, or ten scanners at once, is real load.
Three cheap layers before app dispatch, all keyed on the true peer
address (modulo *is* the edge — X-Forwarded-For is never trusted):

1. **Deny rules** — reuse the per-site rewrite machinery: a `deny`
   action beside redirect/rewrite, first-match-wins, with a global
   default list for the eternal probes (dotfiles, `/.env`, `wp-*`,
   `/etc/`). Answer shape via the condition→responder layer — plain
   404, or drop-without-response for the rudest paths. Requests die
   before touching an app.
2. **Per-client token bucket** — rate + burst, global default with
   per-site override; in-memory map with periodic sweep; over-limit
   answers 429 through the responder layer. Request-level on purpose:
   the scanner rode h2 multiplexing, which a connection-level cap
   never sees.
3. **Auto-tempban** — N denied/429 responses within a window bans the
   IP for T minutes, checked at accept-time before any parsing. Turns
   6,204 log lines into a couple dozen. In-memory with TTL, surfaced
   on the dashboard (current bans, events, manual unban).

Plus the original connection-level caps (max connections per IP —
cheap on Jetty) as the outermost guard against the 10K-connections
client. Non-goals: payload inspection (WAF territory) and distributed
state — single-node in-memory matches the stack's scale, and bans are
allowed to be forgotten on restart.

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

### Controlled partial rollouts

The far end of the deployment spectrum, from a survey of how WO shops
actually deploy: the simplest case is one instance — stop, swap, start
(what `admin/deploy` does today). The most complex is many instances
across several hosts, a new build deployed to *a few* of them, A/B
observation of new against old, and then a hand-controlled slow
rollout — or a retreat. Everything in between depends on what the
change is. Hard to get right; the kind of thing that makes a stack
feel grown-up if it works.

What it would take, and how much already exists:

- **Versioned bundles on disk.** Old and new must coexist. The deploy
  already keeps moved-aside builds (`x<App>_<stamp>.woa`, pruned to a
  retained count); the missing half is installing a build *without*
  making it the live one — `<App>.woa` becomes a pointer, or instances
  point at versioned directories directly.
- **Per-instance bundle paths.** SiteConfig already carries a path per
  instance, so "instances 1–2 run the new build, 3–8 the old" needs no
  model change — only a deploy that sets paths selectively and bounces
  selectively.
- **Cohort routing in modulo.** Session stickiness already pins
  returning users to their instance, which is most of what A/B needs.
  On top: a per-app weight (new builds get n% of *new* sessions) and a
  way to pin a cohort deliberately (a cookie, a header, a user list)
  so the people testing see the new build on purpose.
- **Observing new against old.** The request stats and per-instance
  status modulo already keeps, split by build — error rates and
  latency per version side by side is the whole point.
- **The hand on the dial.** JavaMonitor, or the deploy client: promote
  (widen the cohort, then flip the pointer and bounce the rest) or
  retreat (point everything back; the old build never went away).

Prerequisite arcs: the graceful/rolling bounce variants in the roadmap
are the single-host, single-version version of the same machinery, and
the container model (`DEPLOYMENT-MODELS.md`) maps onto it directly —
two image versions behind one service is the same idea with the
filesystem replaced by a registry.

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
