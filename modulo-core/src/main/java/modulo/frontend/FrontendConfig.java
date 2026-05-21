package modulo.frontend;

import java.nio.file.Path;

/**
 * Configuration for the TLS front-end.
 *
 * Passed in by whoever is launching modulo (modulo-runner today); kept as a
 * plain value so modulo-core stays free of any specific property/config
 * subsystem. A null instance means "front-end disabled, run as plain
 * reverse proxy only."
 *
 * @param apacheConfigManifest Path to a text file listing the Apache vhost
 *            files to import. One path per line; blank lines and {@code #}
 *            comments ignored.
 * @param httpPort Port for the front-end's plain-HTTP connector.
 * @param httpsPort Port for the front-end's TLS connector (also used as the
 *            UDP port for the HTTP/3 connector when {@link #http3} is true).
 * @param acmeWebroot Optional directory whose {@code .well-known/acme-challenge/}
 *            subdirectory holds HTTP-01 challenge tokens. {@code null} disables
 *            challenge passthrough.
 * @param http3 When true, opens an additional UDP connector on {@code httpsPort}
 *            speaking HTTP/3 (QUIC), and adds an {@code Alt-Svc} advertisement
 *            on HTTP/2 responses so capable clients try to upgrade. Requires
 *            launching the JVM with native-access enabled for the Quiche
 *            module (see modulo.conf comments).
 */
public record FrontendConfig(
		Path apacheConfigManifest,
		int httpPort,
		int httpsPort,
		Path acmeWebroot,
		boolean http3 ) {
}
