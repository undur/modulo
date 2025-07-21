## 🤖 Modulo

Modulo is a Java/Jetty-based replacement for the WebObjects Apache adaptor `mod_WebObjects`.

## Why?

One of the problems with setting up a modern WO deployment environment is the Apache adaptor. The adaptor needs to be compiled, some dark configuration chants need to be recited, and after all that you're stuck with an Apache server that can't even serve HTTP/2.

Some avoid this by replacing the adaptor with Apache's `mod_proxy` or some other generic web proxying software, but doing so means losing a lot of the management benefits one gets from managing WO deployment environments using `JavaMonitor` and `wotaskd`.

## The solution?

Modulo uses `jetty-proxy` to provide reverse proxying specifically tailored for WO environments.

 * It runs as a standalone application that reads configuration from `wotaskd:1085` like `mod_WebObjects`.
 * At the webserver level, requests matching an URL pattern (the "adaptor URL",  e.g. `/Apps/WebObjects/*`) are forwarded to Modulo.
 * Modulo looks at the application name/instance number in the URL and forwards the request to the appropriate WO application instance.

Since Modulo is written in Java, it's easier than `mod_WebObjects` for most WO developers to understand, maintain, debug and extend. And since it's a standalone application, it works with any web server software that can act as a reverse proxy such as Nginx, Caddy, Apache and HAProxy.

## Configuration

If you're using Apache, just run Modulo and add the following to your global config (given Modulo is running on port 1400 and your "adaptor URL" is /Apps/WebObjects):

```
ProxyPreserveHost On
ProxyPass /Apps/WebObjects http://proxyhost:1400/Apps/WebObjects
ProxyPassReverse /Apps/WebObjects http://proxyhost:1400/Apps/WebObjects
```

## Does this actually work?

Modulo has been running in several deployment environments (Debian 12 with Apache serving up HTTP/2 as the web server) for a few months without any noticable problems.

## Future plans

Jetty is really just a web server so if you don't need the features of certain web serving software, you might not really need it. It could be easier to just use Jetty as the front-facing server, meaning no "double-proxying".

But that's for later.