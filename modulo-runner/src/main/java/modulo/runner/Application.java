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

	/** When this modulo started, for the start page's uptime display. */
	private static final java.time.Instant _startedAt = java.time.Instant.now();

	public static java.time.Instant startedAt() {
		return _startedAt;
	}

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
	 * {@link FrontendConfig}. Returns {@code null} if no sites file is
	 * configured — modulo then runs as a plain reverse proxy only.
	 */
	static FrontendConfig buildFrontendConfig( final Properties config ) {
		final String sitesFile = config.getProperty( "modulo.frontend.sites-file" );
		if( sitesFile == null ) {
			return null;
		}
		final int httpPort = parsePort( config, "modulo.frontend.http-port", 80 );
		final int httpsPort = parsePort( config, "modulo.frontend.https-port", 443 );
		final String acmeWebroot = config.getProperty( "modulo.frontend.acme-webroot" );
		final String accessLogDir = config.getProperty( "modulo.frontend.access-log-dir" );
		final boolean http3 = Boolean.parseBoolean( config.getProperty( "modulo.frontend.http3", "false" ) );
		return new FrontendConfig(
				Path.of( sitesFile ),
				httpPort,
				httpsPort,
				acmeWebroot == null ? null : Path.of( acmeWebroot ),
				accessLogDir == null ? null : Path.of( accessLogDir ),
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

	/**
	 * Everything this application serves is admin territory — the proxied
	 * sites never enter this dispatch, only the admin UI, its resources,
	 * component actions and framework routes (including the development
	 * plugin's rather consequential /ng/dev/terminate). So the admin guard
	 * sits here, in front of all of it, rather than on individual routes.
	 */
	@Override
	public NGResponse dispatchRequest( final NGRequest request ) {
		final NGActionResults denied = adminGuard( request );

		if( denied != null ) {
			return denied.generateResponse();
		}

		return super.dispatchRequest( request );
	}

	@Override
	public Routes routes() {
		return Routes
				.create()
				.map( "/WOAdaptorInfo", request -> new NGResponse( _modulo.adaptorConfig().toString(), 200 ) )
				.map( "/overview", MDOverviewPage.class )
				.map( "/applications", MDApplicationsPage.class )
				.map( "/events", MDEventsPage.class )
				.map( "/config", MDConfigPage.class )
				.map( "/stats.json", this::statsJson )
				.map( "/reload", this::reloadAction )
				.map( "/events/clear", this::clearEventsAction )
				.map( "/", MDStartPage.class );
	}

	/**
	 * The dashboard's data: per-app request counts (minute-bucketed, last
	 * hour) and average response times, as JSON. Guarded like everything else
	 * by the dispatch-level admin guard.
	 */
	private NGActionResults statsJson( final NGRequest request ) {
		final modulo.stats.RequestStats.Snapshot snapshot = _modulo.requestStats().snapshot();
		final String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString( snapshot );
		final NGResponse response = new NGResponse( json, 200 );
		response.setHeader( "content-type", "application/json" );
		return response;
	}

	/**
	 * Empties the event buffer — the operator drawing a line under handled
	 * events. POST only; the log files are untouched. Leaves a single INFO
	 * event marking the epoch, so an empty-looking page is distinguishable
	 * from a never-filled one.
	 */
	private NGActionResults clearEventsAction( final NGRequest request ) {
		if( !"POST".equalsIgnoreCase( request.method() ) ) {
			return new NGResponse( "Use POST to clear\n", 405 );
		}

		_modulo.events().clear();
		_modulo.events().add( modulo.frontend.events.Event.Severity.INFO, "events-cleared", null, null, "Event log cleared by operator" );

		final NGResponse response = new NGResponse( "", 302 );
		response.setHeader( "Location", "/events" );
		return response;
	}

	/**
	 * Reloads the sites config into the running front-end. POST only (it has
	 * side effects); auth is handled by the dispatch-level guard. From the CLI:
	 *
	 * <pre>curl -X POST -u :yourpassword https://yourserver/reload</pre>
	 *
	 * A config that fails validation changes nothing and reports why (422).
	 */
	private NGActionResults reloadAction( final NGRequest request ) {
		if( !"POST".equalsIgnoreCase( request.method() ) ) {
			return new NGResponse( "Use POST to reload\n", 405 );
		}

		try {
			return new NGResponse( _modulo.reloadSitesConfig() + "\n", 200 );
		}
		catch( final Exception e ) {
			return new NGResponse( "Reload failed — the previous configuration is untouched and still serving:\n\n%s\n".formatted( e.getMessage() ), 422 );
		}
	}

	/**
	 * @return True if an admin password is configured — for the config
	 *         inventory display (never the value itself, obviously)
	 */
	boolean adminPasswordConfigured() {
		final String password = _config.getProperty( "modulo.admin-password" );
		return password != null && !password.isBlank();
	}

	/**
	 * The shared guard for all admin pages/endpoints, driven by the
	 * {@code modulo.admin-password} property from modulo.conf:
	 *
	 * <ul>
	 * <li>Password set → HTTP Basic auth required (any username), always.
	 * A standalone modulo looks like "development mode" to ng-appserver
	 * (no WOMonitor), so a configured password must win over the mode.</li>
	 * <li>No password → open in development mode, disabled in production.</li>
	 * </ul>
	 *
	 * @return null when the request may proceed, otherwise the response to send instead
	 */
	private NGActionResults adminGuard( final NGRequest request ) {
		final String password = _config.getProperty( "modulo.admin-password" );

		if( password != null && !password.isBlank() ) {
			if( !basicAuthPasswordMatches( request, password ) ) {
				final NGResponse response = new NGResponse( "Authentication required", 401 );
				response.setHeader( "WWW-Authenticate", "Basic realm=\"modulo\"" );
				return response;
			}
		}
		else if( !isDevelopmentMode() ) {
			return new NGResponse( "Admin endpoints are disabled. Set modulo.admin-password in %s to enable them.".formatted( CONFIG_FILE ), 403 );
		}

		return null;
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
