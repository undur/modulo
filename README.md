## 🤖 Modulo

Modulo is a Java/Jetty-based replacement for the WebObjects adaptor `mod_WebObjects`.

_The project is still experimental and this might never become reality._

## Why?

One of the largest hassles when setting up a WO deployment environment is the Apache adaptor. You need to compile the adaptor, recite dark configuration chants, scream in desperation at `/etc/WebObjects/Properties`, make a sacrifice (preferably human, young female recommended) to your deity of choice (Cthulhu works for me) and then hope for the best. And after all that it won't work with modern technology like HTTP/2 (and probably not HTTP/3, if and when that gets support in Apache).

These problems can be avoided by using `mod_proxy` instead to proxy requests to your application, but doing so removes a lot of the current (and potential) management goodness you get from managing your deployment environment using `JavaMonitor` and `wotaskd`, making it something of a pain to configure and maintain for each application.

Now, turns out Jetty ships with a reverse proxy provided by the `jetty-proxy` module, with which `mod_WebObjects` could theoretically be replaced:

 * The Jetty proxy would run as a standalone application.
 * That proxy application knows about WO apps and instances by reading `SiteConfig.xml` from `wotaskd:1085`.
 * At the webserver level, requests matching the adaptor URL pattern, e.g. `/Apps/WebObjects/*`, would be directed to this "proxy application" using e.g. Apache's `ProxyPass`
 * The Jetty proxy will use the application name and instance ID to yet again, forward the request to the appropriate instance.

If this works it also solves another problem with `mod_WebObjects` which is, honestly, that it's written in C. Having the "adaptor" in Java, the native language of most WO devs, means it can be more easily maintained and extended by WO users, along with `wotaskd` and `JavaMonitor`. Imagine a totally transparent, open WO Java-based adaptor that you can extend and understand yourself. Need to tweak configuraiton or get some logging? Add a GUI. Why shouldn't the WO adaptor be a WO app? `wotaskd` and `JavaMonitor` already are, seems fair `mod_WebObjects` should be too.

## Work

This has been tested on a small project, so we know it "looks like it works". It now remains to be seen if it works well enought to be sensible/feasible in reality. Potential issues are:

* **Performance** - Proxying a request twice isn't actually ideal. And Jetty might not be as fast as Apache/mod_WebObjects' almighty C code.
* **Lots of other things to come during discovery, probably** - because life. And HTTP.

## Thoughts

If this actually works, it might be interesting to look into taking this further. Jetty is a web server so if you don't need Apache's features, you might not really need Apache in front of it. It might be easier to just use Jetty as the front-facing server, meaning no "double-proxying" — and again, allowing us to have fun with the server. Still have to look into how convenient this would _actually_ be when it comes to virtual hosting, SSL certs, logging etc...

Probably senseless, but a fun thought experiment. Let's start with seeing if this _actually works in any way_ though.