package modulo;

import java.util.UUID;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpURI.Mutable;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.AdaptorConfigParser.AdaptorConfig;
import modulo.AdaptorConfigParser.Instance;

/**
 * A jetty-based reverse proxy, meant to potentially replace mod_WebObjects
 */

public class Modulo {

	private static Logger logger = LoggerFactory.getLogger( Modulo.class );

	/**
	 * The port to run the proxy on
	 */
	private static final int PORT = 1400;

	public static void main( String[] argv ) {
		logger.info( "Starting modulo" );
		final Server server = new Server();

		final ServerConnector connector = new ServerConnector( server );
		connector.setPort( PORT );
		server.addConnector( connector );
		server.setHandler( new ModuloProxy( rewriteURIFunction() ) );

		logger.info( "Modulo started" );

		try {
			server.start();
		}
		catch( final Exception e ) {
			e.printStackTrace();
			System.exit( -1 );
		}
	}

	/**
	 * Subclassing Jetty's own proxy handler, allowing us to make any modifications we need to the request before forwarding it
	 */
	private static class ModuloProxy extends ProxyHandler.Reverse {

		public ModuloProxy( Function<Request, HttpURI> httpURIRewriter ) {
			super( httpURIRewriter );
		}

		@Override
		protected void addProxyHeaders( Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest ) {
			super.addProxyHeaders( clientToProxyRequest, proxyToServerRequest );

			logger.info( "Received headers: " + clientToProxyRequest.getHeaders() );

			proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-adaptor-version", "Modulo" ) );
			proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-request-id", UUID.randomUUID().toString() ) );
			proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-request-method", clientToProxyRequest.getMethod() ) );
		}
	}

	/**
	 * FIXME: This should most definitely not be static, and should be regularly updated
	 */
	private static AdaptorConfig adaptorConfig = AdaptorConfigParser.adaptorConfig();

	/**
	 * Function that rewrites the incoming URI to the target URI
	 */
	public static Function<Request, HttpURI> rewriteURIFunction() {
		return request -> {
			final HttpURI originalURI = request.getHttpURI();

			final String[] splitPath = originalURI.getPath().split( "/" );
			String applicationName = splitPath[3];

			// Remove the .woa from the application name
			applicationName = applicationName.split( "\\." )[0];

			// FIXME: We're hardcoding targeting the first instance for testing
			final Instance targetInstance = adaptorConfig.application( applicationName ).instances().getFirst();

			final String hostName = targetInstance.host();
			final int port = targetInstance.port();

			final Mutable targetURI = HttpURI
					.build( originalURI )
					.host( hostName )
					.scheme( HttpScheme.HTTP )
					.port( port );

			logger.info( "Rewrote %s -> %s".formatted( originalURI, targetURI ) );

			return targetURI;
		};
	}
}