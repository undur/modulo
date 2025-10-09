package modulo;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
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
 * FIXME: Oh, and we're going to have to target the correct instance based on the request URL // Hugi 2025-04-22
 * FIXME: We might want to gather some statistics and logging // Hugi 2025-04-22
 * FIXME: Adaptor config needs to be regularly updated/manually updatable // Hugi 2025-04-22
 * FIXME: We're missing configuration options for ... everything // Hugi 2025-04-22
 * FIXME: Add some nice error pages // Hugi 2025-04-22
 * FIXME: Target application based on domain // Hugi 2025-10-09
 * FIXME: Shutdown/startup of an application in a related wotaskd should really trigger an adaptor reload // Hugi 2025-10-09
 */

public class Modulo {

	private static Logger logger = LoggerFactory.getLogger( Modulo.class );

	/**
	 * Duration between reloads of adaptor configuration
	 */
	private static final Duration DEFAULT_CONFIG_RELOAD_DURATION = Duration.ofSeconds( 10 );

	/**
	 * The port to the proxy will run on
	 */
	private final int port;

	/**
	 * FIXME: We're going to want to hold multiple adaptor configuration sources // Hugi 2025-05-02
	 */
	private AdaptorConfig adaptorConfig;

	/**
	 * Construct a new instance running the proxy on the given port
	 */
	public Modulo( final int port ) {
		this.port = port;
		this.adaptorConfig = fetchAdaptorConfig();
	}

	public void start() {

		logger.info( "Starting modulo" );

		final Server server = new Server();
		final ServerConnector connector = new ServerConnector( server );
		connector.setPort( port );
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

		startAdaptorConfigAutoReloader();
	}

	public void reloadAdaptorConfig() {
		adaptorConfig = fetchAdaptorConfig();
	}

	public AdaptorConfig adaptorConfig() {
		return adaptorConfig;
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
	 *
	 * FIXME: This will be refactored out of existence soon // Hugi 2025-04-23
	 */
	private static AdaptorConfig fetchAdaptorConfig() {

		AdaptorConfig config;

		final String password = System.getProperty( "wotaskdpassword" );

		if( password != null ) {
			final int port = 1085;
			final String host = "hz1.rebbi.is";
			config = new AdaptorConfigParser( host, port, password ).fetchAdaptorConfig();
		}
		else {
			// If the password is not set, we fire up the test server and return an adaptor configuration pointing to it
			final Instance instance = new Instance( 1, "localhost", 1500 );
			final App app = new App( "FakeApp", List.of( instance ) );
			config = new AdaptorConfig( Map.of( "FakeApp", app ) );

			if( !FakeApplicationInstance.running ) {
				FakeApplicationInstance.start( 1500 );
			}
		}

		return config;
	}

	/**
	 * @return A Function that generates the instance URI to target
	 */
	public Function<Request, HttpURI> rewriteURIFunction() {
		return request -> {
			final HttpURI originalURI = request.getHttpURI();

			final String applicationName = applicationNameFromURI( originalURI );

			final App application = adaptorConfig.application( applicationName );

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
	 * @return The name of the application from the given URI
	 */
	static String applicationNameFromURI( final HttpURI uri ) {
		final String[] splitPath = uri.getPath().split( "/" );

		if( splitPath.length < 4 ) {
			throw new IllegalArgumentException( "URL too short" );
		}

		String applicationName = splitPath[3];

		// Remove the .woa from the application name
		applicationName = applicationName.split( "\\." )[0];

		return applicationName;
	}

	/**
	 * Main method included for quick testing
	 */
	public static void main( String[] argv ) {
		new Modulo( 1400 ).start();
	}
}