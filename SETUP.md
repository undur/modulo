# Setting up modulo

This document covers running modulo as a front-facing HTTPS server for
a WO/ng-objects deployment — TLS termination, automatic certificates
via ACME, and reverse-proxying to your apps.

1. **[Initial setup](#part-1--initial-setup)** — from zero to a running server
2. **[Configuration](#part-2--configuration)** — every setting, in one file
3. **[Recipes](#part-3--recipes)** — adding domains, adding apps, migrating
   from Apache + certbot

For what modulo is and why, see [README.md](README.md). For where it's
heading, see [ROADMAP.md](ROADMAP.md). Prefer reading shell to prose?
[setup-server.sh](setup-server.sh) performs the whole initial setup —
full stack: wotaskd, JavaMonitor, modulo — and doubles as the guide.

## The pieces

```
            :80 / :443
                │
            ┌───▼────┐   adaptor config    ┌─────────┐
            │ modulo │ ◄────────────────── │ wotaskd │
            └───┬────┘      :1085          └─────────┘
                │ HTTP/1.1
        ┌───────┴───────┐
    ┌───▼───┐       ┌───▼───┐
    │ WO app│  ...  │ ng app│
    └───────┘       └───────┘
```

- **modulo** binds 80 + 443, terminates TLS (SNI across all your
  sites), answers ACME challenges, redirects HTTP→HTTPS and aliases →
  canonical hostname, and proxies to the right app instance.
- **wotaskd** tells modulo which apps exist and where their instances
  listen. Modulo re-reads this every 10 seconds.
- Which hostname routes to which app comes from modulo's config
  (Part 2).

Modulo also always runs a plain-HTTP proxy connector (default port
1400).

---

## Part 1 — Initial setup

### Prerequisites

- A JDK matching the build (the poms currently target Java 25).
- `wotaskd` running and reachable.
- DNS for your sites pointing at the server.
- Ports 80/443 free and open in the firewall. **Port 80 must be
  reachable from the public internet** — ACME HTTP-01 challenges
  arrive there.

### Build

Three Maven modules, built in dependency order. `-Dlaunch.jvm` bakes
the server's JVM path into the launcher:

```sh
( cd modulo-frontend && mvn clean install )
( cd modulo-core && mvn clean install )
( cd modulo-runner && mvn clean package -Dlaunch.jvm=/opt/jdk-26/bin/java )
```

This produces `modulo-runner/target/modulo-runner.woa`.

> **Always build with `clean`** when producing a deployment bundle — a
> non-clean build can leave jars from older dependency versions inside
> the `.woa`.

### Install on the server

```
/opt/webobjects/apps/modulo-runner.woa    the bundle
/opt/webobjects/modulo.toml               config (Part 2)
/opt/webobjects/acme/                     ACME state, created by modulo
/opt/webobjects/log/modulo.log            log output
```

A minimal systemd unit (`/etc/systemd/system/modulo.service`):

```ini
[Unit]
Description=modulo
After=network.target

[Service]
ExecStart=/opt/webobjects/apps/modulo-runner.woa/modulo-runner -Xms128m -Xmx256m
StandardOutput=append:/opt/webobjects/log/modulo.log
StandardError=inherit
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Binding ports below 1024 requires either running the service as root
or — nicer — granting the capability:

```ini
# in [Service], to run unprivileged:
User=modulo
AmbientCapabilities=CAP_NET_BIND_SERVICE
```

### Configure and start

Create `/opt/webobjects/modulo.toml` with your first site (see Part 2
for every field):

```toml
[wotaskd]
host     = "myserver.example"
port     = 1085
password = "..."

[acme]
email   = "you@example.com"
storage = "/opt/webobjects/acme"

[[sites]]
hostnames = [ "www.example.com", "example.com" ]
app = "MyApp"
```

Then:

```sh
systemctl enable --now modulo
tail -f /opt/webobjects/log/modulo.log
```

On a healthy first start you'll see, in order:

```
Front-end configured with 1 site(s) from /opt/webobjects/modulo.toml
No certificate on disk for www.example.com yet — writing self-signed placeholder
Loaded 1 certificate(s) into in-memory keystore
Ordering certificate for www.example.com covering [www.example.com, example.com]
Obtained certificate for www.example.com — valid until <date>
SslContextFactory reloaded with refreshed keystore
```

The TLS connector starts immediately on a self-signed placeholder and
hot-swaps to the real certificate seconds later. Verify from outside:

```sh
curl -sI https://www.example.com/         # 200, real certificate
curl -sI http://www.example.com/          # 301 → https
```

> **Trying things out?** Set `directory = "letsencrypt-staging"` in
> the `[acme]` table while experimenting. Let's Encrypt production
> rate-limits aggressively (5 failed validations per hostname per
> hour); staging is free to fail against. Flip to production (remove
> the line) once a site validates end-to-end, and delete the site's
> directory under `<storage>/sites/` so it reissues against
> production.

---

## Part 2 — Configuration

One TOML file: `/opt/webobjects/modulo.toml` (override the location
with `-Dmodulo.config-file=...`). Without it, modulo runs as a plain
reverse proxy on port 1400 only.

Two kinds of settings live in it:

| Settings | Changes require |
|---|---|
| `[frontend]`, `[admin]`, `[wotaskd]` | restart |
| `include`, `[acme]`, `[[sites]]` (+ fragment files) | `POST /reload` |

A reload that touches restart-required settings answers with an
explicit "restart required" notice — nothing is silently ignored.

**Top-level keys (`include`) must appear before the first table
header** — that's TOML, not modulo.

```toml
include = [ "/apps/*/conf/site.toml" ]   # optional: pull in sites from other files

[frontend]
httpPort     = 80                    # plain-HTTP connector (redirects + ACME challenges)
httpsPort    = 443                   # TLS connector
http3        = false                 # HTTP/3 (QUIC) — leave off for multi-site, see roadmap
accessLogDir = "/opt/webobjects/log/access"   # per-site access logs; unset disables

[admin]
password = "..."     # guards the admin pages (HTTP Basic, any username).
                     # Set: auth always required. Unset: open in development
                     # mode, disabled in production.

[wotaskd]
host     = "myserver.example"        # where wotaskd runs
port     = 1085                      # its config port
password = "..."                     # its config password

[acme]                               # required if any site uses ACME (i.e. normally)
email     = "you@example.com"        # required — CA sends expiry warnings here
storage   = "/opt/webobjects/acme"   # required — modulo-owned state dir
directory = "letsencrypt"            # optional — or "letsencrypt-staging", or any directory URI

# ...plus [[sites]] entries, optional if include covers everything
```

### Fragment files

Sites can live in separate files — one per app, next to the rest of
that app's configuration. Fragments hold only `[[sites]]` entries;
`include` and `[acme]` belong to the main file:

```toml
# /apps/myapp/conf/site.toml — one app and its domains

[[sites]]
hostnames = [ "www.example.com", "example.com" ]
app = "MyApp"

[[sites]]
hostnames = [ "www.other-brand.com" ]
app = "MyApp"
```

Include rules: relative patterns resolve against the main file's
directory; wildcards (`*`, `?`, `[...]`) match one path level — no
recursive `**`; a pattern **without** wildcards must name an existing
file, while a wildcard pattern matching nothing is just a logged
warning; matches load in sorted path order.

### The site entry

| Field | Required | Meaning |
|---|---|---|
| `hostnames` | yes | All hostnames the site answers for. The **first is the canonical hostname**; the rest are aliases that 301-redirect to it. |
| `app` | no | Upstream app name, as known to wotaskd. Omit for a site that terminates TLS but proxies nothing (warned at startup). |
| `tls` | no | **Omitted = ACME**: modulo obtains and renews the certificate. Or `{ mode = "manual", cert = "/path/fullchain.pem", key = "/path/privkey.pem" }` for certs you manage yourself. |
| `canonicalRedirect` | no (`true`) | 301 alias hostnames → canonical hostname. |
| `httpsRedirect` | no (`true`) | 301 plain HTTP → HTTPS. |
| `rewrites` | no | Ordered URL rewrite rules — see below. |

### Rewrite rules

Map friendly URLs into adaptor URL space, or answer with a redirect:

```toml
[[sites]]
hostnames = [ "www.example.com" ]
app = "MyApp"
rewrites = [
  { match = '^/$', to = '/Apps/WebObjects/MyApp.woa/wa/default' },
  { match = '^/things/([^/]+)$', to = '/Apps/WebObjects/MyApp.woa/wa/thing?id=$1' },
  { match = '^/old-name$', to = '/new-name', redirect = "permanent" },
  { match = '^(.*)$', to = '/Apps/WebObjects/MyApp.woa/wa/RouteAction/handler?url=$1', appendQuery = true },
]
```

Single-quoted TOML strings are literal (no escaping), so regexes like
`'^/entry/(\d+)\.html$'` are written exactly as the regex reads.

| Field | Required | Meaning |
|---|---|---|
| `match` | yes | Java regex, matched against the request path. Unanchored — anchor with `^` and `$`. |
| `to` | yes | Substitution target; `$1`–`$9` insert capture groups (`$$` for a literal `$`). A path for internal rewrites; a path or absolute URL for redirects. |
| `redirect` | no | Omitted = internal rewrite (the request proceeds to the site's `app` under the new path). `"temporary"` = 302, `"permanent"` = 301. |
| `appendQuery` | no (`false`) | When `to` has its own query part, also append the request's original query after it. Without it, a target query replaces the original; a target *without* a query always keeps the original. |
| `encodeCaptures` | no (`false`) | URL-encode each substituted capture — for captures that become query parameter values. |

Rules are tried in order; **first match wins**. Rules only apply to
paths *outside* the adaptor URL space — a request that is already
`/Apps/WebObjects/...` routes untouched, so app-generated URLs bypass
the rules and a catch-all `^(.*)$` can't loop.

### Strictness

Unknown fields (typos), duplicate hostnames (across all files, both
file names in the error), invalid regexes, an ACME site with no
`[acme]` table — all refuse to load, with a message naming the file
and site. The plain proxy keeps running, so a config typo degrades
service rather than taking it down. Hostnames are case-insensitive.

### Reload

```sh
curl -X POST -u :yourpassword https://yourserver/reload
```

Routing, redirects, rewrites, the keystore and the ACME-managed set
all swap in place; a new ACME site starts on a placeholder cert and
gets its real one seconds later. Reload is validation-first: a config
that doesn't parse is rejected with the exact error (HTTP 422) and the
running configuration is untouched. Also available as a button on
`/overview`.

### The ACME storage directory (state, not config)

Modulo writes it; you never edit it (but you can read it — everything
is plain PEM):

```
<storage>/account-key.pem            ACME account key (created on first use)
<storage>/sites/<host>/cert.pem      full chain, PEM
<storage>/sites/<host>/key.pem       private key, PKCS#8 PEM
```

- A managed site with no cert on disk gets a **self-signed
  placeholder** at startup so TLS binds immediately; the real
  certificate is ordered in the background and hot-swapped in.
- **Renewal** is checked every 12 hours. A certificate is (re)issued
  when it's missing, still a placeholder, expiring within 30 days, or
  no longer covering the site's configured hostnames. Failures are
  logged loudly and retried next cycle; the site keeps serving its
  current cert.
- Deleting a site's directory under `<storage>/sites/` forces a fresh
  issuance at the next check/restart — the recovery move for "this
  cert is somehow wrong".

---

## Part 3 — Recipes

### Adding a hostname to an existing site

Add it to the site's `hostnames` array (first position = canonical;
anywhere else = alias):

```diff
-hostnames = [ "www.example.com", "example.com" ]
+hostnames = [ "www.example.com", "example.com", "example.org" ]
```

Make sure DNS for the new name points at the server, then
`POST /reload`. The renewal check notices the certificate no longer
covers all configured hostnames and reissues it with the new name —
typically live within seconds.

### Adding a new site (new domain → an app)

1. Point the domain's DNS at the server.
2. Drop a fragment file into the app's folder (or add a site entry to
   an existing fragment / the main file):

   ```toml
   # /apps/newapp/conf/site.toml
   [[sites]]
   hostnames = [ "www.newsite.com", "newsite.com" ]
   app = "NewApp"
   ```

   If the file location matches an existing `include` pattern, nothing
   else needs wiring. `"NewApp"` must be the name wotaskd knows the
   app by.
3. `POST /reload`. Watch for placeholder → `Ordering` → `Obtained`,
   then verify:

   ```sh
   curl -s -o /dev/null -w '%{http_code} ssl:%{ssl_verify_result}\n' https://www.newsite.com/
   ```

### Removing a site

Delete its site entry (or fragment file) and `POST /reload`.
Optionally delete its directory under `<storage>/sites/` — nothing
renews it once it's out of the config.

### Migrating from Apache + certbot

Incremental, with a fallback at every step.

1. **Generate the sites config from your Apache vhosts.** The importer
   reads a manifest (one vhost-file path per line) and emits sites in
   `manual` mode pointing at your existing certbot PEMs:

   ```sh
   CP=$(find /opt/webobjects/apps/modulo-runner.woa/Contents/Resources/Java -name '*.jar' | tr '\n' ':')
   java -cp "$CP" modulo.config.ApacheConfigImporter /path/to/manifest.txt sites-imported.toml
   ```

   Review the output — sites whose hostnames couldn't be mapped to an
   app are emitted without an `app` and warned about.

2. **Serve through modulo**: put the imported sites into
   `modulo.toml` (inline or via `include`), stop Apache, start modulo.
   Behavior should match — same sites, same certbot certs, now served
   by modulo.

3. **Move sites to ACME** (one, several, or all at once): add the
   `[acme]` table, delete the sites' `tls` entries, `POST /reload`,
   watch the issuance log lines. Then retire that site's certbot
   renewal.

4. **Done when no `manual` site remains**: disable certbot entirely
   (`systemctl disable --now certbot.timer`); `/etc/letsencrypt` is
   now unused.

Let's Encrypt allows 50 new certificates per registered domain per
week — migrating even a large fleet in one go is usually fine; it's
*repeated failures* per hostname that rate-limit quickly.

---

## Verifying a deployment

```sh
# Right cert per site (SNI)?
openssl s_client -connect yourserver:443 -servername www.example.com </dev/null 2>/dev/null | openssl x509 -noout -subject -ext subjectAltName -enddate

# Redirects?
curl -sI http://www.example.com/ | head -3          # expect 301 → https
curl -sI https://example.com/ | head -3             # expect 301 → canonical hostname

# End to end, with certificate validation?
curl -s https://www.example.com/ -o /dev/null -w '%{http_code} ssl:%{ssl_verify_result}\n'
```

## Troubleshooting

- **Front-end didn't start, plain proxy did.** Almost always a config
  error — the log names the file, site and field. Deliberately
  non-fatal so the rest keeps serving.
- **Challenge failures** (`HTTP-01 challenge for <host> ended as INVALID`).
  The CA couldn't fetch `http://<host>/.well-known/acme-challenge/...`
  — check that DNS for that hostname points at this server and port 80
  is reachable from outside. *Every* hostname of the site must
  resolve, aliases included.
- **Browser shows an untrusted, self-signed certificate.** That's the
  placeholder — issuance hasn't succeeded yet; the log has the reason.
  Also expected (issuer "STAGING ...") while on `letsencrypt-staging`.
- **Rate-limited by Let's Encrypt.** You iterated against production.
  Switch to `letsencrypt-staging` until the setup validates; the
  failed-validation limit resets hourly.
- **First request to a fresh app returns 500.** The wotaskd topology
  poll hasn't seen the new instance yet; it converges within ~10
  seconds.
- **`No TLS certificates could be loaded — refusing to start front-end
  with empty keystore`.** Every site failed to load its PEMs — for
  manual sites check the paths (per-site failures are warned
  individually above this error).
