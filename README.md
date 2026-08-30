## 🤖 Modulo

Modulo is a Java/Jetty-based reverse proxy for WebObjects and
ng-objects applications. It replaces Apache's `mod_WebObjects`
adaptor with a small, modern, easier-to-understand piece of software.

## Why?

A modern WO deployment historically required Apache + `mod_WebObjects`
in front, with all the operational baggage that brings: compiling an
Apache module, dark configuration chants, and an HTTP stack that
didn't speak HTTP/2.

Some avoid this by replacing the adaptor with `mod_proxy` or another
generic proxy, but that means losing many of the management benefits provided by
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
termination, HTTP/2, SNI across many sites, automatic certificates
via ACME, HTTP→HTTPS redirects, compression. The goal is for modulo
to fully replace the Apache + certbot layer. The front-end is in
production use for a real fleet of sites and now has its own
configuration format (a single JSON sites file) and native ACME
issuance/renewal — but it's still young: the ACME path is fresh out
of development, and a handful of operator-facing rough edges remain.
See [SETUP.md](docs/SETUP.md) for setting it up.

See [ROADMAP.md](docs/ROADMAP.md) for where modulo is heading and
[BRAINSTORMING.md](docs/BRAINSTORMING.md) for ideas being considered.

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

### The front-end mode

Modulo's TLS front-end can run *alongside* the plain proxy when
configured. This lets you incrementally move sites off Apache without
breaking the rest. [SETUP.md](docs/SETUP.md) covers the full setup — the
`modulo.conf` properties, the sites config format, automatic
certificates, and migrating an existing Apache + certbot deployment.

## Documentation

- [SETUP.md](docs/SETUP.md) — setting up modulo as a front-facing HTTPS
  server, or as a plain proxy behind another web server.
- [ROADMAP.md](docs/ROADMAP.md) — what's next, iteration by iteration, and
  deliberate non-goals.
- [BRAINSTORMING.md](docs/BRAINSTORMING.md) — ideas being considered but
  not committed to.

Design discussions live in the issue tracker.
