package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import modulo.error.ErrorHandling;
import modulo.frontend.events.EventLog;
import modulo.stats.RequestStats;

/**
 * The origin headers as an application actually receives them through the
 * proxy: a client spoofing every one of them, an upstream echoing back what
 * arrived.
 */
public class OriginHeadersProxyTest {

	private Server _upstream;
	private Server _proxy;
	private int _proxyPort;

	@BeforeEach
	void start() throws Exception {
		_upstream = new Server();
		final ServerConnector upstreamConnector = new ServerConnector( _upstream );
		_upstream.addConnector( upstreamConnector );
		_upstream.setHandler( new Handler.Abstract() {
			@Override
			public boolean handle( final Request request, final Response response, final Callback callback ) {
				final StringBuilder echo = new StringBuilder();
				for( final HttpField field : request.getHeaders() ) {
					echo.append( field.getName().toLowerCase( Locale.ROOT ) ).append( ": " ).append( field.getValue() ).append( '\n' );
				}
				response.write( true, java.nio.ByteBuffer.wrap( echo.toString().getBytes( StandardCharsets.UTF_8 ) ), callback );
				return true;
			}
		} );
		_upstream.start();
		final int upstreamPort = upstreamConnector.getLocalPort();

		_proxy = new Server();
		final ServerConnector proxyConnector = new ServerConnector( _proxy );
		_proxy.addConnector( proxyConnector );
		_proxy.setHandler( new ModuloProxy( request -> HttpURI.build( "http://127.0.0.1:" + upstreamPort + request.getHttpURI().getPathQuery() ), ErrorHandling.withDefaults(), new EventLog( 10 ), null, new RequestStats(), TrustedProxies.NONE ) );
		_proxy.start();
		_proxyPort = proxyConnector.getLocalPort();
	}

	@AfterEach
	void stop() throws Exception {
		_proxy.stop();
		_upstream.stop();
	}

	/** Sends a raw HTTP/1.1 request (raw, so header names such as remote_addr go exactly as written) and returns the echoed headers, every value of each. */
	private Map<String, List<String>> send( final String... headerLines ) throws Exception {
		try( Socket socket = new Socket( "127.0.0.1", _proxyPort )) {
			final StringBuilder request = new StringBuilder( "GET /page HTTP/1.1\r\nHost: www.example.com\r\nConnection: close\r\n" );
			for( final String line : headerLines ) {
				request.append( line ).append( "\r\n" );
			}
			request.append( "\r\n" );
			final OutputStream out = socket.getOutputStream();
			out.write( request.toString().getBytes( StandardCharsets.ISO_8859_1 ) );
			out.flush();

			final BufferedReader in = new BufferedReader( new InputStreamReader( socket.getInputStream(), StandardCharsets.UTF_8 ) );
			String line;
			while( (line = in.readLine()) != null && !line.isEmpty() ) {
				// response head
			}
			final Map<String, List<String>> echoed = new LinkedHashMap<>();
			while( (line = in.readLine()) != null ) {
				final int colon = line.indexOf( ": " );
				if( colon > 0 ) {
					echoed.computeIfAbsent( line.substring( 0, colon ), k -> new ArrayList<>() ).add( line.substring( colon + 2 ) );
				}
			}
			return echoed;
		}
	}

	@Test
	void spoofedOriginNeverReachesTheApplication() throws Exception {
		final List<String> spoofed = new ArrayList<>();
		for( final String name : OriginHeaders.ORIGIN_HEADERS ) {
			spoofed.add( name + ": 6.6.6.6" );
		}
		final Map<String, List<String>> echoed = send( spoofed.toArray( String[]::new ) );

		for( final Map.Entry<String, List<String>> header : echoed.entrySet() ) {
			assertFalse( header.getValue().stream().anyMatch( v -> v.contains( "6.6.6.6" ) ), header.toString() );
		}

		// each written exactly once, from the connection (the test client is on loopback, and nothing is trusted)
		for( final String name : List.of( "x-forwarded-for", "x-real-ip", "remote_addr", "remote_host", "x-webobjects-remote-addr" ) ) {
			assertEquals( List.of( "127.0.0.1" ), echoed.get( name ), name );
		}
		assertEquals( List.of( "www.example.com" ), echoed.get( "host" ) );
		assertEquals( List.of( "www.example.com" ), echoed.get( "x-forwarded-host" ) );
		assertEquals( List.of( "www.example.com" ), echoed.get( "x-webobjects-server-name" ) );
		assertEquals( List.of( "http" ), echoed.get( "x-forwarded-proto" ) );
		assertEquals( List.of( "80" ), echoed.get( "x-forwarded-port" ) );
		assertEquals( List.of( "80" ), echoed.get( "x-webobjects-server-port" ) );
		assertEquals( List.of( "for=127.0.0.1;proto=http;host=www.example.com" ), echoed.get( "forwarded" ) );
		assertEquals( List.of( "Modulo" ), echoed.get( "x-webobjects-adaptor-version" ) );
		assertEquals( null, echoed.get( "https" ) );
		assertEquals( null, echoed.get( "x-webobjects-servlet-server-name" ) );
		assertEquals( null, echoed.get( "remote_user" ) );
	}

	@Test
	void otherHeadersPassThrough() throws Exception {
		final Map<String, List<String>> echoed = send( "Accept-Language: is", "Cookie: wosid=abc" );
		assertEquals( List.of( "is" ), echoed.get( "accept-language" ) );
		assertEquals( List.of( "wosid=abc" ), echoed.get( "cookie" ) );
	}
}
