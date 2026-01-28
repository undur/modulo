package modulo;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.woadaptorconfig.AdaptorConfigParser;
import modulo.woadaptorconfig.model.AdaptorConfig;
import modulo.woadaptorconfig.model.App;
import modulo.woadaptorconfig.model.Instance;

/**
 * A jetty-based reverse proxy
 *
 * FIXME: We're currently always targeting the first instance. Here's where a load balacing scheme might come in strong... // Hugi 2025-04-22
 * FIXME: currently only support one instance. Target instances based on the request URL and 'woinst' cookie // Hugi 2025-04-22
 * FIXME: We might want to gather some statistics and logging // Hugi 2025-04-22
 * FIXME: Add some nice error pages // Hugi 2025-04-22
 * FIXME: Target application based on domain // Hugi 2025-10-09
 * FIXME: Shutdown/startup of an application in a related wotaskd should really trigger an adaptor reload // Hugi 2025-10-09
 * FIXME: Adaptor config needs to be manually updatable // Hugi 2025-04-22
 * FIXME: We're missing user configuration for ... everything // Hugi 2025-04-22
 */

public class Modulo {

	private static Logger logger = LoggerFactory.getLogger( Modulo.class );

	/**
	 * Duration between reloads of adaptor configuration
	 *
	 * FIXME: Should be settable/configurable // Hugi 2025-10-09
	 */
	private static final Duration DEFAULT_CONFIG_RELOAD_DURATION = Duration.ofSeconds( 10 );

	/**
	 * Adaptor URL
	 *
	 * FIXME: Should be settable/configurable // Hugi 2025-10-09
	 */
	private static final String ADAPTOR_URL = "/Apps/WebObjects/";

	/**
	 * The port to the proxy will run on
	 */
	private final int _port;

	/**
	 * FIXME: We're going to want to hold multiple adaptor configuration sources // Hugi 2025-05-02
	 */
	private AdaptorConfig _adaptorConfig;

	/**
	 * Construct a new instance running the proxy on the given port
	 */
	public Modulo( final int port ) {
		_port = port;

		reloadAdaptorConfig();
	}

	/**
	 * @return The host wotaskd is running on
	 */
	public static String wotaskdHost() {
		return getRequiredProperty( "modulo.wotaskd.host" );
	}

	/**
	 * @return Port number to fetch wotaskd's configuration from
	 */
	public static int wotaskdPort() {
		return Integer.parseInt( getRequiredProperty( "modulo.wotaskd.port" ) );
	}

	/**
	 * @return The password for getting configuration from the targeted wotaskd instance
	 */
	public static String wotaskdPassword() {
		return getRequiredProperty( "modulo.wotaskd.password" );
	}

	/**
	 * @return True if we want to run without wotaskd for running testing
	 */
	public static boolean isTesting() {
		return "true".equals( System.getProperty( "modulo.testing" ) );
	}

	/**
	 * @return The value of the java System property [propertyName]
	 * @throws IllegalStateException if the property is not set
	 */
	private static String getRequiredProperty( final String propertyName ) {
		final String value = System.getProperty( propertyName );

		if( value == null ) {
			throw new IllegalStateException( "The system property %s is not set".formatted( propertyName ) );
		}

		return value;
	}

	public void start() {

		logger.info( "Starting modulo" );

		final Server server = new Server();

		final HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.setSendServerVersion( false ); // Not sending the server software/version is good practice for security

		final HttpConnectionFactory connectionFactory = new HttpConnectionFactory( httpConfig );
		final ServerConnector connector = new ServerConnector( server, connectionFactory );
		connector.setPort( _port );
		server.addConnector( connector );
		server.setHandler( new ModuloProxy( rewriteURIFunction() ) );
		server.setErrorHandler( new ModuloProxy.ModuloErrorHandler() );

		try {
			server.start();
		}
		catch( final Exception e ) {
			logger.info( "Modulo startup failed" );
			e.printStackTrace();
			System.exit( -1 );
		}

		startAdaptorConfigAutoReloader();
	}

	public void reloadAdaptorConfig() {
		_adaptorConfig = fetchAdaptorConfig();

		// FIXME: Hardcoded modulo reference should not really be present // Hugi 2026-01-28
		final Map<String, App> applications = new HashMap<>( _adaptorConfig.applications() );
		final App moduloApp = new App( "Modulo", List.of( new Instance( 1, "localhost", 45678 ) ) );
		applications.put( "Modulo", moduloApp );

		_adaptorConfig = new AdaptorConfig( applications );
	}

	public AdaptorConfig adaptorConfig() {
		return _adaptorConfig;
	}

	private void startAdaptorConfigAutoReloader() {
		final TimerTask adaptorConfigReloadTask = new TimerTask() {
			@Override
			public void run() {
				reloadAdaptorConfig();
			}
		};

		final Timer timer = new Timer( "AdaptorConfigReloader", true );
		final long timeBeforeFirstExecution = Duration.ofSeconds( 0 ).toMillis();
		final long timeBetweenExecutions = DEFAULT_CONFIG_RELOAD_DURATION.toMillis();
		timer.schedule( adaptorConfigReloadTask, timeBeforeFirstExecution, timeBetweenExecutions );
	}

	/**
	 * @return The adaptorConfig we'll initialize with.
	 */
	private static AdaptorConfig fetchAdaptorConfig() {

		if( isTesting() ) {
			// If we're testing,fire up a test application and return an adaptor configuration pointing to it
			final App fakeApp = new App( "FakeApp", List.of( new Instance( 1, "localhost", 1500 ) ) );
			final App localApp = new App( "LocalApp", List.of( new Instance( 1, "localhost", 1200 ) ) );
			AdaptorConfig config = new AdaptorConfig( Map.of( "FakeApp", fakeApp, "LocalApp", localApp ) );

			if( !FakeApplicationInstance.running ) {
				FakeApplicationInstance.start( 1500 );
			}

			return config;
		}

		final String host = wotaskdHost();
		final int port = wotaskdPort();
		final String password = wotaskdPassword();
		return new AdaptorConfigParser( host, port, password ).fetchAdaptorConfig();
	}

	/**
	 * @return A Function that generates the instance URI to target
	 */
	public Function<Request, HttpURI> rewriteURIFunction() {
		return request -> {
			final HttpURI originalURI = request.getHttpURI();

			final String applicationName = applicationNameFromURI( originalURI );

			final App application = _adaptorConfig.application( applicationName );

			if( application == null ) {
				throw new IllegalArgumentException( "No application found with the name %s".formatted( applicationName ) );
			}

			final List<Instance> instances = application.instances();

			if( instances.isEmpty() ) {
				throw new IllegalStateException( "No instances registered for application %s".formatted( applicationName ) );
			}

			// FIXME: We're hardcoding targeting the first instance for testing // Hugi 2025-04-22
			final Instance targetInstance = instances.getFirst();

			final String hostName = targetInstance.host();
			final int port = targetInstance.port();

			final HttpURI.Mutable targetURI = HttpURI
					.build( originalURI )
					.host( hostName )
					.scheme( HttpScheme.HTTP )
					.port( port );

			logger.info( "Forwarding %s -> %s".formatted( originalURI, targetURI ) );

			return targetURI;
		};
	}

	/**
	 * FIXME: Temporary hardcoding of domain-to-app mapping until domains find home in config // Hugi 2026-01-27
	 */
	private static final Map<String, String> domainToAppMap = Map.of(
			"www.hugi.io", "Hugi",
			"www.lidamot.is", "Lidamot" );

	/**
	 * @return The name of the application from the given URI
	 */
	static String applicationNameFromURI( final HttpURI uri ) {

		final String uriString = uri.getPath();

		if( !uriString.startsWith( ADAPTOR_URL ) ) {
			final String host = uri.getHost();

			final String domainDefaultAppName = domainToAppMap.get( host );

			if( domainDefaultAppName != null ) {
				return domainDefaultAppName;
			}

			throw new IllegalArgumentException( "The uri '%s' does not start with an adaptor URL and we're not serving a known domain".formatted( uriString ) );
		}

		String appName = uriString.substring( ADAPTOR_URL.length() );

		int periodIndex = appName.indexOf( ".woa" );

		if( periodIndex > -1 ) {
			appName = appName.substring( 0, periodIndex );
		}

		return appName;
	}
}