# Deployment models

How modulo, wotaskd and JavaMonitor should be designed so the same stack
works on a single server the way we run it today, *and* in a containerized
environment — without maintaining two stacks.

Status: design notes for discussion. Nothing here is implemented yet unless
it says so.

## Why this exists

Everything so far has been designed for one environment: a server we
provision, with the stack supervising app processes launched from `.woa`
bundles on its disk. That environment is real and worth serving well — one
cheap box, many apps, no container knowledge required — but it is not the
only one people deploy to, and several of the stack's habits are inherited
from classic WebObjects rather than chosen. This document names the two
environments, maps who does what in each, and turns the differences into
design rules, so that future features are built once and work in both.

The short version: **the layout and the front end port; the process
supervisor does not.** wotaskd and JavaMonitor exist to launch, watch and
restart processes on one host. In a container platform that job belongs to
the orchestrator. modulo — routing, affinity, WebSockets, certificates — is
what carries over, and it needs one seam to do so: how it learns where
instances are.

## The two models

**Model A — the server.** One host (or a few). The stack is installed under
`/opt/wo`. wotaskd launches app instances from bundles on disk, watches them
and restarts them; JavaMonitor is the human UI for that; modulo is the front
end and learns about instances from wotaskd. This is what
`setup-server.sh` builds and what runs in production today.

**Model B — the orchestrator.** Docker Swarm, Kubernetes, or similar. Each
app is an image; an instance is a replica; the orchestrator launches,
restarts, scales and health-checks. modulo runs as a service with 80/443
published and is the WO-aware front end for the replicas. wotaskd and
JavaMonitor have no role — their responsibilities are the orchestrator's.

**Model A′ — the appliance.** Model A inside a single container: one image
holding the stack, state on volumes, apps dropped into the `apps/` volume.
Not idiomatic (three processes in one container) but a legitimate way to
run "the server" on any Docker host without an orchestrator, and the
cheapest container deliverable we have. It follows from the directory
layout for free — see below.

## Who does what

| Concern | Model A (server) | Model B (orchestrator) |
|---|---|---|
| Launch an instance | wotaskd, from a `.woa` on disk | orchestrator, from an image |
| Restart on crash | wotaskd (autoRecover) | orchestrator restart policy |
| Scale | add instances in JavaMonitor | `--replicas N` |
| Liveness | WO lifebeats to wotaskd | orchestrator health check (HTTP) |
| Instance discovery (for modulo) | wotaskd's `woconfig` | service DNS / endpoints API / static list |
| App registry | `SiteConfig.xml` | the orchestrator's service definitions |
| Per-app front-end settings | JavaMonitor → adaptor config, or `app.toml` | a mounted file or labels |
| Sites (hostname → app, rules, certs) | `sites/*.toml` | `sites/*.toml`, mounted |
| Certificates | modulo + ACME, `acme/` on disk | modulo + ACME, `acme/` on a volume |
| Logs | files under `log/` and `apps/*/log/` | stdout, collected by the platform |
| Deploy a new build | drop a bundle, bounce the instance | push an image, rolling update |
| Human UI | JavaMonitor | the platform's own |
| Session affinity | modulo, by instance number | modulo, by replica identity |

Reading the table column-wise: in Model B the left half of the stack
disappears and modulo stays. Reading it row-wise gives the design rules.

## Design rules

### 1. Separate the instance registry from the process supervisor

modulo must consume an **instance registry** — "app X has instances at these
addresses, in these states" — and must not care who produces it. Today the
producer is wotaskd's `woconfig` document and the consumer is one class
(`AdaptorConfigParser`), which is the right shape already. Make the boundary
explicit:

```
InstanceSource
  apps()                       → the apps this source knows
  instances(app)               → address, identity, state, metadata
  changes()                    → poll or push; modulo doesn't care which
```

Implementations: `woconfig` (today), Docker DNS (`tasks.<service>` resolves
to every replica; re-resolve on the same cadence as today's poll),
Kubernetes endpoints, and a static list in `app.toml` for the simplest
cases. Several sources can be active at once — that also covers Model A
across several hosts, which is the same problem wearing different clothes.

Everything above this seam — routing, affinity, refusal steering, the
WebSocket tunnel, ACME, the admin UI — is unchanged by it.

### 2. Instance identity is an address, not a number

WO instance numbers exist because mod_WebObjects needed them. Replicas in
an orchestrator are identical and come and go; they have no stable number.
modulo's notion of "which instance" — the affinity cookie, URL pins, the
admin UI — should be an opaque identity derived from the source (host:port
today, a replica name or ordinal later), with the WO instance number kept
as *metadata* for Model A and for backward-compatible `.woa/N/` URLs. The
`woinst` cookie name can stay for compatibility; its value becomes opaque.

### 3. Liveness is an HTTP check, not a lifebeat

Lifebeats are instance → wotaskd; in Model B there is no wotaskd to beat
to. modulo should determine liveness itself — an HTTP probe of each
instance, plus the refusal steering it already does — and treat wotaskd's
view as one more hint, not the truth. Apps expose a health endpoint that
both modulo and an orchestrator can call. Lifebeats become optional (still
on in Model A, where wotaskd uses them for autoRecover).

### 4. Configuration is files, in layers

Three levels, one file name each, all plain files:

| Level | File | Answers |
|---|---|---|
| stack | `modulo.toml` | how this server/service behaves |
| site | `sites/<host>.toml` | which hostnames reach which app, with what rules and cert |
| app | `apps/<App>/app.toml` (proposed) | how to talk to this app's instances, whatever hostname the request used |

Files are the one thing both models can supply — on disk in Model A, as a
volume or ConfigMap in Model B. JavaMonitor may *edit* files or
`SiteConfig.xml`; modulo *reads* files. Per-app front-end settings
(timeouts, WebSocket limits, routing policy, error pages) therefore belong in
`app.toml`, not only in wotaskd's adaptor config, or they don't exist in
Model B. Secrets (admin password, stack password, ACME account) must also
be injectable from the environment or a secret file, because that is how
container platforms hand them over.

### 5. Software and state never share a directory

The layout in `DEFAULT-DIRECTORY-STRUCTURE.md` already does this: `stack/`
is software; `siteconfig/`, `acme/`, `log/`, `apps/`, `sites/` and
`modulo.toml` are state. That is precisely the image/volume boundary, and
what makes the appliance image (Model A′) nearly free. Rule: nothing under
`stack/` is written at runtime, ever; everything that is written lives
under a directory that can be a volume.

### 6. A bundle must run wherever it lands

A `.woa` built elsewhere carries its builder's `launch.jvm` path baked into
the launcher — wrong on any other machine. The supervisor supplies the JVM
at launch (`-launch.jvm=…` from one stack-level setting); in a container the
JVM is on `PATH`. Same for `WOPort`, `WOHost` and the instance identity:
arguments or environment at launch, never baked in. This is also what makes
"drag a bundle into JavaMonitor" work for bundles we didn't build.

### 7. Logs go to stdout first

Container platforms collect stdout. On the server, systemd and wotaskd
redirect stdout to files — which is where today's `log/` and `apps/*/log/`
come from. The rule is that no component *insists* on writing its own log
file: the process writes to stdout, and file placement is the supervisor's
decision. Rotation is then a supervisor concern in Model A and nobody's in
Model B.

### 8. The front end must never require wotaskd

Already true for ACME, routing, rewrites and WebSockets; keep it true. A
modulo with zero wotaskd configured and a static instance list is a valid
deployment. The admin UI shows *which* source an instance came from rather
than assuming.

### 9. JavaMonitor is Model A's UI, and stays thin

JavaMonitor's data model (`MSiteConfig`) must not leak into modulo;
modulo's model is apps, instances and sites, fed by instance sources. New
JavaMonitor features — deploy by dropping a bundle, proposing
`apps/<App>/…` paths, writing a site file — are operations on the
filesystem and on `SiteConfig.xml`, deliberately thin, so that in Model B
the same outcome is a compose file and nothing is lost. Whether JavaMonitor
should exist in Model B at all (as a read-only view of modulo's registry?)
is an open question, not a goal.

## What we keep from classic WO, and where we break

Keep, because they cost nothing and buy compatibility:

- the `.woa` bundle as the deployable unit, and the WO adaptor URL scheme
  (`/Apps/WebObjects/<App>.woa/…`)
- the `woconfig` document as *one* instance source
- the `woinst` cookie name

Break, when a feature needs it — and build the replacement so it works in
both models:

- instance numbers as identity (→ opaque identity, rule 2)
- lifebeats as the definition of "alive" (→ HTTP liveness, rule 3)
- `SiteConfig.xml` as the only place an app can be declared (→ instance
  sources, rule 1; `app.toml`, rule 4)
- the mod_WebObjects adaptor-config XML as the wire format between
  supervisor and front end (→ a simple registry document, when a second
  source exists to justify it)
- baked-in launch paths (→ rule 6)
- per-component log files (→ rule 7)

The test for any new feature: *does it work with a static instance list and
no wotaskd?* If not, it has grown a Model A-only assumption, and either that
is deliberate and documented (a JavaMonitor feature, say) or it needs the
seam.

## Roadmap, in dependency order

1. **Extract `InstanceSource`** from `AdaptorConfigParser` — do it when the
   upstream-timeouts work touches that code; no behavior change.
2. **HTTP liveness probing** in modulo, independent of lifebeats.
3. **Opaque instance identity** in the affinity cookie and URL pins, with
   instance numbers as compatibility metadata.
4. **Stack-level JVM injection** at launch (`-launch.jvm`), so foreign
   bundles run — also a prerequisite for drag-in deploys.
5. **`app.toml`** — decide, then implement per-app front-end settings as a
   file (the tension: wotaskd's adaptor config already carries some).
6. **Appliance image** — a Dockerfile derived from `setup-server.sh`, state
   on volumes. The first container deliverable; demos the stack to people
   who won't provision a server.
7. **Docker DNS instance source** plus a compose example with one WO app
   image — the first Model B deployment.
8. **JavaMonitor drag-in deploy** — a Model A feature, built thin (rule 9).

## Open questions

- **Session state.** WO sessions are in-memory, so affinity is mandatory in
  both models and a replica's death loses its sessions. That's true today
  too; it should be stated, and it argues for keeping affinity robust
  (refusal steering, graceful drain) rather than pretending replicas are
  stateless.
- **Certificates with several modulo replicas.** `acme/` on a shared volume
  with one replica doing issuance, or a single modulo replica in front of
  many app replicas — the latter is fine for the sizes we're talking about.
- **Multi-host Model A.** Several servers, one modulo: the same
  `InstanceSource` abstraction, one source per host. Worth confirming it
  falls out rather than assuming.
- **Does JavaMonitor exist in Model B?** See rule 9.
- **Health endpoint contract.** What an app must answer for modulo (and an
  orchestrator) to consider it alive — needs to be a one-line thing a WO app
  gets for free from the frameworks.
