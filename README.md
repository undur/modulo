## 🤖 Modulo

Modulo is a Java/Jetty-based replacement for the WebObjects Apache adaptor `mod_WebObjects`.

## Why?

One of the problems with setting up a modern WO deployment environment is the Apache adaptor. The adaptor needs to be compiled, some dark configuration chants need to be recited, and after all that, you're stuck with Apache. And you're stuck with running it in a mode incompatible with HTTP/2, so you can't use that.

Some avoid this by using Apache's `mod_proxy` (or some other generic proxy) to manually forward requests to WO applications. Doing so removes most of the management benefits one gets from managing WO deployment environments using `JavaMonitor` and `wotaskd`.

## The solution?

Modulo runs as a standalone application and uses Jetty, specifically the `jetty-proxy` module, to provide managed reverse proxying for WO environments. In short:

 * It runs as a standalone application.
 * It reads the adaptor configuration from `wotaskd:1085`, much like `mod_WebObjects`.
 * At the webserver level, requests matching an URL pattern (the "adaptor URL",  e.g. `/Apps/WebObjects/*`) are forwarded to this "proxy application".
 * Modulo directs the request to the appropriate WO application instance for handling based on application name (and instance number, if present).

Since Modulo is written in Java, it's easier than `mod_WebObjects` for most WO developers to understand, maintain, debug and extend. Need to tweak configuration, customize your balancing strategy or add some logging? Sure, go ahead. Want a nice UI for your stuff? No problem.

As mentioned before, this also makes the WO adaptor independent of the web server. A standalone Java-based proxy adaptor can be used with any server that can act as a reverse proxy, Nginx, Caddy, Apache, HAProxy etc.

## Configuration

Run Modulo and add this to your global Apache config (given that Modulo is running on port 1400):

```
ProxyPreserveHost On
ProxyPass /Apps/WebObjects http://proxyhost:1400/Apps/WebObjects
ProxyPassReverse /Apps/WebObjects http://proxyhost:1400/Apps/WebObjects
```

## Is this ready for use?

According to my own testing, yes. Modulo has been running in several deployment environments (with Apache serving up HTTP/2 as the web server) for a couple of months without any noticable problems.

## Future plans

Jetty is really just a web server so if you don't really need a featureful web server, you might not really need separate software for it. It could be easier to just use Jetty as the front-facing server, meaning no "double-proxying". But that's for later.