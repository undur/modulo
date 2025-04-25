package modulo;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a fake application instance that we can use to target for some testing and logging
 */

public class FakeApplicationInstance {

	private static final Logger logger = LoggerFactory.getLogger( FakeApplicationInstance.class );

	/**
	 * Globally keep track of the request number coming in
	 */
	private static final AtomicLong requestNumber = new AtomicLong();

	public static void start( int port ) {

		logger.info( "Starting fake application instance for logging" );

		final Server server = new Server();
		final ServerConnector connector = new ServerConnector( server );
		connector.setPort( port );
		server.addConnector( connector );
		server.setHandler( new FakeHandler() );

		try {
			server.start();
		}
		catch( final Exception e ) {
			logger.info( "Modulo startup failed" );
			e.printStackTrace();
			System.exit( -1 );
		}
	}

	public static class FakeHandler extends Handler.Abstract {

		@Override
		public boolean handle( Request request, Response response, Callback callback ) throws Exception {

			boolean skipHeaderLogging = !request.getHttpURI().toString().contains( "skipHeaderLogging" );

			if( skipHeaderLogging ) {
				logger.info( "========== REQUEST =========" );
				logger.info( "uri: {}", request.getHttpURI() );
				logger.info( "headers: {}", request.getHeaders() );
				System.out.println();
				System.out.println();
			}

			String responseString = "Serving request: " + requestNumber.getAndIncrement();
			System.out.println( responseString );

			final byte[] responseBytes = responseString.getBytes();
			response.getHeaders().add( "content-length", responseBytes.length );

			Content.copy( Content.Source.from( new ByteArrayInputStream( responseBytes ) ), response, callback );

			callback.succeeded();

			return true;
		}
	}
}