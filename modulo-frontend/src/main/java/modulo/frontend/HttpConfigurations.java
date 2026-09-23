package modulo.frontend;

import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConfiguration;

/**
 * The one place modulo's connectors get their {@link HttpConfiguration}, so
 * settings every listener must share can't drift between the plain proxy and
 * the front end's http, https and HTTP/3 connectors.
 */
public final class HttpConfigurations {

	private HttpConfigurations() {}

	/**
	 * A configuration with the settings every modulo connector shares:
	 *
	 * <ul>
	 * <li>No server version header — not advertising the software/version is
	 * good practice.</li>
	 * <li>Characters such as '|' accepted in request paths. Jetty's default URI
	 * compliance refuses them with "400 Illegal Path Character", but WebObjects
	 * applications have always accepted them — the classic adaptor and Apache
	 * pass the path through as-is — and URLs in the wild carry them. A front end
	 * must not be stricter than the applications behind it, or those URLs fail
	 * at the proxy before the application ever sees them. Same relaxation as
	 * wo-adaptor-jetty's.</li>
	 * </ul>
	 */
	public static HttpConfiguration create() {
		final HttpConfiguration config = new HttpConfiguration();
		config.setSendServerVersion( false );
		config.setUriCompliance( UriCompliance.DEFAULT.with( "WebObjects", UriCompliance.Violation.ILLEGAL_PATH_CHARACTERS ) );
		return config;
	}
}
