package modulo.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.Modulo;
import modulo.frontend.FrontendConfig;
import ng.appserver.NGActionResults;
import ng.appserver.NGApplication;
import ng.appserver.NGRequest;
import ng.appserver.NGResponse;
import ng.plugins.Routes;

public class Application extends NGApplication {

	private static final Logger logger = LoggerFactory.getLogger( Application.class );

	/**
	 * Temporary location for modulo's runtime configuration file. This is a
	 * plain java.util.Properties file containing the front-end keys (see
	 * {@link #buildFrontendConfig}). The choice of /opt/webobjects/modulo.conf
	 * is interim — these properties will move into NGProperties once the
	 * framework supports the timing we need.
	 *
	 * Override via {@code -Dmodulo.config-file=...} (mainly for testing).
	 */
	private static final Path CONFIG_FILE = Path.of( System.getProperty( "modulo.config-file", "/opt/webobjects/modulo.conf" ) );

	private final Modulo _modulo;

	/** The loaded modulo.conf properties, kept around for later lookups (admin password). */
	private final Properties _config;

	public static void main( String[] args ) {
		NGApplication.run( args, Application.class );
	}

	public Application() {
		_config = loadConfig( CONFIG_FILE );
		_modulo = new Modulo( Config.MODULO_PROXY_PORT, buildFrontendConfig( _config ) );
		_modulo.start();
	}

	/**
	 * Reads a properties file if present. Returns an empty Properties when
	 * the file is missing or unreadable (modulo then runs as a plain
	 * reverse proxy).
	 */
	static Properties loadConfig( final Path file ) {
		final Properties p = new Properties();
		if( !Files.isRegularFile( file ) ) {
			logger.info( "Config file {} not present — running without front-end", file );
			return p;
		}
		try( InputStream in = Files.newInputStream( file ) ) {
			p.load( in );
			logger.info( "Loaded {} entries from {}", p.size(), file );
		}
		catch( final IOException e ) {
			logger.warn( "Failed to read {}: {} — running without front-end", file, e.toString() );
		}
		return p;
	}

	/**
	 * Reads the front-end-related properties and assembles a
	 * {@link FrontendConfig}. Returns {@code null} if no site source is
	 * configured (neither the native sites file nor the transitional Apache
	 * manifest) — modulo then runs as a plain reverse proxy only.
	 */
	static FrontendConfig buildFrontendConfig( final Properties config ) {
		final String sitesFile = config.getProperty( "modulo.frontend.sites-file" );
		final String manifest = config.getProperty( "modulo.frontend.apache-config-file" );
		if( sitesFile == null && manifest == null ) {
			return null;
		}
		final int httpPort = parsePort( config, "modulo.frontend.http-port", 80 );
		final int httpsPort = parsePort( config, "modulo.frontend.https-port", 443 );
		final String acmeWebroot = config.getProperty( "modulo.frontend.acme-webroot" );
		final boolean http3 = Boolean.parseBoolean( config.getProperty( "modulo.frontend.http3", "false" ) );
		return new FrontendConfig(
				sitesFile == null ? null : Path.of( sitesFile ),
				manifest == null ? null : Path.of( manifest ),
				httpPort,
				httpsPort,
				acmeWebroot == null ? null : Path.of( acmeWebroot ),
				http3 );
	}

	private static int parsePort( final Properties config, final String key, final int fallback ) {
		final String raw = config.getProperty( key );
		return raw == null ? fallback : Integer.parseInt( raw.trim() );
	}

	/**
	 * @return Our modulo instance, for checking out configuration, status and statistics
	 */
	public Modulo modulo() {
		return _modulo;
	}

	@Override
	public Routes routes() {
		return Routes
				.create()
				.map( "/WOAdaptorInfo", request -> new NGResponse( _modulo.adaptorConfig().toString(), 200 ) )
				.map( "/overview", this::overviewPage )
				.map( "/", MDStartPage.class );
	}

	/**
	 * The configuration overview, guarded by the {@code modulo.admin-password}
	 * property from modulo.conf:
	 *
	 * <ul>
	 * <li>Password set → HTTP Basic auth required (any username), always.
	 * A standalone modulo looks like "development mode" to ng-appserver
	 * (no WOMonitor), so a configured password must win over the mode.</li>
	 * <li>No password → open in development mode, disabled in production.</li>
	 * </ul>
	 */
	private NGActionResults overviewPage( final NGRequest request ) {
		final String password = _config.getProperty( "modulo.admin-password" );

		if( password != null && !password.isBlank() ) {
			if( !basicAuthPasswordMatches( request, password ) ) {
				final NGResponse response = new NGResponse( "Authentication required", 401 );
				response.setHeader( "WWW-Authenticate", "Basic realm=\"modulo\"" );
				return response;
			}
		}
		else if( !isDevelopmentMode() ) {
			return new NGResponse( "The overview page is disabled. Set modulo.admin-password in %s to enable it.".formatted( CONFIG_FILE ), 403 );
		}

		return pageWithName( MDOverviewPage.class, request.context() );
	}

	/**
	 * @return True if the request carries HTTP Basic credentials whose
	 *         password part matches [password]. The username is ignored.
	 */
	private static boolean basicAuthPasswordMatches( final NGRequest request, final String password ) {
		final String header = request.headers().entrySet().stream()
				.filter( e -> "authorization".equalsIgnoreCase( e.getKey() ) )
				.flatMap( e -> e.getValue().stream() )
				.findFirst()
				.orElse( null );

		if( header == null || !header.startsWith( "Basic " ) ) {
			return false;
		}

		final String decoded;
		try {
			decoded = new String( Base64.getDecoder().decode( header.substring( "Basic ".length() ) ), StandardCharsets.UTF_8 );
		}
		catch( final IllegalArgumentException e ) {
			return false;
		}

		final int colon = decoded.indexOf( ':' );
		final String supplied = colon < 0 ? decoded : decoded.substring( colon + 1 );
		return MessageDigest.isEqual( supplied.getBytes( StandardCharsets.UTF_8 ), password.getBytes( StandardCharsets.UTF_8 ) );
	}
}
