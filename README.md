## 🤖 Modulo

Modulo is a Java/Jetty-based reverse proxy for WebObjects and
ng-objects applications. It replaces Apache's `mod_WebObjects`
adaptor with a small, modern, easier-to-understand piece of software.

## Why?

A modern WO deployment historically required Apache + `mod_WebObjects`
(or `mod_proxy`) in front, with all the operational baggage that
brings: compiling an Apache module, dark configuration chants, and an
HTTP stack that didn't speak HTTP/2.

Some avoid this by replacing the adaptor with `mod_proxy` or another
generic proxy, but that loses the management benefits provided by
`JavaMonitor` and `wotaskd`.

Modulo is a small reverse proxy that:

- Runs as a standalone application, reading configuration from
  `wotaskd:1085` like `mod_WebObjects` does.
- Forwards requests matching the WO adaptor URL pattern (e.g.
  `/Apps/WebObjects/*`) to the appropriate WO app instance.
- Adds the `x-webobjects-*` headers WO/ng apps expect.
- Handles HTTP/2 cookie coalescing, Host preservation, and the other
  WO-specific niceties that generic proxies don't know about.

Since modulo is written in Java, it's easier than `mod_WebObjects` for
most WO developers to understand, maintain, debug and extend. And
since it's a standalone application, it works behind any web server
that can act as a reverse proxy — nginx, Caddy, Apache, HAProxy.

## Current state

Modulo has been running in production behind Apache for over a year,
serving real WO/ng-objects sites without issue. This is the core,
mature part of the project.

A **front-end mode** is being developed alongside it — TLS
termination, HTTP/2, SNI across many sites, ACME-challenge passthrough,
HTTP→HTTPS redirects, compression. The goal is for modulo to one day
replace the Apache layer too. The front-end works today and is in
production use for a real fleet of sites, but is still very much
"works for the author's deployments" territory — it has no
proper configuration format yet (sites are imported from existing
Apache vhost files as interim scaffolding), no native ACME (certbot
still does cert issuance), and a handful of operator-facing rough
edges. The quotes around "works" are very much still required.

See [ROADMAP.md](ROADMAP.md) for where modulo is heading and
[BRAINSTORMING.md](BRAINSTORMING.md) for ideas being considered.

## Repository layout

- **modulo-core** — the reverse proxy: routes requests to WO/ng app
  instances, integrates with `wotaskd`, sets WO-specific request
  headers. This is the mature, production-tested part.
- **modulo-frontend** — the front-facing HTTP server library: TLS
  termination, SNI, redirects, compression, ACME-challenge
  passthrough, cookie normalisation. Less mature; see above.
- **modulo-runner** — the deployable WO-style application bundle that
  wires the two together.

## Running modulo

Modulo is packaged as a WO-style application and run via its launcher
script. It needs to know how to reach `wotaskd` so it can discover
your WO apps:

```
./modulo-runner \
  -Dmodulo.wotaskd.host=[yourhost] \
  -Dmodulo.wotaskd.port=[yourport] \
  -Dmodulo.wotaskd.password=[yourpass]
```

By default modulo listens on port 1400 and serves as a plain reverse
proxy behind another web server.

### Web server configuration

If you're using Apache, add the following to your config (assuming
modulo is on the default port 1400 and your adaptor URL is
`/Apps/WebObjects`):

```
ProxyPreserveHost On
ProxyPass /Apps/WebObjects http://proxyhost:1400/Apps/WebObjects
ProxyPassReverse /Apps/WebObjects http://proxyhost:1400/Apps/WebObjects
```

### Trying out the front-end mode

Modulo's TLS front-end can run *alongside* the plain proxy when
configured. This lets you incrementally move sites off Apache without
breaking the rest. Config lives in a properties file at
`/opt/webobjects/modulo.conf`; see the `modulo.frontend.*` properties
and ROADMAP.md. Be aware that this part of the project is still in
flux — the config format will change before reaching a stable shape.

## Documentation

- [ROADMAP.md](ROADMAP.md) — what's next, iteration by iteration, and
  deliberate non-goals.
- [BRAINSTORMING.md](BRAINSTORMING.md) — ideas being considered but
  not committed to.

Design discussions live in the issue tracker.
