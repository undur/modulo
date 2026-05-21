package modulo.frontend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.frontend.site.Site;
import modulo.frontend.tls.CertStore;

/**
 * Front-facing Jetty server: binds 80 + 443, terminates TLS with SNI via
 * {@link CertStore}, applies redirect / canonical-hostname / ACME-challenge
 * policy, then hands off to a wrapped terminal handler (modulo's proxy).
 *
 * Iteration 1: configuration is supplied directly (list of Sites + ACME
 * webroot). The Apache config reader builds the Site list.
 */
public class JettyFrontend {

	private static final Logger logger = LoggerFactory.getLogger( JettyFrontend.class );

	private final List<Site> sites;
	private final CertStore certStore;
	private final Path acmeWebroot;
	private final int httpPort;
	private final int httpsPort;
	private final Handler terminalHandler;

	private Server server;

	public JettyFrontend(
			final List<Site> sites,
			final CertStore certStore,
			final Path acmeWebroot,
			final int httpPort,
			final int httpsPort,
			final Handler terminalHandler ) {
		this.sites = List.copyOf( sites );
		this.certStore = certStore;
		this.acmeWebroot = acmeWebroot;
		this.httpPort = httpPort;
		this.httpsPort = httpsPort;
		this.terminalHandler = terminalHandler;
	}

	public Server start() throws Exception {
		final QueuedThreadPool threadPool = new QueuedThreadPool();
		threadPool.setMaxThreads( 200 );
		threadPool.setVirtualThreadsExecutor( Executors.newVirtualThreadPerTaskExecutor() );
		server = new Server( threadPool );

		final SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
		sslContextFactory.setKeyStore( certStore.current() );
		sslContextFactory.setKeyStorePassword( new String( certStore.keyStorePassword() ) );

		certStore.onReload( reloadedStore -> {
			try {
				sslContextFactory.reload( factory -> factory.setKeyStore( reloadedStore ) );
				logger.info( "SslContextFactory reloaded with refreshed keystore" );
			}
			catch( final Exception e ) {
				logger.warn( "SslContextFactory reload failed: {}", e.toString() );
			}
		} );

		final Map<String, Site> sitesByHost = buildHostMap( sites );
		final Handler chain = buildHandlerChain( sitesByHost );
		server.setHandler( chain );

		server.addConnector( buildHttpConnector() );
		server.addConnector( buildHttpsConnector( sslContextFactory ) );

		server.start();
		certStore.startWatching();
		return server;
	}

	public void stop() throws Exception {
		certStore.stopWatching();
		if( server != null ) {
			server.stop();
		}
	}

	private ServerConnector buildHttpConnector() {
		final HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.setSendServerVersion( false );
		final HttpConnectionFactory httpFactory = new HttpConnectionFactory( httpConfig );
		final ServerConnector connector = new ServerConnector( server, httpFactory );
		connector.setPort( httpPort );
		return connector;
	}

	private ServerConnector buildHttpsConnector( final SslContextFactory.Server sslContextFactory ) {
		final HttpConfiguration httpsConfig = new HttpConfiguration();
		httpsConfig.setSendServerVersion( false );
		httpsConfig.setSecureScheme( "https" );
		httpsConfig.setSecurePort( httpsPort );
		httpsConfig.addCustomizer( new SecureRequestCustomizer() );

		final HttpConnectionFactory http1 = new HttpConnectionFactory( httpsConfig );
		final HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory( httpsConfig );
		final ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
		alpn.setDefaultProtocol( http1.getProtocol() );
		final SslConnectionFactory ssl = new SslConnectionFactory( sslContextFactory, alpn.getProtocol() );

		final ServerConnector connector = new ServerConnector( server, ssl, alpn, http2, http1 );
		connector.setPort( httpsPort );
		return connector;
	}

	private static Map<String, Site> buildHostMap( final List<Site> sites ) {
		final Map<String, Site> out = new HashMap<>();
		for( final Site site : sites ) {
			for( final String host : site.allHostnames() ) {
				out.put( host.toLowerCase(), site );
			}
		}
		return out;
	}

	private Handler buildHandlerChain( final Map<String, Site> sitesByHost ) {
		// Order matters: ACME challenge first (must respond on plain HTTP), then
		// HTTP→HTTPS redirect, then canonical-hostname redirect, then the proxy.
		return new AcmeChallengeHandler( acmeWebroot,
				new HttpsRedirectHandler( sitesByHost, httpsPort,
						new CanonicalRedirectHandler( sitesByHost,
								terminalHandler ) ) );
	}

	private static String hostOf( final Request request ) {
		final String h = Request.getServerName( request );
		return h == null ? null : h.toLowerCase();
	}

	/**
	 * Serves {@code /.well-known/acme-challenge/*} from {@link #webroot} on
	 * plain HTTP so certbot's HTTP-01 challenge keeps working. Everything else
	 * is delegated.
	 */
	private static class AcmeChallengeHandler extends Handler.Wrapper {

		private static final String CHALLENGE_PREFIX = "/.well-known/acme-challenge/";

		private final Path webroot;

		AcmeChallengeHandler( final Path webroot, final Handler next ) {
			super( next );
			this.webroot = webroot;
		}

		@Override
		public boolean handle( final Request request, final Response response, final Callback callback ) throws Exception {
			final String path = request.getHttpURI().getPath();
			if( webroot == null || !path.startsWith( CHALLENGE_PREFIX ) ) {
				return super.handle( request, response, callback );
			}
			final String token = path.substring( CHALLENGE_PREFIX.length() );
			if( token.isEmpty() || token.contains( "/" ) || token.contains( ".." ) ) {
				Response.writeError( request, response, callback, HttpStatus.NOT_FOUND_404 );
				return true;
			}
			final Path file = webroot.resolve( CHALLENGE_PREFIX.substring( 1 ) ).resolve( token );
			if( !Files.isRegularFile( file ) ) {
				Response.writeError( request, response, callback, HttpStatus.NOT_FOUND_404 );
				return true;
			}
			final byte[] body = Files.readAllBytes( file );
			response.getHeaders().put( HttpHeader.CONTENT_TYPE, "text/plain" );
			response.getHeaders().put( HttpHeader.CONTENT_LENGTH, String.valueOf( body.length ) );
			response.write( true, java.nio.ByteBuffer.wrap( body ), callback );
			return true;
		}
	}

	/**
	 * If the request arrived over plain HTTP and the host belongs to a Site
	 * with httpsRedirect=true, send a 301 to the HTTPS equivalent. Otherwise
	 * delegate.
	 */
	private static class HttpsRedirectHandler extends Handler.Wrapper {

		private final Map<String, Site> sitesByHost;
		private final int httpsPort;

		HttpsRedirectHandler( final Map<String, Site> sitesByHost, final int httpsPort, final Handler next ) {
			super( next );
			this.sitesByHost = sitesByHost;
			this.httpsPort = httpsPort;
		}

		@Override
		public boolean handle( final Request request, final Response response, final Callback callback ) throws Exception {
			if( request.isSecure() ) {
				return super.handle( request, response, callback );
			}
			final String host = hostOf( request );
			final Site site = host == null ? null : sitesByHost.get( host );
			if( site == null || !site.httpsRedirect() ) {
				return super.handle( request, response, callback );
			}
			final String location = "https://" + host + (httpsPort == 443 ? "" : ":" + httpsPort) + request.getHttpURI().getPathQuery();
			sendRedirect( response, callback, location );
			return true;
		}
	}

	/**
	 * If the request hostname matches an alias rather than the primary
	 * hostname of a Site with canonicalRedirect=true, send a 301 to the
	 * primary hostname (preserving scheme + port + path).
	 */
	private static class CanonicalRedirectHandler extends Handler.Wrapper {

		private final Map<String, Site> sitesByHost;

		CanonicalRedirectHandler( final Map<String, Site> sitesByHost, final Handler next ) {
			super( next );
			this.sitesByHost = sitesByHost;
		}

		@Override
		public boolean handle( final Request request, final Response response, final Callback callback ) throws Exception {
			final String host = hostOf( request );
			final Site site = host == null ? null : sitesByHost.get( host );
			if( site == null || !site.canonicalRedirect() || site.primaryHostname().equalsIgnoreCase( host ) ) {
				return super.handle( request, response, callback );
			}
			final boolean secure = request.isSecure();
			final String scheme = secure ? "https" : "http";
			final int port = Request.getServerPort( request );
			final boolean defaultPort = (secure && port == 443) || (!secure && port == 80) || port <= 0;
			final String portPart = defaultPort ? "" : ":" + port;
			final String location = scheme + "://" + site.primaryHostname() + portPart + request.getHttpURI().getPathQuery();
			sendRedirect( response, callback, location );
			return true;
		}
	}

	private static void sendRedirect( final Response response, final Callback callback, final String location ) {
		response.setStatus( HttpStatus.MOVED_PERMANENTLY_301 );
		final HttpFields.Mutable headers = response.getHeaders();
		headers.put( HttpHeader.LOCATION, location );
		headers.put( HttpHeader.CONTENT_LENGTH, "0" );
		response.write( true, Content.Chunk.EMPTY.getByteBuffer(), callback );
	}
}
