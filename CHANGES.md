# Changelog

## Unreleased

- **Request paths may contain an encoded slash**
  A path segment with `%2F` in it, such as a file name containing `Etc/GMT`, was refused with
  `400 Ambiguous URI path separator` before the request reached an application. The path now reaches the
  application still encoded, so the application decodes the segment and the path's structure is kept.
  Encoded `.` and `..` segments are still refused, and serving files from a site's `woa` still can't leave
  its `WebServerResources` directories. (undur/wo-adaptor-jetty#9)

- **Request paths and queries may contain characters such as `|`**
  A request whose path contained `|` was refused with `400 Illegal Path Character`, and one with `|` in its
  query got modulo's own 400 page, before reaching the application. Both now reach the application exactly
  as the client wrote them, not re-encoded. A malformed escape, such as a stray `%` in a query, is still
  answered with 400. All of modulo's listeners share one configuration for this, so the rules can't differ
  between them. (#12)

- **modulo runs on ng-objects 0.1.3** (was 0.1.1).

- **`scripts/deploy.sh` uses the newest JDK on the target**
  The launch script in modulo's bundle names the server's JVM, so the deploy script now asks the server for
  its newest `/opt/jdk-<version>` instead of naming one. `JVM_PATH` overrides it.

- **`encodeCaptures` encodes a capture once, never twice**
  A rewrite rule's capture is part of the path as the client sent it, already percent-encoded. With
  `encodeCaptures`, an encoded capture such as `S%C3%A6la` was encoded a second time and reached the
  application as `S%25C3%25A6la`. The capture is now decoded first and then encoded as a query value, so it
  arrives as sent, while a literal `&` or `+` still becomes part of the value instead of splitting it.

## Before this changelog

modulo has not had a release yet. What follows is the work that shipped before this file existed, as the
roadmap recorded it, newest first.

- **JavaMonitor's timeouts are honored** *(2026-09-12)*
  An instance's receive timeout from JavaMonitor (or its send timeout, if only that is set) becomes the
  time modulo waits for that instance's response, per request, so it follows the request through failover.
  Without one, Jetty's default applies. The connect timeout is deliberately not applied: connecting to a
  live instance is instant, so it would only delay failover from a dead one.

- **Serving WebServerResources for classic applications** *(2026-09-01)*
  A site with `woa` set serves `/WebObjects/<App>.woa/…` from the bundle's `WebServerResources` and
  `Frameworks/*/WebServerResources` directories, never `Resources/`. The job Apache's document root did for
  plain WebObjects and Project Wonder applications.

- **setup-server.sh** *(2026-08-30, 2026-08-31)*
  The whole stack installed on a fresh server by one readable script: wotaskd, JavaMonitor and modulo, the
  standard layout, systemd units, firewall, and a JDK if none is installed. The JDK distribution and version
  are parameters, and the stack password is written at install.

- **WebSocket proxying** *(2026-08-30)*
  WebSocket connections are routed like HTTP and tunnelled raw after the handshake, over `ws://` and
  `wss://`.

- **One TOML configuration file** *(2026-08-30, #10)*
  `modulo.toml` holds the startup settings and the sites. A reload names any setting that changed but needs
  a restart. The JSON format is gone.

- **Per-site rewrite rules** *(2026-08-30, #4)*
  Regular expressions with captures, internal rewrites and redirects, first match wins, and URLs already in
  the adaptor's space never rewritten.

- **Multi-instance routing** *(2026-08-29, 2026-08-30)*
  Instance pinning by URL, sticky sessions through cookies modulo owns, round-robin, steering away from
  instances refusing new sessions, failover with a cool-down for dead instances, re-reading the
  configuration early when an instance stops answering, and a last resort for instances wotaskd doesn't
  list.

- **Admin pages** *(2026-08-29, 2026-08-30)*
  A dashboard with traffic charts, the applications and their instances, an overview of the sites, recent
  events, the full configuration inventory, and reload.

- **Logging and statistics** *(2026-08-29, 2026-08-30)*
  Per-site access logs with the hostname used and the response time, an event buffer, a tally of requests
  for unknown hostnames, and request statistics.

- **Error pages** *(2026-08-29)*
  Typed error conditions with a default page, saying whether the failure happened in modulo or in the
  application.

- **Sites configuration** *(2026-08-28, 2026-08-29)*
  Sites in their own files through `include`, strict parsing, and reload without a restart, validated before
  anything changes.

- **Certificates from Let's Encrypt** *(2026-08-28)*
  modulo orders and renews certificates itself over HTTP-01, serving a placeholder until the real one
  arrives.

- **The front end** *(2025, 2026)*
  TLS with SNI across all sites and certificates reloaded without a restart, redirects from HTTP to HTTPS
  and from aliases to the canonical hostname, and compression. The front end is its own module,
  modulo-frontend.
