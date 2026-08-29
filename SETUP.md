# Setting up modulo

This document covers running modulo as a front-facing HTTPS server for
a WO/ng-objects deployment — TLS termination, automatic certificates
via ACME, and reverse-proxying to your apps. No Apache, no certbot.

It is organized in three parts:

1. **[Initial setup](#part-1--initial-setup)** — from zero to a running server
2. **[Configuration architecture](#part-2--configuration-architecture)** — what's
   configured where, and what needs to be set
3. **[Recipes](#part-3--recipes)** — adding domains, adding apps, migrating
   from Apache + certbot

For what modulo is and why, see [README.md](README.md). For where it's
heading, see [ROADMAP.md](ROADMAP.md).

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
  listen. Modulo re-reads this every 10 seconds — a freshly started
  app may 500 for a few seconds until the topology poll catches up.
- Which hostname routes to which app comes from modulo's own sites
  config (Part 2).

Modulo also always runs a plain-HTTP proxy connector (default port
1400) — useful as a safety net and for traffic from a legacy web
server during migration.

---

## Part 1 — Initial setup

### Prerequisites

- A JDK matching the build (the poms currently target Java 25).
- `wotaskd` running and reachable, with its config password.
- DNS for your sites pointing at the server.
- Ports 80/443 free (stop Apache/nginx first) and open in the
  firewall. **Port 80 must be reachable from the public internet** —
  ACME HTTP-01 challenges arrive there.

### Build

Three Maven modules, built in dependency order. `-Dlaunch.jvm` bakes
the server's JVM path into the launcher:

```sh
( cd modulo-frontend && mvn clean install )
( cd modulo-core && mvn clean install )
( cd modulo-runner && mvn clean package -Dlaunch.jvm=/opt/jdk-26/bin/java )
```

This produces `modulo-runner/target/modulo-runner.woa`, a WO-style
application bundle.

> **Always build with `clean`** when producing a deployment bundle — a
> non-clean build can leave jars from older dependency versions inside
> the `.woa`.

### Install on the server

Copy the bundle over and give it a service. The conventional layout:

```
/opt/webobjects/apps/modulo-runner.woa    the bundle
/opt/webobjects/modulo.conf               runtime properties (Part 2)
/opt/webobjects/sites.json                sites config (Part 2)
/opt/webobjects/acme/                     ACME state, created by modulo
/opt/webobjects/log/modulo.log            log output
```

A minimal systemd unit (`/etc/systemd/system/modulo.service`):

```ini
[Unit]
Description=modulo
After=network.target

[Service]
ExecStart=/opt/webobjects/apps/modulo-runner.woa/modulo-runner \
  -Dmodulo.wotaskd.host=localhost \
  -Dmodulo.wotaskd.port=1085 \
  -Dmodulo.wotaskd.password=CHANGEME \
  -Xms128m -Xmx256m
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

Create `/opt/webobjects/modulo.conf`:

```properties
modulo.frontend.sites-file = /opt/webobjects/sites.json
```

Create `/opt/webobjects/sites.json` with your first site (see Part 2
for every field):

```json
{
  "acme": { "email": "you@example.com", "storage": "/opt/webobjects/acme" },
  "sites": [
    { "hostnames": [ "www.example.com", "example.com" ], "app": "MyApp" }
  ]
}
```

Then:

```sh
systemctl enable --now modulo
tail -f /opt/webobjects/log/modulo.log
```

On a healthy first start you'll see, in order:

```
Front-end configured with 1 site(s) from /opt/webobjects/sites.json
No certificate on disk for www.example.com yet — writing self-signed placeholder
Loaded 1 certificate(s) into in-memory keystore
Ordering certificate for www.example.com covering [www.example.com, example.com]
Obtained certificate for www.example.com — valid until <date>
SslContextFactory reloaded with refreshed keystore
```

The TLS connector starts immediately on a self-signed placeholder and
hot-swaps to the real Let's Encrypt certificate seconds later — no
second restart. Verify from outside:

```sh
curl -sI https://www.example.com/         # 200, real certificate
curl -sI http://www.example.com/          # 301 → https
```

> **Trying things out?** Set `"directory": "letsencrypt-staging"` in
> the `acme` block while experimenting. Let's Encrypt production
> rate-limits aggressively (5 failed validations per hostname per
> hour); staging is free to fail against. Flip to production (remove
> the line) once a site validates end-to-end, and delete the site's
> directory under `<storage>/sites/` so it reissues against
> production.

---

## Part 2 — Configuration architecture

Modulo's configuration lives in three layers, each with a distinct
job. From the outside in:

| Layer | Where | What it configures | Changes require |
|---|---|---|---|
| Launch properties | systemd unit / launcher args | How to reach wotaskd; where modulo.conf lives | service restart |
| `modulo.conf` | `/opt/webobjects/modulo.conf` (properties) | Whether/where the front-end runs: ports, sites-file path | service restart |
| Sites config | `sites.json` + optional per-app fragment files (JSON) | Everything per-site: hostnames, routing, TLS, redirects — plus the deployment-wide `acme` block | service restart |

Plus one directory that is **state, not configuration**: the ACME
storage directory. Modulo writes it; you never edit it (but you can
read it — everything is plain PEM).

### Layer 1: launch properties

Passed as `-D` arguments at launch (typically in the systemd unit):

| Property | Required | Purpose |
|---|---|---|
| `modulo.wotaskd.host` | yes | Host wotaskd runs on. |
| `modulo.wotaskd.port` | yes | wotaskd's config port (conventionally 1085). |
| `modulo.wotaskd.password` | yes | wotaskd's config password. |
| `modulo.config-file` | no | Alternate location for modulo.conf (default `/opt/webobjects/modulo.conf`). |

### Layer 2: modulo.conf

A plain Java properties file. Without it (or without a site source
configured), modulo runs as a plain reverse proxy on port 1400 only —
the front-end simply doesn't start.

| Property | Default | Purpose |
|---|---|---|
| `modulo.frontend.sites-file` | — | Path to the sites config. **Setting this is what enables the front-end.** |
| `modulo.frontend.http-port` | `80` | Plain-HTTP connector (redirects + ACME challenges). |
| `modulo.frontend.https-port` | `443` | TLS connector. |
| `modulo.frontend.http3` | `false` | HTTP/3 (QUIC). Leave off for multi-site deployments — see "Deliberate non-goals" in the roadmap. |
| `modulo.admin-password` | — | Guards the `/overview` configuration page (HTTP Basic, any username). When set, auth is always required; when unset, the page is open in development mode and disabled in production. |
| `modulo.frontend.acme-webroot` | — | *Transitional.* Webroot where an external certbot writes HTTP-01 tokens, for deployments mid-migration to native ACME. |

### Layer 3: the sites config

One main JSON file, optionally spread across per-app fragment files.
`//` comments and trailing commas are allowed everywhere. The **main
file** holds the deployment-wide blocks and (optionally) sites of its
own:

```json
{
  // Required if any site uses ACME (i.e. normally):
  "acme": {
    "email": "you@example.com",          // required — CA sends expiry warnings here
    "storage": "/opt/webobjects/acme",   // required — modulo-owned state dir
    "directory": "letsencrypt"           // optional — or "letsencrypt-staging", or any directory URI
  },

  // Optional: pull in sites from other files
  "include": [ "/apps/*/conf/site.json" ],

  // Optional if include covers everything:
  "sites": [ ... ]
}
```

**Fragment files** contain only a `"sites"` array — `acme` and
`include` belong to the main file alone. This is the layout that keeps
each application's sites next to the rest of that application's
configuration:

```json
// /apps/myapp/conf/site.json — one app and its domains
{
  "sites": [
    { "hostnames": [ "www.example.com", "example.com" ], "app": "MyApp" },
    { "hostnames": [ "www.other-brand.com" ], "app": "MyApp" }
  ]
}
```

Include rules: relative patterns resolve against the main file's
directory; wildcards (`*`, `?`, `[...]`) match one path level — no
recursive `**`; a pattern **without** wildcards must name an existing
file, while a wildcard pattern matching nothing is just a logged
warning; matches load in sorted path order.

**The site object** — the unit of configuration:

| Field | Required | Meaning |
|---|---|---|
| `hostnames` | yes | All hostnames the site answers for. The **first is the canonical hostname**; the rest are aliases that 301-redirect to it. |
| `app` | no | Upstream app name, as known to wotaskd. Omit for a site that terminates TLS but proxies nothing (warned at startup). |
| `tls` | no | **Omitted = ACME**: modulo obtains and renews the certificate. Or `{ "mode": "manual", "cert": "/path/fullchain.pem", "key": "/path/privkey.pem" }` for certs you manage yourself. |
| `canonicalRedirect` | no (`true`) | 301 alias hostnames → canonical hostname. |
| `httpsRedirect` | no (`true`) | 301 plain HTTP → HTTPS. |

**Strictness is a feature.** Unknown fields (typos), duplicate
hostnames (across all files, with both file names in the error), a
site using ACME with no `acme` block, `manual` mode missing a path —
all refuse to start the front-end, with a message naming the file and
site. The plain proxy keeps running, so a config typo degrades service
rather than taking it down. Hostnames are case-insensitive and
normalized to lowercase.

### The ACME storage directory (state, not config)

```
<storage>/account-key.pem            ACME account key (created on first use)
<storage>/sites/<host>/cert.pem      full chain, PEM
<storage>/sites/<host>/key.pem       private key, PKCS#8 PEM
```

Behavior around it:

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
-  "hostnames": [ "www.example.com", "example.com" ],
+  "hostnames": [ "www.example.com", "example.com", "example.org" ],
```

Make sure DNS for the new name points at the server, then restart
modulo. The renewal check notices the certificate no longer covers all
configured hostnames and reissues it with the new name — typically
live within seconds:

```
Certificate for www.example.com does not cover all configured hostnames [...] — reissuing
Ordering certificate for www.example.com covering [www.example.com, example.com, example.org]
Obtained certificate for www.example.com — valid until <date>
```

With `canonicalRedirect` on (the default), the new alias 301s to the
canonical hostname.

### Adding a new site (new domain → an app)

1. Point the domain's DNS at the server.
2. Drop a fragment file into the app's folder (or add a site object to
   an existing fragment / the main file):

   ```json
   // /apps/newapp/conf/site.json
   { "sites": [ { "hostnames": [ "www.newsite.com", "newsite.com" ], "app": "NewApp" } ] }
   ```

   If the file location matches an existing `include` pattern, nothing
   else needs wiring. `"NewApp"` must be the name wotaskd knows the
   app by — a name wotaskd doesn't recognize is warned at startup
   (`points at apps unknown to wotaskd`).
3. Restart modulo. Watch for placeholder → `Ordering` → `Obtained`,
   then verify:

   ```sh
   curl -s -o /dev/null -w '%{http_code} ssl:%{ssl_verify_result}\n' https://www.newsite.com/
   ```

### Removing a site

Delete its site object (or fragment file) and restart. Optionally
delete its directory under `<storage>/sites/` — nothing renews it once
it's out of the config.

### Migrating from Apache + certbot

The pieces are designed for an incremental migration with a fallback
at every step.

1. **Generate the sites config from your Apache vhosts.** The importer
   reads a manifest (one vhost-file path per line) and emits sites in
   `manual` mode pointing at your existing certbot PEMs:

   ```sh
   CP=$(find /opt/webobjects/apps/modulo-runner.woa/Contents/Resources/Java -name '*.jar' | tr '\n' ':')
   java -cp "$CP" modulo.config.ApacheConfigImporter /path/to/manifest.txt sites.json
   ```

   Review the output — sites whose hostnames couldn't be mapped to an
   app are emitted without an `app` and warned about.

2. **Point modulo at it**: set `modulo.frontend.sites-file`, stop
   Apache, start modulo. Behavior should match — same sites, same
   certbot certs, now served by modulo.

3. **Move sites to ACME** (one, several, or all at once): add the
   `acme` block, delete the sites' `tls` blocks, restart, watch the
   issuance log lines. Then retire that site's certbot renewal.

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

- **Front-end didn't start, plain proxy did.** Almost always a sites
  config error — the log names the file, site and field. Deliberately
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
