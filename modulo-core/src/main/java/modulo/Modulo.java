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
 *
 * FIXME: We're currently always targeting the first instance. Here's where a load balacing scheme might come in strong... // Hugi 2025-04-22
 * FIXME: Oh, and we're going to have to target the correct instance based on the request URL // Hugi 2025-04-22
 * FIXME: We might want to gather some statistics and logging // Hugi 2025-04-22
 * FIXME: We're missing configuration options for ... everything // Hugi 2025-04-22
 * FIXME: Adaptor config needs to be regularly updated/manually updatable // Hugi 2025-04-22
 * FIXME: We need a GUI for this thing. ng? // Hugi 2025-04-22
 */

public class Modulo {

	private static Logger logger = LoggerFactory.getLogger( Modulo.class );

	/**
	 * The port to run the proxy on
	 */
	private static final int PORT = 1400;

	/**
	 * FIXME: This should most definitely not be static and should be regularly updated // Hugi 2025-04-22
	 */
	private static AdaptorConfig adaptorConfig = AdaptorConfigParser.adaptorConfig();

	public static void main( String[] argv ) {

		logger.info( "Starting modulo" );

		final Server server = new Server();
		final ServerConnector connector = new ServerConnector( server );
		connector.setPort( PORT );
		server.addConnector( connector );
		server.setHandler( new ModuloProxy( rewriteURIFunction() ) );

		try {
			server.start();
		}
		catch( final Exception e ) {
			logger.info( "Modulo startup failed" );
			e.printStackTrace();
			System.exit( -1 );
		}
	}

	/**
	 * Subclass of Jetty's proxy handler, allows us to make required modifications to the proxied request before forwarding it to the instance
	 */
	private static class ModuloProxy extends ProxyHandler.Reverse {

		public ModuloProxy( Function<Request, HttpURI> httpURIRewriter ) {
			super( httpURIRewriter );
		}

		@Override
		protected void addProxyHeaders( Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest ) {
			super.addProxyHeaders( clientToProxyRequest, proxyToServerRequest );
			proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-adaptor-version", "Modulo" ) );
			proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-request-id", UUID.randomUUID().toString() ) );
			proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-request-method", clientToProxyRequest.getMethod() ) );
		}
	}

	/**
	 * @return A Function that generates the instance URI to target
	 */
	public static Function<Request, HttpURI> rewriteURIFunction() {
		return request -> {
			final HttpURI originalURI = request.getHttpURI();
			final String applicationName = applicationNameFromURI( originalURI );

			// FIXME: We're hardcoding targeting the first instance for testing // Hugi 2025-04-22
			final Instance targetInstance = adaptorConfig.application( applicationName ).instances().getFirst();

			final String hostName = targetInstance.host();
			final int port = targetInstance.port();

			final Mutable targetURI = HttpURI
					.build( originalURI )
					.host( hostName )
					.scheme( HttpScheme.HTTP )
					.port( port );

			logger.info( "Forwarding %s -> %s".formatted( originalURI, targetURI ) );

			return targetURI;
		};
	}

	/**
	 * @return The name of the application from the given URI
	 */
	private static String applicationNameFromURI( final HttpURI uri ) {
		final String[] splitPath = uri.getPath().split( "/" );
		String applicationName = splitPath[3];

		// Remove the .woa from the application name
		applicationName = applicationName.split( "\\." )[0];

		return applicationName;
	}
}