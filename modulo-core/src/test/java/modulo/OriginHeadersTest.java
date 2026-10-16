package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.junit.jupiter.api.Test;

import modulo.OriginHeaders.Origin;

public class OriginHeadersTest {

	private static final String CLIENT = "203.0.113.7";

	/** Every spoofable origin claim a client could send, with a value the application must never see. */
	private static HttpFields.Mutable spoofed() {
		final HttpFields.Mutable headers = HttpFields.build();
		for( final String name : OriginHeaders.ORIGIN_HEADERS ) {
			headers.add( name, "6.6.6.6" );
		}
		return headers;
	}

	@Test
	public void frontFacingUsesTheConnectionAndIgnoresClaims() {
		final Origin origin = OriginHeaders.resolve( CLIENT, true, "www.example.com", spoofed(), TrustedProxies.LOOPBACK );
		assertEquals( new Origin( CLIENT, "https", "www.example.com", "www.example.com", 443 ), origin );
	}

	@Test
	public void plainHttpAndAnExplicitPort() {
		assertEquals( new Origin( CLIENT, "http", "www.example.com", "www.example.com", 80 ), OriginHeaders.resolve( CLIENT, false, "www.example.com", HttpFields.EMPTY, TrustedProxies.NONE ) );
		assertEquals( new Origin( CLIENT, "https", "localhost:8443", "localhost", 8443 ), OriginHeaders.resolve( CLIENT, true, "localhost:8443", HttpFields.EMPTY, TrustedProxies.NONE ) );
	}

	@Test
	public void trustedProxyIsBelieved() {
		final HttpFields headers = HttpFields.build()
				.add( "X-Forwarded-For", CLIENT )
				.add( "X-Forwarded-Proto", "https" )
				.add( "X-Forwarded-Host", "www.example.com" );
		final Origin origin = OriginHeaders.resolve( "127.0.0.1", false, "localhost:1200", headers, TrustedProxies.LOOPBACK );
		assertEquals( new Origin( CLIENT, "https", "www.example.com", "www.example.com", 443 ), origin );
	}

	@Test
	public void trustedProxyChainIsReadFromTheRight() {
		final TrustedProxies trusted = TrustedProxies.parse( List.of( "10.0.0.0/8", "127.0.0.1" ) );

		// the client claimed 6.6.6.6; the trusted 10.0.0.2 saw CLIENT; 10.0.0.1 relayed to us
		final HttpFields headers = HttpFields.build()
				.add( "X-Forwarded-For", "6.6.6.6, " + CLIENT )
				.add( "X-Forwarded-For", "10.0.0.2" );
		assertEquals( CLIENT, OriginHeaders.resolve( "10.0.0.1", true, "www.example.com", headers, trusted ).clientAddress() );

		// every hop trusted: the leftmost is the best anyone knows
		final HttpFields internal = HttpFields.build().add( "X-Forwarded-For", "10.0.0.9, 10.0.0.2" );
		assertEquals( "10.0.0.9", OriginHeaders.resolve( "10.0.0.1", true, "www.example.com", internal, trusted ).clientAddress() );

		// no header at all: the proxy itself is the client
		assertEquals( "10.0.0.1", OriginHeaders.resolve( "10.0.0.1", true, "www.example.com", HttpFields.EMPTY, trusted ).clientAddress() );
	}

	@Test
	public void forwardedPortAndHostPort() {
		final HttpFields headers = HttpFields.build()
				.add( "X-Forwarded-Proto", "https" )
				.add( "X-Forwarded-Host", "www.example.com:8443" );
		assertEquals( 8443, OriginHeaders.resolve( "127.0.0.1", false, "localhost", headers, TrustedProxies.LOOPBACK ).port() );

		final HttpFields withPort = HttpFields.build( headers ).add( "X-Forwarded-Port", "9443" );
		assertEquals( 9443, OriginHeaders.resolve( "127.0.0.1", false, "localhost", withPort, TrustedProxies.LOOPBACK ).port() );
	}

	@Test
	public void untrustedProtoValuesAreIgnored() {
		final HttpFields headers = HttpFields.build().add( "X-Forwarded-Proto", "gopher" );
		assertEquals( "http", OriginHeaders.resolve( "127.0.0.1", false, "localhost", headers, TrustedProxies.LOOPBACK ).scheme() );
	}

	@Test
	public void headersCarryTheResolvedOriginInEveryDialect() {
		final Map<String, String> headers = OriginHeaders.headers( new Origin( CLIENT, "https", "www.example.com:8443", "www.example.com", 8443 ) );

		assertEquals( "www.example.com:8443", headers.get( "Host" ) );
		for( final String name : List.of( "X-Forwarded-For", "X-Real-IP", "remote_addr", "remote_host", "x-webobjects-remote-addr" ) ) {
			assertEquals( CLIENT, headers.get( name ), name );
		}
		assertEquals( "https", headers.get( "X-Forwarded-Proto" ) );
		assertEquals( "www.example.com:8443", headers.get( "X-Forwarded-Host" ) );
		assertEquals( "8443", headers.get( "X-Forwarded-Port" ) );
		assertEquals( "www.example.com", headers.get( "x-webobjects-server-name" ) );
		assertEquals( "8443", headers.get( "x-webobjects-server-port" ) );
		assertEquals( "on", headers.get( "https" ) );
		assertEquals( "Modulo", headers.get( "x-webobjects-adaptor-version" ) );
		assertEquals( "for=203.0.113.7;proto=https;host=\"www.example.com:8443\"", headers.get( "Forwarded" ) );

		// every header written is one whose client copies are removed
		for( final String name : headers.keySet() ) {
			assertTrue( name.equals( "Host" ) || OriginHeaders.ORIGIN_HEADERS.contains( name.toLowerCase() ), name );
		}
	}

	@Test
	public void plainHttpSendsNoHttpsHeader() {
		assertNull( OriginHeaders.headers( new Origin( CLIENT, "http", "www.example.com", "www.example.com", 80 ) ).get( "https" ) );
	}

	@Test
	public void forwardedQuotesIPv6() {
		final Map<String, String> headers = OriginHeaders.headers( new Origin( "2001:db8::1", "https", "www.example.com", "www.example.com", 443 ) );
		assertEquals( "for=\"[2001:db8::1]\";proto=https;host=www.example.com", headers.get( "Forwarded" ) );
	}

	@Test
	public void webSocketHandshakeCarriesOnlyResolvedOrigin() {
		final HttpFields.Mutable client = spoofed();
		client.add( "Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==" );
		client.add( "Host", "evil.example" );

		final Origin origin = OriginHeaders.resolve( CLIENT, true, "www.example.com", client, TrustedProxies.LOOPBACK );
		final String handshake = new String( WebSocketTunnelHandler.handshakeBytes( HttpURI.from( "http://127.0.0.1:2001/socket" ), client, OriginHeaders.headers( origin ) ), StandardCharsets.ISO_8859_1 );

		assertFalse( handshake.contains( "6.6.6.6" ), handshake );
		assertFalse( handshake.contains( "evil.example" ), handshake );
		assertTrue( handshake.startsWith( "GET /socket HTTP/1.1\r\nHost: www.example.com\r\n" ), handshake );
		assertTrue( handshake.contains( "\r\nX-Forwarded-For: " + CLIENT + "\r\n" ), handshake );
		assertTrue( handshake.contains( "\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" ), handshake );
		assertTrue( handshake.endsWith( "Connection: Upgrade\r\nUpgrade: websocket\r\n\r\n" ), handshake );
	}

	@Test
	public void trustedProxiesMatchAddressesAndRanges() {
		final TrustedProxies trusted = TrustedProxies.parse( List.of( "10.0.0.0/8", "192.168.1.5", "2001:db8::/32", "172.16.0.0/12" ) );
		assertTrue( trusted.contains( "10.255.0.1" ) );
		assertTrue( trusted.contains( "192.168.1.5" ) );
		assertFalse( trusted.contains( "192.168.1.6" ) );
		assertTrue( trusted.contains( "172.31.255.255" ) );
		assertFalse( trusted.contains( "172.32.0.0" ) );
		assertTrue( trusted.contains( "[2001:db8:abcd::1]" ) );
		assertFalse( trusted.contains( "2001:db9::1" ) );
		assertFalse( trusted.contains( "unknown" ) );
		assertFalse( trusted.contains( "www.example.com" ) );
		assertFalse( trusted.contains( null ) );

		assertTrue( TrustedProxies.LOOPBACK.contains( "127.0.0.1" ) );
		assertTrue( TrustedProxies.LOOPBACK.contains( "0:0:0:0:0:0:0:1" ) );
		assertTrue( TrustedProxies.LOOPBACK.contains( "::ffff:127.0.0.1" ) );
		assertFalse( TrustedProxies.LOOPBACK.contains( CLIENT ) );
		assertFalse( TrustedProxies.NONE.contains( "127.0.0.1" ) );
	}

	@Test
	public void trustedProxiesRejectNonLiterals() {
		assertThrows( IllegalArgumentException.class, () -> TrustedProxies.parse( List.of( "proxy.example.com" ) ) );
		assertThrows( IllegalArgumentException.class, () -> TrustedProxies.parse( List.of( "10.0.0.0/33" ) ) );
		assertThrows( IllegalArgumentException.class, () -> TrustedProxies.parse( List.of( "10.0.0.0/x" ) ) );
	}
}
