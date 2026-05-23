package modulo.frontend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.gzip.GzipEncoderConfig;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
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
	private final boolean http3Enabled;
	private final Handler terminalHandler;

	private Server server;
	private Path http3PemWorkDir;

	public JettyFrontend(
			final List<Site> sites,
			final CertStore certStore,
			final Path acmeWebroot,
			final int httpPort,
			final int httpsPort,
			final Handler terminalHandler ) {
		this( sites, certStore, acmeWebroot, httpPort, httpsPort, false, terminalHandler );
	}

	public JettyFrontend(
			final List<Site> sites,
			final CertStore certStore,
			final Path acmeWebroot,
			final int httpPort,
			final int httpsPort,
			final boolean http3Enabled,
			final Handler terminalHandler ) {
		this.sites = List.copyOf( sites );
		this.certStore = certStore;
		this.acmeWebroot = acmeWebroot;
		this.httpPort = httpPort;
		this.httpsPort = httpsPort;
		this.http3Enabled = http3Enabled;
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

		if( http3Enabled ) {
			http3PemWorkDir = Files.createTempDirectory( "modulo-h3-" );
			logger.info( "HTTP/3 enabled — using Quiche PEM work dir {}", http3PemWorkDir );
			server.addConnector( buildHttp3Connector( sslContextFactory, http3PemWorkDir ) );
		}

		server.start();
		certStore.startWatching();
		return server;
	}

	public void stop() throws Exception {
		certStore.stopWatching();
		if( server != null ) {
			server.stop();
		}
		if( http3PemWorkDir != null ) {
			deleteRecursively( http3PemWorkDir );
			http3PemWorkDir = null;
		}
	}

	private static void deleteRecursively( final Path dir ) {
		try {
			if( !Files.exists( dir ) ) {
				return;
			}
			Files.walk( dir )
					.sorted( ( a, b ) -> b.getNameCount() - a.getNameCount() )
					.forEach( p -> {
						try {
							Files.deleteIfExists( p );
						}
						catch( final Exception ignored ) {
						}
					} );
		}
		catch( final Exception e ) {
			logger.warn( "Failed to clean HTTP/3 PEM work dir {}: {}", dir, e.toString() );
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

	/**
	 * Builds the UDP connector for HTTP/3. Reuses the same SslContextFactory
	 * as the TCP TLS connector so SNI/cert selection is identical. Requires
	 * the JVM to be started with native-access enabled for the Quiche
	 * foreign-memory module (see modulo.conf comments).
	 */
	private QuicheServerConnector buildHttp3Connector(
			final SslContextFactory.Server sslContextFactory,
			final Path pemWorkDir ) {
		final HttpConfiguration httpsConfig = new HttpConfiguration();
		httpsConfig.setSendServerVersion( false );
		httpsConfig.setSecureScheme( "https" );
		httpsConfig.setSecurePort( httpsPort );
		httpsConfig.addCustomizer( new SecureRequestCustomizer() );

		final QuicheServerQuicConfiguration quicConfig = HTTP3ServerQuicConfiguration.configure(
				new QuicheServerQuicConfiguration( pemWorkDir ) );

		final HTTP3ServerConnectionFactory h3 = new HTTP3ServerConnectionFactory( httpsConfig );
		final QuicheServerConnector connector = new QuicheServerConnector( server, sslContextFactory, quicConfig, h3 );
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
		// HTTP→HTTPS redirect, then canonical-hostname redirect, then response
		// compression, then optionally Alt-Svc advertising H/3, then RFC-2965
		// cookie scrubbing (only relevant to traffic we're about to proxy),
		// then the proxy itself.
		Handler tail = new Cookie2ScrubHandler( buildCompressionHandler( terminalHandler ) );
		if( http3Enabled ) {
			tail = new AltSvcHandler( httpsPort, tail );
		}
		return new AcmeChallengeHandler( acmeWebroot,
				new HttpsRedirectHandler( sitesByHost, httpsPort,
						new CanonicalRedirectHandler( sitesByHost, tail ) ) );
	}

	/**
	 * Wraps the terminal handler in Jetty's {@link CompressionHandler} so
	 * proxied responses get gzipped when the client supports it.
	 *
	 * FIXME: Configurable. Every value below should eventually come from
	 * per-Site (or global) configuration so operators can tune compression
	 * per-deployment. The constants are spelled out so the future refactor
	 * is mechanical. // 2026-05-21
	 */
	private static Handler buildCompressionHandler( final Handler downstream ) {
		// --- Tuning knobs (all FIXME-configurable) -----------------------
		final int COMPRESSION_LEVEL = 6;                 // gzip 1..9; 6 is the standard speed/ratio trade-off, matches Apache's default
		final List<String> COMPRESS_MIME_TYPES = List.of(
				"text/html",
				"text/plain",
				"text/xml",
				"text/css",
				"text/javascript",
				"application/javascript",
				"application/json",
				"application/xml",
				"image/svg+xml" );                       // matches the AddOutputFilterByType list from the Apache config
		final boolean COMPRESS_GET_ONLY = true;          // only compress GET responses; other methods are typically tiny / non-cacheable
		// -----------------------------------------------------------------

		final GzipEncoderConfig encoderConfig = new GzipEncoderConfig();
		encoderConfig.setCompressionLevel( COMPRESSION_LEVEL );

		final GzipCompression gzip = new GzipCompression();
		gzip.setDefaultEncoderConfig( encoderConfig );

		final CompressionConfig.Builder cfg = CompressionConfig.builder();
		for( final String mime : COMPRESS_MIME_TYPES ) {
			cfg.compressIncludeMimeType( mime );
		}
		if( COMPRESS_GET_ONLY ) {
			cfg.compressIncludeMethod( "GET" );
		}

		final CompressionHandler handler = new CompressionHandler( downstream );
		handler.putCompression( gzip );
		handler.putConfiguration( "/", cfg.build() );
		return handler;
	}

	private static String hostOf( final Request request ) {
		final String h = Request.getServerName( request );
		return h == null ? null : h.toLowerCase();
	}

	/**
	 * Normalises incoming {@code Cookie} headers before they reach the proxy.
	 *
	 * Two things happen here:
	 *
	 * <ol>
	 * <li><b>Coalesce multiple {@code Cookie} headers into one.</b> HTTP/2 (RFC
	 * 7540 §8.1.2.5) lets browsers split the {@code Cookie} header into multiple
	 * HPACK-compressed header fields. Firefox does this. RFC 7540 requires that
	 * these be re-concatenated using {@code "; "} before being passed into a
	 * non-HTTP/2 context (such as the HTTP/1.1 hop to the upstream WO app).
	 * Jetty's HttpClient does <em>not</em> do this coalescing automatically when
	 * acting as a proxy, so the upstream sees only one of the cookies and the
	 * rest are silently lost. We do the coalescing here.</li>
	 *
	 * <li><b>Strip RFC 2965 ("Cookie2") metadata segments.</b> WO emits cookies
	 * with the {@code version="1"} attribute by default, which historically
	 * caused RFC-2965-conformant browsers to echo the cookie back with
	 * {@code $Path}/{@code $Domain} metadata between each cookie. Modern
	 * browsers mostly don't do this any more, but we strip the segments
	 * defensively in case any client still does.</li>
	 * </ol>
	 *
	 * This handler only runs on the front-end chain — traffic arriving via
	 * legacy Apache → modulo:1400 was already laundered by Apache and doesn't
	 * see either issue.
	 */
	private static class Cookie2ScrubHandler extends Handler.Wrapper {

		private static final Pattern COOKIE2_METADATA = Pattern.compile(
				"\\s*;\\s*\\$(?:Path|Domain|Port|Version)\\s*=\\s*\"?[^;\"]*\"?",
				Pattern.CASE_INSENSITIVE );

		Cookie2ScrubHandler( final Handler next ) {
			super( next );
		}

		@Override
		public boolean handle( final Request request, final Response response, final Callback callback ) throws Exception {
			final HttpFields original = request.getHeaders();
			final List<String> cookieHeaders = original.getValuesList( HttpHeader.COOKIE );

			// Fast path — overwhelming majority of requests are HTTP/1.1 with a
			// single canonical Cookie header. Skip the work entirely.
			if( cookieHeaders.isEmpty() ) {
				return super.handle( request, response, callback );
			}
			if( cookieHeaders.size() == 1 && cookieHeaders.get( 0 ).indexOf( '$' ) < 0 ) {
				return super.handle( request, response, callback );
			}

			// Either H/2-style split cookies, or RFC 2965 metadata present.
			final String coalesced = join( cookieHeaders );
			final String scrubbed = coalesced.indexOf( '$' ) < 0 ? coalesced : COOKIE2_METADATA.matcher( coalesced ).replaceAll( "" );
			if( scrubbed.isBlank() ) {
				// Nothing left after scrubbing — drop the Cookie header entirely.
				return super.handle( new HeadersOverrideRequest( request, withoutCookies( original ) ), response, callback );
			}
			return super.handle( new HeadersOverrideRequest( request, withSingleCookie( original, scrubbed ) ), response, callback );
		}

		private static String join( final List<String> cookieHeaders ) {
			if( cookieHeaders.size() == 1 ) {
				return cookieHeaders.get( 0 );
			}
			final StringBuilder out = new StringBuilder();
			for( final String value : cookieHeaders ) {
				if( !out.isEmpty() ) {
					out.append( "; " );
				}
				out.append( value );
			}
			return out.toString();
		}

		private static HttpFields withoutCookies( final HttpFields original ) {
			final HttpFields.Mutable out = HttpFields.build();
			for( final org.eclipse.jetty.http.HttpField field : original ) {
				if( HttpHeader.COOKIE != field.getHeader() ) {
					out.add( field );
				}
			}
			return out.asImmutable();
		}

		private static HttpFields withSingleCookie( final HttpFields original, final String cookie ) {
			final HttpFields.Mutable out = HttpFields.build();
			boolean emitted = false;
			for( final org.eclipse.jetty.http.HttpField field : original ) {
				if( HttpHeader.COOKIE == field.getHeader() ) {
					if( !emitted ) {
						out.add( HttpHeader.COOKIE, cookie );
						emitted = true;
					}
				}
				else {
					out.add( field );
				}
			}
			return out.asImmutable();
		}

	}

	/**
	 * Tiny {@link Request.Wrapper} that returns a substituted {@link HttpFields}
	 * for {@link #getHeaders()}. Used by {@link Cookie2ScrubHandler} to
	 * present sanitised request headers to downstream handlers.
	 */
	private static class HeadersOverrideRequest extends Request.Wrapper {

		private final HttpFields headers;

		HeadersOverrideRequest( final Request wrapped, final HttpFields headers ) {
			super( wrapped );
			this.headers = headers;
		}

		@Override
		public HttpFields getHeaders() {
			return headers;
		}
	}

	/**
	 * Adds an {@code Alt-Svc} response header on secure (HTTPS) responses so
	 * capable clients know they may upgrade to HTTP/3. {@code ma=86400} caches
	 * the advertisement for a day. Only added on H/2 or H/1.1 over TLS — for
	 * H/3 traffic it would be a no-op (already using H/3) but harmless.
	 */
	private static class AltSvcHandler extends Handler.Wrapper {

		private final String altSvcValue;

		AltSvcHandler( final int httpsPort, final Handler next ) {
			super( next );
			this.altSvcValue = "h3=\":" + httpsPort + "\"; ma=86400";
		}

		@Override
		public boolean handle( final Request request, final Response response, final Callback callback ) throws Exception {
			if( request.isSecure() ) {
				response.getHeaders().put( "alt-svc", altSvcValue );
			}
			return super.handle( request, response, callback );
		}
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
