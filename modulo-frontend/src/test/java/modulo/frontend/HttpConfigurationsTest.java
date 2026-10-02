package modulo.frontend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

public class HttpConfigurationsTest {

	@Test
	public void acceptsPipeInPath() throws Exception {
		final String response = rawGet( HttpConfigurations.create(), "/wa/Action/a|b" );
		assertTrue( response.startsWith( "HTTP/1.1 200" ), response );
		assertTrue( response.contains( "path=/wa/Action/a|b" ), response );
	}

	@Test
	public void jettyDefaultStillRefusesIt() throws Exception {
		// Guards the premise: if Jetty ever relaxes its default, the override becomes removable
		final String response = rawGet( new HttpConfiguration(), "/wa/Action/a|b" );
		assertTrue( response.startsWith( "HTTP/1.1 400" ), response );
	}

	@Test
	public void acceptsAnEncodedSlashAndKeepsItEncoded() throws Exception {
		final String response = rawGet( HttpConfigurations.create(), "/document/2008-10-01+Etc%2FGMT.jpg" );
		assertTrue( response.startsWith( "HTTP/1.1 200" ), response );
		assertTrue( response.contains( "path=/document/2008-10-01+Etc%2FGMT.jpg" ), response );
	}

	@Test
	public void stillRefusesEncodedDotSegments() throws Exception {
		// Encoded dot segments would change the path's structure once decoded; those stay refused
		for( final String path : java.util.List.of( "/a/%2e%2e/b", "/a/%2E%2E/etc", "/a/%2e/b" ) ) {
			final String response = rawGet( HttpConfigurations.create(), path );
			assertTrue( response.startsWith( "HTTP/1.1 400" ), path + ": " + response );
		}
	}

	private static String rawGet( final HttpConfiguration config, final String path ) throws Exception {
		final Server server = new Server();
		final ServerConnector connector = new ServerConnector( server, new HttpConnectionFactory( config ) );
		connector.setPort( 0 );
		server.addConnector( connector );
		server.setHandler( new Handler.Abstract() {
			@Override
			public boolean handle( final Request request, final Response response, final Callback callback ) {
				response.setStatus( 200 );
				Content.Sink.write( response, true, "path=" + request.getHttpURI().getPath(), callback );
				return true;
			}
		} );
		server.start();

		try( Socket socket = new Socket( "localhost", connector.getLocalPort() ) ) {
			final OutputStream out = socket.getOutputStream();
			out.write( ("GET " + path + " HTTP/1.1\r\nHost: test\r\nConnection: close\r\n\r\n").getBytes( StandardCharsets.ISO_8859_1 ) );
			out.flush();
			final InputStream in = socket.getInputStream();
			return new String( in.readAllBytes(), StandardCharsets.ISO_8859_1 );
		}
		finally {
			server.stop();
		}
	}
}
