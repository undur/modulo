package modulo;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The proxies whose claims about a request's origin modulo believes: the
 * connections allowed to tell modulo, through X-Forwarded-* headers, which
 * client they are relaying for. A request from anywhere else is its own
 * origin, and whatever forwarding headers it carries are discarded (#14).
 *
 * Entries are IP addresses or CIDR ranges, IPv4 or IPv6. Only literals are
 * accepted, never hostnames, so checking an address never involves DNS.
 */
public final class TrustedProxies {

	/**
	 * The default: only this machine. modulo running behind a web server on
	 * the same host (the plain connector) gets the client's address from that
	 * server, and a front-facing modulo is unaffected, since no client
	 * connects over loopback.
	 */
	public static final TrustedProxies LOOPBACK = parse( List.of( "127.0.0.0/8", "::1" ) );

	/** Trusts no one: every request is its own origin. */
	public static final TrustedProxies NONE = parse( List.of() );

	private record Range( byte[] network, int prefixLength ) {

		boolean contains( final byte[] address ) {
			if( address.length != network.length ) {
				return false;
			}
			final int fullBytes = prefixLength / 8;
			for( int i = 0; i < fullBytes; i++ ) {
				if( address[i] != network[i] ) {
					return false;
				}
			}
			final int remainingBits = prefixLength % 8;
			if( remainingBits == 0 ) {
				return true;
			}
			final int mask = 0xFF << (8 - remainingBits) & 0xFF;
			return (address[fullBytes] & mask) == (network[fullBytes] & mask);
		}
	}

	private final List<String> _entries;
	private final List<Range> _ranges;

	private TrustedProxies( final List<String> entries, final List<Range> ranges ) {
		_entries = List.copyOf( entries );
		_ranges = List.copyOf( ranges );
	}

	/**
	 * @param entries Addresses ("10.0.0.5", "::1") and CIDR ranges ("10.0.0.0/8", "2001:db8::/32")
	 * @throws IllegalArgumentException naming the first entry that is neither
	 */
	public static TrustedProxies parse( final List<String> entries ) {
		Objects.requireNonNull( entries );
		final List<Range> ranges = new ArrayList<>();
		for( final String entry : entries ) {
			ranges.add( parseRange( entry ) );
		}
		return new TrustedProxies( entries, ranges );
	}

	private static Range parseRange( final String entry ) {
		final String trimmed = entry == null ? "" : entry.trim();
		final int slash = trimmed.indexOf( '/' );
		final byte[] network = literalBytes( slash < 0 ? trimmed : trimmed.substring( 0, slash ) );
		if( network == null ) {
			throw new IllegalArgumentException( "\"%s\" is not an IP address or CIDR range".formatted( entry ) );
		}
		final int maxPrefix = network.length * 8;
		int prefixLength = maxPrefix;
		if( slash >= 0 ) {
			try {
				prefixLength = Integer.parseInt( trimmed.substring( slash + 1 ) );
			}
			catch( final NumberFormatException e ) {
				prefixLength = -1;
			}
			if( prefixLength < 0 || prefixLength > maxPrefix ) {
				throw new IllegalArgumentException( "\"%s\" has an invalid prefix length (0–%d)".formatted( entry, maxPrefix ) );
			}
		}
		return new Range( network, prefixLength );
	}

	/**
	 * @return The address's bytes (4 for IPv4, including IPv4-mapped IPv6;
	 *         16 for IPv6), or null if [address] isn't an IP literal. Accepts
	 *         IPv6 in brackets, as Jetty and the Forwarded header write it.
	 */
	static byte[] literalBytes( final String address ) {
		if( address == null || address.isBlank() ) {
			return null;
		}
		String literal = address.trim();
		if( literal.startsWith( "[" ) && literal.endsWith( "]" ) ) {
			literal = literal.substring( 1, literal.length() - 1 );
		}
		try {
			return InetAddress.ofLiteral( literal ).getAddress();
		}
		catch( final IllegalArgumentException e ) {
			return null;
		}
	}

	/**
	 * @return true if [address] is an IP literal inside one of the trusted
	 *         ranges. Anything that isn't an IP literal is never trusted.
	 */
	public boolean contains( final String address ) {
		final byte[] bytes = literalBytes( address );
		if( bytes == null ) {
			return false;
		}
		for( final Range range : _ranges ) {
			if( range.contains( bytes ) ) {
				return true;
			}
		}
		return false;
	}

	/** The entries as configured, for display. */
	public List<String> entries() {
		return _entries;
	}

	@Override
	public String toString() {
		return _entries.isEmpty() ? "none" : String.join( ", ", _entries );
	}
}
