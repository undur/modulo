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
	public void stillRefusesAmbiguousPaths() throws Exception {
		// Only illegal path characters are relaxed, not the ambiguity checks that protect routing
		final String response = rawGet( HttpConfigurations.create(), "/a/%2e%2e/b" );
		assertTrue( response.startsWith( "HTTP/1.1 400" ), response );
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
