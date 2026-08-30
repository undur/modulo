# modulo vs Apache + mod_WebObjects — performance

## Setup

One machine (Debian 12, OpenSSL 3, JDK 26, current kernel), two
front-ends side by side, same WebObjects application instance behind
both:

- **Apache 2.4.68** with **mod_WebObjects built from the current
  adaptor sources**, prefork MPM (required by
  mod_WebObjects), stock Debian configuration.
- **modulo**, default configuration.

Both negotiate **TLS 1.3**. The target application (AjaxPlayground, a
wonder-slim app with no database) renders a full dynamic component
page per request — so every request exercises TLS, the front-end, the
adaptor protocol, and a real page render; nothing is served from
cache or disk. Load generated on the same machine over loopback: no
network variance, no client-side crypto skew. Sequence A-B-A — the
two modulo runs agree within 2% on every metric.

## Results

| HTTP/1.1, keepalive (wrk, 15s) | Apache + mod_WebObjects | modulo | ratio |
|---|---|---|---|
| 10 connections | 2 686 req/s · p50 1.18ms | **13 400–13 700 req/s** · p50 0.65ms | **5.0×** |
| 30 connections | 4 260 req/s · p50 1.77ms | **16 500 req/s** · p50 1.56ms | **3.8×** |

| HTTP/2 (h2load, 10 connections × 5 streams) | | |
|---|---|---|
| Apache + mod_WebObjects | not possible | mod_WebObjects requires prefork; mod_http2 requires a threaded MPM — mutually exclusive on any Apache version |
| modulo | **18 000–18 300 req/s** | negotiated via ALPN, zero app changes |

## Controls and honest footnotes

- **Tuning control:** re-running Apache with `MaxKeepAliveRequests 0`
  (unlimited keepalive) changed nothing — 2 705 req/s vs 2 686 stock.
  The gap is not a keepalive configuration artifact.
- **Where the gap lives:** two places. Per-request latency through
  Apache is ~2× (1.18ms vs 0.65ms median), and its connections
  additionally sit idle ~⅔ of the time between requests (10
  connections ÷ 2 705 req/s = 3.7ms per request slot against a 1.18ms
  response time) — a turnaround stall in the prefork +
  mod_WebObjects pipeline.
- **Where Apache wins:** fresh-connection TLS setup, ~9.2ms vs
  ~11.9ms (native OpenSSL vs the JVM's TLS). Clients pay this once
  per connection, then live in the tables above.
- Load generator and servers shared the machine's CPU — identically
  for every run, and the faster side is the one that had *less* CPU
  left over for itself.