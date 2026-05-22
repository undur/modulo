package modulo;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.frontend.FrontendConfig;
import modulo.frontend.JettyFrontend;
import modulo.frontend.apache.ApacheConfigReader;
import modulo.frontend.site.Site;
import modulo.frontend.tls.CertStore;
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
	 * Front-end configuration. {@code null} means "front-end disabled" —
	 * modulo runs only its plain reverse-proxy connector on {@link #_port}.
	 */
	private final FrontendConfig _frontendConfig;

	/**
	 * Construct a new instance running the plain reverse-proxy connector on
	 * the given port, with no front-end (today's behavior).
	 */
	public Modulo( final int port ) {
		this( port, null );
	}

	/**
	 * Construct a new instance running both the plain reverse-proxy
	 * connector and (if {@code frontendConfig} is non-null and its manifest
	 * file exists) the TLS front-end alongside it.
	 */
	public Modulo( final int port, final FrontendConfig frontendConfig ) {
		_port = port;
		_frontendConfig = frontendConfig;

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

		// Plain reverse-proxy connector always runs (today's behavior, port 1400).
		// A failure here is fatal — modulo cannot function without it.
		try {
			startPlain();
		}
		catch( final Exception e ) {
			logger.error( "Modulo startup failed" );
			e.printStackTrace();
			System.exit( -1 );
		}

		// The TLS front-end runs alongside the plain connector when a manifest
		// file is configured and exists on disk. A front-end failure is logged
		// loudly but does NOT take the process down — the plain reverse proxy
		// continues serving (which is what existing deployments rely on).
		// Whether the plain connector should keep running once the front-end
		// is in use will become a real config option later.
		final boolean useFrontend = _frontendConfig != null && Files.isRegularFile( _frontendConfig.apacheConfigManifest() );
		if( useFrontend ) {
			try {
				startWithFrontend( _frontendConfig );
			}
			catch( final Exception e ) {
				logger.error( "Front-end startup failed — continuing with plain reverse proxy only", e );
			}
		}

		startAdaptorConfigAutoReloader();
	}

	/**
	 * Original (pre-iteration-1) startup: a plain-HTTP connector on the
	 * configured port. Used when the front-end flag is absent — modulo runs
	 * behind another web server.
	 */
	private void startPlain() throws Exception {
		final QueuedThreadPool threadPool = new QueuedThreadPool();
		threadPool.setMaxThreads( 200 ); // FIXME: Make configurable
		threadPool.setVirtualThreadsExecutor( Executors.newVirtualThreadPerTaskExecutor() );
		final Server server = new Server( threadPool );

		final HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.setSendServerVersion( false );

		final HttpConnectionFactory connectionFactory = new HttpConnectionFactory( httpConfig );
		final ServerConnector connector = new ServerConnector( server, connectionFactory );
		connector.setPort( _port );
		server.addConnector( connector );
		server.setHandler( new ModuloProxy( rewriteURIFunction() ) );
		server.setErrorHandler( new ModuloProxy.ModuloErrorHandler() );

		server.start();
	}

	/**
	 * Iteration-1 startup: build the front-end (TLS + SNI + redirects + ACME
	 * passthrough) from the manifest file in {@code config} and bind it on
	 * the configured HTTP/HTTPS ports.
	 */
	private void startWithFrontend( final FrontendConfig config ) throws Exception {
		final List<Site> sites = ApacheConfigReader.fromManifest( config.apacheConfigManifest() ).read();
		if( sites.isEmpty() ) {
			throw new IllegalStateException( "No sites found via manifest " + config.apacheConfigManifest() + " — refusing to start front-end with no Sites" );
		}
		logger.info( "Front-end discovered {} site(s) via manifest {}", sites.size(), config.apacheConfigManifest() );

		logUnmappedDomains( sites );

		final CertStore certStore = new CertStore( sites );
		certStore.load();

		final JettyFrontend frontend = new JettyFrontend(
				sites,
				certStore,
				config.acmeWebroot(),
				config.httpPort(),
				config.httpsPort(),
				config.http3(),
				new ModuloProxy( rewriteURIFunction() ) );
		frontend.start();
	}

	/**
	 * Walks the configured sites at startup and warns about hostnames that
	 * won't route correctly: hostnames missing from {@link #domainToAppMap},
	 * or hostnames pointing at app names that aren't known to wotaskd.
	 * Helps catch misconfiguration before traffic hits modulo.
	 */
	private void logUnmappedDomains( final List<Site> sites ) {
		final List<String> hostnamesWithoutMapping = new ArrayList<>();
		final List<String> hostnamesPointingAtUnknownApp = new ArrayList<>();

		for( final Site site : sites ) {
			for( final String host : site.allHostnames() ) {
				final String mappedApp = domainToAppMap.get( host );
				if( mappedApp == null ) {
					hostnamesWithoutMapping.add( host );
					continue;
				}
				if( _adaptorConfig.application( mappedApp ) == null ) {
					hostnamesPointingAtUnknownApp.add( "%s -> %s".formatted( host, mappedApp ) );
				}
			}
		}

		if( !hostnamesWithoutMapping.isEmpty() ) {
			logger.warn( "{} site hostname(s) have no entry in domainToAppMap and won't route: {}",
					hostnamesWithoutMapping.size(), hostnamesWithoutMapping );
		}
		if( !hostnamesPointingAtUnknownApp.isEmpty() ) {
			logger.warn( "{} site hostname(s) point at apps unknown to wotaskd: {}",
					hostnamesPointingAtUnknownApp.size(), hostnamesPointingAtUnknownApp );
		}
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
	 * @return The name of the application from the given URI
	 */
	static String applicationNameFromURI( final HttpURI uri ) {

		final String uriString = uri.getPath();

		if( !uriString.startsWith( ADAPTOR_URL ) ) {
			final String host = uri.getHost();

			final String domainDefaultAppName = DomainApp.appForHost( host );

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