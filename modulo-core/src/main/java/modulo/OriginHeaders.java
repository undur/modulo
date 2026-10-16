package modulo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.HostPort;

/**
 * What modulo tells an application about where a request came from: the
 * client's address, the host it asked for, and the scheme and port it used.
 *
 * Applications of every generation read this from different headers: plain
 * WebObjects from mod_WebObjects' ({@code remote_addr},
 * {@code x-webobjects-*}, {@code https}), Project Wonder and wonder-slim
 * from {@code x-forwarded-*} first and the adaptor's after, and most other
 * software from {@code x-forwarded-*}, {@code x-real-ip} or RFC 7239's
 * {@code Forwarded}. Many of them take the first header they find, and a
 * client can send any of them. So modulo resolves the origin once, removes
 * every header in {@link #ORIGIN_HEADERS} the client sent, and writes the
 * same resolved values into all of them (#14).
 *
 * The origin comes from the connection itself, unless the connection is
 * from a {@link TrustedProxies trusted proxy}; only then are its
 * X-Forwarded-* headers believed.
 */
final class OriginHeaders {

	/**
	 * Every request header that carries a claim about the request's origin,
	 * lowercase. A client's copies of these never reach the application: they
	 * are removed before modulo writes its own, including the ones modulo
	 * doesn't write (an application reading one of those, such as
	 * {@code x-webobjects-servlet-server-name}, which wonder-slim reads before
	 * any other host header, would otherwise take the client's word for it).
	 */
	static final Set<String> ORIGIN_HEADERS = Set.of(
			// the standard and de facto standard proxy headers
			"forwarded", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-forwarded-port", "x-forwarded-server", "x-real-ip",
			// mod_WebObjects' dialect
			"remote_addr", "remote_host", "x-webobjects-remote-addr", "x-webobjects-server-name", "x-webobjects-server-port", "https",
			// read by Wonder or WebObjects as the origin, set by no front end modulo stands in for
			"x-webobjects-servlet-server-name", "x-webobjects-servlet-server-port", "server_name", "server_port", "http_host", "pc-remote-addr",
			// an authenticated user's name, which old Wonder read as an address
			"remote_user",
			// identifies the front end; its mere presence switches WebObjects to "behind a web server" behaviour
			"x-webobjects-adaptor-version" );

	/**
	 * A request's origin, as modulo resolved it.
	 *
	 * @param clientAddress The client's IP address
	 * @param scheme "https" or "http", as the client used it
	 * @param authority The host the client asked for, with the port if it gave one
	 * @param hostname The authority without its port
	 * @param port The port the client connected to
	 */
	record Origin( String clientAddress, String scheme, String authority, String hostname, int port ) {

		boolean isSecure() {
			return "https".equals( scheme );
		}
	}

	private OriginHeaders() {}

	static Origin resolve( final Request request, final TrustedProxies trustedProxies ) {
		String authority = request.getHttpURI().getAuthority();
		if( authority == null || authority.isEmpty() ) {
			authority = Request.getServerName( request ) + ":" + Request.getServerPort( request );
		}
		return resolve( Request.getRemoteAddr( request ), request.isSecure(), authority, request.getHeaders(), trustedProxies );
	}

	/**
	 * @param connectionAddress The address the connection comes from
	 * @param secure Whether the connection is TLS
	 * @param authority The authority the request carries (HTTP/2's :authority, or the Host header)
	 * @param headers The request's headers as the client sent them
	 */
	static Origin resolve( final String connectionAddress, final boolean secure, final String authority, final HttpFields headers, final TrustedProxies trustedProxies ) {
		String clientAddress = connectionAddress;
		String scheme = secure ? "https" : "http";
		String resolvedAuthority = authority;
		Integer forwardedPort = null;

		if( trustedProxies.contains( connectionAddress ) ) {
			clientAddress = clientFromForwardedFor( headers, connectionAddress, trustedProxies );

			final String proto = lastValue( headers, "x-forwarded-proto" );
			if( "https".equalsIgnoreCase( proto ) || "http".equalsIgnoreCase( proto ) ) {
				scheme = proto.toLowerCase( Locale.ROOT );
			}

			final String host = lastValue( headers, "x-forwarded-host" );
			if( host != null && !host.isEmpty() ) {
				resolvedAuthority = host;
			}

			forwardedPort = parsePort( lastValue( headers, "x-forwarded-port" ) );
		}

		final HostPort hostPort = parseAuthority( resolvedAuthority );
		final String hostname = hostPort == null ? resolvedAuthority : hostPort.getHost();
		int port = "https".equals( scheme ) ? 443 : 80;
		if( forwardedPort != null ) {
			port = forwardedPort;
		}
		else if( hostPort != null && hostPort.hasPort() ) {
			port = hostPort.getPort();
		}

		return new Origin( clientAddress, scheme, resolvedAuthority, hostname, port );
	}

	/**
	 * The client behind a trusted proxy: the X-Forwarded-For chain read from
	 * the right (the entries closest to modulo), skipping trusted proxies.
	 * The first untrusted entry is the client; everything left of it is
	 * whatever that client claimed, so it is never believed.
	 */
	private static String clientFromForwardedFor( final HttpFields headers, final String connectionAddress, final TrustedProxies trustedProxies ) {
		final List<String> chain = new ArrayList<>();
		for( final String value : headers.getValuesList( "x-forwarded-for" ) ) {
			for( final String entry : value.split( "," ) ) {
				if( !entry.isBlank() ) {
					chain.add( entry.trim() );
				}
			}
		}

		if( chain.isEmpty() ) {
			return connectionAddress;
		}

		for( int i = chain.size() - 1; i >= 0; i-- ) {
			if( !trustedProxies.contains( chain.get( i ) ) ) {
				return chain.get( i );
			}
		}

		// every hop is a trusted proxy: the leftmost is as close to the client as anyone saw
		return chain.getFirst();
	}

	/** The last comma-separated value of a header, the one the nearest proxy added. */
	private static String lastValue( final HttpFields headers, final String name ) {
		final List<String> values = headers.getValuesList( name );
		if( values.isEmpty() ) {
			return null;
		}
		final String[] parts = values.getLast().split( "," );
		return parts[parts.length - 1].trim();
	}

	private static Integer parsePort( final String value ) {
		if( value == null ) {
			return null;
		}
		try {
			final int port = Integer.parseInt( value );
			return port > 0 && port <= 65535 ? port : null;
		}
		catch( final NumberFormatException e ) {
			return null;
		}
	}

	private static HostPort parseAuthority( final String authority ) {
		try {
			return new HostPort( authority );
		}
		catch( final IllegalArgumentException e ) {
			return null;
		}
	}

	/**
	 * @return The headers to set on the request to the application, in
	 *         order, names as written on the wire. Every name except
	 *         {@code Host} is in {@link #ORIGIN_HEADERS}.
	 */
	static Map<String, String> headers( final Origin origin ) {
		final String client = origin.clientAddress();
		final String port = String.valueOf( origin.port() );

		final Map<String, String> headers = new LinkedHashMap<>();

		// Host stays the host the client asked for (Apache's ProxyPreserveHost),
		// since WebObjects and Wonder build complete URLs from it (see #7)
		headers.put( "Host", origin.authority() );

		headers.put( "X-Forwarded-For", client );
		headers.put( "X-Forwarded-Proto", origin.scheme() );
		headers.put( "X-Forwarded-Host", origin.authority() );
		headers.put( "X-Forwarded-Port", port );
		headers.put( "X-Real-IP", client );
		headers.put( "Forwarded", "for=%s;proto=%s;host=%s".formatted( forwardedNode( client ), origin.scheme(), forwardedValue( origin.authority() ) ) );

		// mod_WebObjects' names. We don't do reverse DNS (Apache's
		// HostnameLookups Off default), so remote_host carries the address
		headers.put( "remote_addr", client );
		headers.put( "remote_host", client );
		headers.put( "x-webobjects-remote-addr", client );
		headers.put( "x-webobjects-server-name", origin.hostname() );
		headers.put( "x-webobjects-server-port", port );
		if( origin.isSecure() ) {
			headers.put( "https", "on" );
		}

		// mod_WebObjects sends "Apache" here. WebObjects treats the header's
		// presence as "behind a web server", which changes how it builds URLs,
		// so it's sent on every request, as it always has been
		headers.put( "x-webobjects-adaptor-version", "Modulo" );

		return headers;
	}

	/** An address as an RFC 7239 node: IPv6 bracketed and quoted. */
	private static String forwardedNode( final String address ) {
		if( address.indexOf( ':' ) >= 0 ) {
			return "\"" + (address.startsWith( "[" ) ? address : "[" + address + "]") + "\"";
		}
		return forwardedValue( address );
	}

	/** An RFC 7239 value: a token as is, anything else (such as host:port) quoted. */
	private static String forwardedValue( final String value ) {
		for( int i = 0; i < value.length(); i++ ) {
			final char c = value.charAt( i );
			if( !(Character.isLetterOrDigit( c ) || "!#$%&'*+-.^_`|~".indexOf( c ) >= 0) ) {
				return "\"" + value.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) + "\"";
			}
		}
		return value;
	}
}
