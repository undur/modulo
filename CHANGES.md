# Changelog

## Unreleased

- **Dependencies updated**: Jetty 12.1.14 (with quiche 0.30.0 for HTTP/3), ng-objects 0.1.6 (on the same Jetty),
  acme4j 5.1.0 (with Bouncy Castle 1.83), and Jackson 3.2.3, which fixes two denial-of-service vulnerabilities in
  parsing JSON.

## 2026-10-16 (0.1.0)

The first release. modulo is a front-facing web server and reverse proxy for WebObjects and ng-objects
applications, replacing Apache and mod_WebObjects: it terminates TLS for any number of sites, obtains their
certificates, and routes each request to the right application instance.

**Sites and TLS**

- **Any number of sites on one server**, each with its hostnames, TLS served by SNI, and plain HTTP
  redirected to HTTPS. Aliases redirect to the site's canonical hostname, keeping path and query.
- **Certificates from Let's Encrypt**, ordered and renewed by modulo over HTTP-01, with a temporary
  certificate served until the real one arrives. Certificates you manage yourself work too.
- **HTTP/2**, and **HTTP/3** as an opt-in, served from one certificate covering every hostname.
- **Response compression** for text content.
- **WebSocket connections** proxied like any other request, over `ws://` and `wss://`.

**Routing to applications**

- **Applications and instances from wotaskd**: a site routes to an application by hostname, and the adaptor
  URLs of classic applications (`/WebObjects/App.woa/…`) route as they always have.
- **Multiple instances**: requests pinned to an instance by URL, sessions kept on their instance by a cookie
  modulo manages, new sessions spread round-robin and steered away from instances refusing them, and
  failover from instances that stop answering. JavaMonitor's per-instance timeouts are honored.
- **Rewrite rules per site**: regular expressions with captures, rewriting internally or redirecting, in
  place of Apache's `RewriteRule`.
- **WebServerResources served directly** for classic applications, the job Apache's document root did.
- **One trustworthy account of each request's origin**: the client's address, the requested host and the
  scheme reach the application in every header WebObjects, Project Wonder, wonder-slim and other software
  read, and nothing a client sends can override them. Proxies in front of modulo, such as a CDN or load
  balancer, can be trusted explicitly.

**Configuration**

- **One TOML file**, `modulo.toml`, with sites in their own files through `include` if you prefer, so each
  application's sites can live beside it.
- **Strict parsing**: an unknown key or an invalid value is an error, never silently ignored.
- **Reload without a restart**: sites, aliases, rewrites and certificates change live, validated before
  anything is applied.
- **Each site has a stable name**, independent of its hostnames, so changing the canonical domain never
  moves its certificate or log history.

**Operating it**

- **Admin pages**: a dashboard with traffic and response times, the applications and their instances, an
  overview of the sites and their certificates, recent events, and an inventory of every setting modulo runs
  with and where it came from.
- **Per-site access logs**, a file per day, recording the hostname used and the response time.
- **Error pages** that say whether a failure was in modulo or in the application.
- **`scripts/setup-server.sh`** installs the whole stack (wotaskd, JavaMonitor and modulo) on a fresh
  server, and **`scripts/deploy.sh`** deploys new builds of modulo.
- **A guide for moving from Apache and certbot**, site by site, in `docs/SETUP.md`.
