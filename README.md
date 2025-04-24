## 🤖 Modulo

Modulo is a Java/Jetty-based replacement for the WebObjects adaptor `mod_WebObjects`.

_This project is still experimental and these musings might never become reality._

## Why?

One of the largest hassles when setting up a WO deployment environment is the Apache adaptor. You need to compile the adaptor, recite dark configuration chants, scream in desperation at `/etc/WebObjects/Properties`, make a sacrifice (preferably human, young female recommended) to your deity of choice (Cthulhu works for me) and then hope for the best. And after all that, `mod_WebObjects` will force you to disable HTTP/2 (and probably eventually HTTP/3, if and when that gets support in Apache. Not that that's a biggie but it's a little irritating).

Some of these problems can be avoided by using `mod_proxy` to proxy requests to your application, but doing so removes a lot of the current (and potential) management goodness you get from managing your deployment environment using `JavaMonitor` and `wotaskd`, and makes it something of a pain to create and maintain configuration for each application.

## The solution?

Turns out Jetty ships with a reverse proxy provided by the `jetty-proxy` module that could theoretically replace `mod_WebObjects`. How so?

 * The Jetty proxy runs as a standalone application.
 * The proxy application knows about WO apps and instances by reading the adaptor configuration from `wotaskd:1085`.
 * At the webserver level, requests matching the adaptor URL pattern, e.g. `/Apps/WebObjects/*`, would be forwarder to this "proxy application" using e.g. Apache's `ProxyPass`.
 * The Jetty proxy will then forward the request to the appropriate instance for handling based on application name (and instance number, if applicable).

This solves another problem with `mod_WebObjects` which is, really, that it's written in C, making it something of a black box for most WO developers. Having an "adaptor" written in Java, the native language of most WO devs, means it can be more easily maintained and extended, along with `wotaskd` and `JavaMonitor`. Imagine a totally open WO Java-based adaptor that you can understand and extend. Need to tweak configuration, customize your balancing strategy or add some weird adaptor logging? Sure, go ahead. Want to know more about what's happening? Add a GUI. After all, why shouldn't the WO adaptor be a WO app? `wotaskd` and `JavaMonitor` already are, seems fair `mod_WebObjects` could be too.

This also makes the "adaptor" independent of the web server. A standalone Java-based proxy adaptor can be used with any server that can act as a reverse proxy, Nginx, Caddy, Apache, HAProxy etc.

## How would this work?

This would make the setup of a WO adaptor for Apache mean (1) running the proxying application and (2) adding this something like this to the Apache config:

```
ProxyPreserveHost On
ProxyPass /Apps/WebObjects http://proxyhost:1400/Apps/WebObjects
ProxyPassReverse /Apps/WebObjects http://proxyhost:1400/Apps/WebObjects
```

## Status

The proxy functionality described above has been implemented in a basic way for testing and it certainly "looks like it works". A little more testing will reveal if it works well enough to be sensible/feasible, especially performance vise (although first tests look surprisingly promising in that regard).

## More?

If this actually works, it might be interesting to look into taking this further. Jetty is really just a web server so if you don't need Apache's features, you might not really need Apache in front of it - it might be easier to just use Jetty as the front-facing server, meaning no "double-proxying" — and again, allowing us to have a little fun with how a server is configured. Still have to look into how convenient this would _actually_ be when it comes to virtual hosting, SSL certs, logging etc...

Probably senseless, but a fun thought experiment. Let's start with seeing if this _actually works in any way_ though.