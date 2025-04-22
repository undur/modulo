package modulo;

import java.util.UUID;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpURI.Mutable;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
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
	 * Subclassing Jetty's own proxy handler, allowing us to make any further modifications to the proxied requests
	 */
	private static class ModuloProxy extends ProxyHandler.Reverse {

		public ModuloProxy( Function<Request, HttpURI> httpURIRewriter ) {
			super( httpURIRewriter );
		}

		@Override
		protected void addProxyHeaders( Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest ) {
			super.addProxyHeaders( clientToProxyRequest, proxyToServerRequest );

			//			proxyToServerRequest.headers( headers -> headers.computeField( "some-header", ( header, viaFields ) -> {
			//				return new HttpField( "some-header", "some-value" );
			//			} ) );

			proxyToServerRequest.headers( headers -> headers.computeField( "x-webobjects-adaptor-version", ( header, viaFields ) -> {
				return new HttpField( "x-webobjects-adaptor-version", "Modulo" );
			} ) );

			proxyToServerRequest.headers( headers -> headers.computeField( "x-webobjects-request-id", ( header, viaFields ) -> {
				return new HttpField( "x-webobjects-request-id", UUID.randomUUID().toString() );
			} ) );

			proxyToServerRequest.headers( headers -> headers.computeField( "x-webobjects-request-method", ( header, viaFields ) -> {
				return new HttpField( "x-webobjects-request-method", clientToProxyRequest.getMethod() );
			} ) );

			// x-webobjects-adaptor-version
			// x-webobjects-request-id
			// x-webobjects-request-method

			// Example request headers from a request proxied by the Apache adaptor
			// {Accept=[text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8], Accept-Encoding=[gzip, deflate, br, zstd], Accept-Language=[en-GB, en-US;q=0.7, en;q=0.3], connection=[close], DNT=[1], DOCUMENT_ROOT=[/rebbi/com.fermentedshark/html], Host=[www.fermentedshark.com], HTTPS=[on], mod_rewrite_rewritten=[1], Priority=[u=0, i], Referer=[https://www.fermentedshark.com/], REMOTE_ADDR=[31.209.136.250], REMOTE_HOST=[31.209.136.250], REMOTE_PORT=[49578], SCRIPT_FILENAME=[/Apps], SCRIPT_URI=[https://www.fermentedshark.com/page/templating], SCRIPT_URL=[/page/templating], Sec-Fetch-Dest=[document], Sec-Fetch-Mode=[navigate], Sec-Fetch-Site=[same-origin], Sec-Fetch-User=[?1], SERVER_ADMIN=[root@localhost], SERVER_NAME=[www.fermentedshark.com], SERVER_PORT=[443], SERVER_SOFTWARE=[Apache/2.4.6 (CentOS) OpenSSL/1.0.2k-fips], ssl-secure-reneg=[1], SSL_TLS_SNI=[www.fermentedshark.com], UNIQUE_ID=[aAdE0G3LH-oETpsNLF2qBwAAAAw], Upgrade-Insecure-Requests=[1], User-Agent=[Mozilla/5.0 (Macintosh;Intel Mac OS X 10.15;rv:139.0) Gecko/20100101 Firefox/139.0], x-webobjects-adaptor-version=[Apache], x-webobjects-request-id=[680605a00000534c00000a82], x-webobjects-request-method=[GET]}
		}
	}

	/**
	 * Function that rewrites the incoming URI to the target URI
	 */
	public static Function<Request, HttpURI> rewriteURIFunction() {
		return request -> {
			final HttpURI originalURI = request.getHttpURI();

			final Mutable targetURI = HttpURI
					.build( originalURI )
					.host( "linode-4.rebbi.is" )
					.scheme( HttpScheme.HTTP )
					.port( 2011 );

			logger.info( "Rewrote %s -> %s".formatted( originalURI, targetURI ) );

			return targetURI;
		};
	}
}