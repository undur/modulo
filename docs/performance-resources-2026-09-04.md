# Serving web server resources: the app vs. Apache — performance

The recommended deployment serves an application's static resources
(css, js, fonts, images) from the application itself, leaving the
front end a pure proxy. The obvious question: what does that cost
against a web server serving the same file from disk? Measured.

## Setup

One machine (Debian 13, 4 ARM cores, 8 GB, JDK 26), everything on
loopback, load generator sharing the CPU with the servers — identical
conditions for every row, no network variance.

- **The file**: a 341 KB JavaScript library from a real application's
  `WebServerResources`, byte-identical on every path.
- **Apache 2.4.68** (event MPM, stock Debian config) serving it from
  disk — plain HTTP, and HTTPS with a local certificate.
- **The application** (wonder-slim, `WOAdaptorJetty`, production
  mode) serving it through its resource request handler
  (`…/App.woa/res/app/<file>`), plain HTTP straight to the instance.
- **modulo** in front of that application, HTTPS, the path a browser
  actually takes.

`wrk` 4.1, keep-alive, 15 s per run, `--latency`. A-B-A: the repeated
Apache-http and app-http runs agree within 2% and 10% respectively.

## Results

| 10 connections, 4 threads | req/s | throughput | p50 | p99 |
|---|---|---|---|---|
| Apache, HTTP, from disk | **5 200–5 300** | 1.7 GB/s | 1.0 ms | 8 ms |
| App, HTTP, `res/` | **2 900–3 200** | 1.0 GB/s | 2.2 ms | 8–11 ms |
| Apache, HTTPS, from disk | 1 770 | 577 MB/s | 3.9 ms | 13 ms |
| modulo (HTTPS) → app | 1 000 | 326 MB/s | 7.3 ms | 21 ms |

| 1 connection | req/s | throughput | p50 | p99 |
|---|---|---|---|---|
| Apache, HTTP, from disk | 3 400 | 1.1 GB/s | **0.25 ms** | 0.7 ms |
| App, HTTP, `res/` | 1 000 | 330 MB/s | **0.95 ms** | 2.1 ms |
| Apache, HTTPS, from disk | 660 | 214 MB/s | 1.3 ms | 3.2 ms |
| modulo (HTTPS) → app | 390 | 128 MB/s | 2.4 ms | 4.2 ms |

## Reading it

- **Apache from disk is the fastest way to move a file, by about
  1.8× over the app on the same protocol** (5 300 vs 3 000 req/s,
  1.0 vs 2.2 ms). Expected: Apache hands the page-cached file to the
  kernel with `sendfile`, no copy through user space. The app serves
  from an in-memory byte array (wonder-slim caches each resource
  after first read in production), so it isn't paying for disk — it
  pays for the request pipeline: a request object, dispatch, and the
  response body copied out through the adaptor. ~0.7 ms per request on
  this hardware, single-connection.
- **Through the front door the ratio holds, at 1.8× again** (Apache
  HTTPS 1 770 vs modulo→app 1 000 req/s). TLS costs both sides the
  same; the proxy hop adds its own copy. Note this is HTTP/1.1 from
  `wrk`; browsers get HTTP/2 from modulo, which Apache can't offer
  alongside mod_WebObjects at all.
- **In absolute terms none of this is a bottleneck.** One instance
  pushes a 341 KB file at 1 000 req/s through TLS and a proxy on
  four shared cores — 326 MB/s, a saturated gigabit link three times
  over. The resource handler also sends `cache-control: max-age=3600`,
  so a browser fetches each resource once an hour; resource requests
  are a rounding error next to page renders in any real workload.
- **What the app path buys for its 1.8×**: no split install, no
  docroot to keep in sync on deploy, one URL scheme in development and
  production, and the app in control of caching and access. Those are
  the reasons to recommend it; the measurement says the price is real
  but irrelevant at the scale WO applications run at.

## When Apache-from-disk would still win

Large media (video, big downloads) is the exception: the app's handler
serves a full 200 with the whole body and doesn't do range requests
yet, and `sendfile` on multi-megabyte files is a different league. For
that class of content a static file server — or a CDN — in front is
the right tool, and modulo's static-upstream site (roadmap) is the
place to route it.
