package modulo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

import modulo.config.SitesConfig;
import modulo.config.SitesConfigReader;
import modulo.error.ErrorCondition;
import modulo.error.ErrorHandling;
import modulo.error.ProxyRoutingException;
import modulo.frontend.FrontendConfig;
import modulo.frontend.JettyFrontend;
import modulo.frontend.events.Event;
import modulo.frontend.events.EventLog;
import modulo.frontend.site.Site;
import modulo.frontend.tls.CertStore;
import modulo.frontend.tls.acme.AcmeManager;
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
	 * Hostname → app name routing, populated from the sites config when the
	 * front-end is active. {@code null} in plain-proxy mode — routing then
	 * falls back to the {@link DomainApp} property-driven mappings.
	 */
	private Map<String, String> _domainToAppMap;

	/**
	 * The parsed sites config when the front-end is active, null in
	 * plain-proxy mode. Exposed for the overview page.
	 */
	private SitesConfig _sitesConfig;

	/**
	 * How error conditions are answered. Defaults to modulo's error page for
	 * every condition; the embedding application may reassign individual
	 * conditions via {@link #errorHandling()}.
	 */
	private final ErrorHandling _errorHandling = ErrorHandling.withDefaults();

	/**
	 * Recent noteworthy occurrences (proxy failures, certs obtained/failed,
	 * config reloads), buffered for inspection through the admin UI.
	 */
	private final EventLog _events = new EventLog( 1000 );

	/** The front-end's moving parts, kept for config reload. All null in plain-proxy mode. */
	private CertStore _certStore;
	private JettyFrontend _frontend;
	private AcmeManager _acmeManager;

	/**
	 * The keystore feeding the HTTP/3 connector its single "fleet"
	 * certificate — one cert covering every ACME-managed hostname,
	 * sidestepping Jetty's one-cert-per-QUIC-connector limitation.
	 * Null unless http3 is enabled and ACME sites exist.
	 */
	private CertStore _h3FleetStore;

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

		// The TLS front-end runs alongside the plain connector when the sites
		// config file exists on disk. A front-end failure is logged
		// loudly but does NOT take the process down — the plain reverse proxy
		// continues serving (which is what existing deployments rely on).
		// Whether the plain connector should keep running once the front-end
		// is in use will become a real config option later.
		final boolean useFrontend = _frontendConfig != null && isRegularFile( _frontendConfig.sitesFile() );
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
		server.setHandler( new ModuloProxy( rewriteURIFunction(), _errorHandling, _events ) );
		server.setErrorHandler( new ModuloProxy.ModuloErrorHandler( _errorHandling ) );

		server.start();
	}

	/**
	 * Iteration-1 startup: build the front-end (TLS + SNI + redirects + ACME
	 * passthrough) from the manifest file in {@code config} and bind it on
	 * the configured HTTP/HTTPS ports.
	 */
	private void startWithFrontend( final FrontendConfig config ) throws Exception {
		final SitesConfig sitesConfig = SitesConfigReader.read( config.sitesFile() );
		_sitesConfig = sitesConfig;
		_domainToAppMap = sitesConfig.domainToAppMap();
		final List<Site> sites = sitesConfig.frontendSites();
		logger.info( "Front-end configured with {} site(s) from {}", sites.size(), config.sitesFile() );

		if( sites.isEmpty() ) {
			throw new IllegalStateException( "No sites found — refusing to start front-end with no Sites" );
		}

		logUnmappedDomains( sites );

		// HTTP/3's fleet certificate: Jetty's QUIC connector can present only
		// one certificate, so when h3 is enabled, one extra ACME cert covering
		// every ACME-managed hostname is maintained alongside the per-site
		// certs and fed to the QUIC connector from its own keystore.
		final Site fleetSite = (config.http3() && !sitesConfig.acmeManagedSites().isEmpty()) ? h3FleetSite( sitesConfig ) : null;

		// The ACME manager always exists when the front-end runs (a config
		// reload may introduce the first ACME site later). ACME-managed sites
		// without a cert on disk get a self-signed placeholder before the
		// keystore is built, so the TLS connector can start immediately; real
		// certs are ordered in the background below. The fleet "site" rides
		// along as one more managed entry — placeholder, issuance and
		// SAN-coverage renewal all apply to it unchanged.
		_acmeManager = new AcmeManager( sitesConfig.acme(), managedSitesPlusFleet( sitesConfig, fleetSite ), _events );
		_acmeManager.ensurePlaceholders();

		_certStore = new CertStore( sites );
		_certStore.load();

		if( fleetSite != null ) {
			_h3FleetStore = new CertStore( List.of( fleetSite ) );
			_h3FleetStore.load();
		}

		_frontend = new JettyFrontend(
				sites,
				_certStore,
				config.acmeWebroot(),
				_acmeManager::challengeContent,
				config.httpPort(),
				config.httpsPort(),
				config.http3(),
				_h3FleetStore,
				fleetSite == null ? null : Set.copyOf( fleetSite.allHostnames() ),
				config.accessLogDir(),
				new ModuloProxy( rewriteURIFunction(), _errorHandling, _events ),
				new ModuloProxy.ModuloErrorHandler( _errorHandling ) );
		_frontend.start();

		// Only after the server is up — HTTP-01 needs the HTTP connector answering
		_acmeManager.start( () -> {
			_certStore.reloadNow();
			if( _h3FleetStore != null ) {
				_h3FleetStore.reloadNow();
			}
		} );
	}

	/**
	 * @return The synthetic Site whose certificate covers every ACME-managed
	 *         hostname — the HTTP/3 fleet certificate. Its PEMs live under
	 *         {@code <acme storage>/h3-fleet/}.
	 */
	private static Site h3FleetSite( final SitesConfig sitesConfig ) {
		final List<String> hostnames = sitesConfig.acmeManagedSites().stream()
				.flatMap( site -> site.allHostnames().stream() )
				.distinct()
				.toList();
		final Path fleetDir = sitesConfig.acme().storageDir().resolve( "h3-fleet" );
		return new Site( hostnames.getFirst(), hostnames.subList( 1, hostnames.size() ), fleetDir.resolve( "cert.pem" ), fleetDir.resolve( "key.pem" ), true, true );
	}

	private static List<Site> managedSitesPlusFleet( final SitesConfig sitesConfig, final Site fleetSite ) {
		if( fleetSite == null ) {
			return sitesConfig.acmeManagedSites();
		}
		final List<Site> all = new ArrayList<>( sitesConfig.acmeManagedSites() );
		all.add( fleetSite );
		return all;
	}

	/**
	 * Re-reads the sites config and applies it to the running front-end
	 * without a restart: routing map, redirect policy, keystore and
	 * ACME-managed set all swap in place; new ACME sites get a placeholder
	 * immediately and a real certificate ordered in the background.
	 *
	 * Validation-first: a config that doesn't parse (or yields no loadable
	 * certificates) throws and changes <em>nothing</em> — the front-end keeps
	 * serving its current configuration.
	 *
	 * @return A short human-readable summary of what is now live
	 */
	public synchronized String reloadSitesConfig() throws Exception {
		try {
			return doReloadSitesConfig();
		}
		catch( final Exception e ) {
			_events.add( Event.Severity.ERROR, "config-reload-rejected", null, null, e.getMessage() );
			throw e;
		}
	}

	private String doReloadSitesConfig() throws Exception {
		if( _frontend == null ) {
			throw new IllegalStateException( "The front-end is not running — nothing to reload (plain-proxy mode, or front-end startup failed)" );
		}

		final SitesConfig newConfig = SitesConfigReader.read( _frontendConfig.sitesFile() );
		final List<Site> sites = newConfig.frontendSites();

		if( sites.isEmpty() ) {
			throw new IllegalStateException( "The sites config contains no sites — refusing to reload the front-end down to nothing" );
		}

		final Site newFleetSite = (_h3FleetStore != null && !newConfig.acmeManagedSites().isEmpty()) ? h3FleetSite( newConfig ) : null;

		_acmeManager.update( newConfig.acme(), managedSitesPlusFleet( newConfig, newFleetSite ) );
		_acmeManager.ensurePlaceholders();

		_certStore.updateSites( sites ); // rebuilds the keystore; throws (changing nothing) if no certs load
		_frontend.updateSites( sites );

		if( newFleetSite != null ) {
			_h3FleetStore.updateSites( List.of( newFleetSite ) );
			_frontend.updateH3CoveredHosts( Set.copyOf( newFleetSite.allHostnames() ) );
		}
		else if( _h3FleetStore != null ) {
			logger.warn( "HTTP/3 is enabled but the reloaded config has no ACME-managed sites — the fleet certificate is frozen at its current coverage" );
		}

		_sitesConfig = newConfig;
		_domainToAppMap = newConfig.domainToAppMap();

		logUnmappedDomains( sites );
		_acmeManager.checkNow();

		final String summary = "Reloaded sites config: %d site(s), %d ACME-managed".formatted( sites.size(), newConfig.acmeManagedSites().size() );
		logger.info( summary );
		_events.add( Event.Severity.INFO, "config-reloaded", null, null, summary );
		return summary;
	}

	/**
	 * @return The name of the app serving [host] — from the sites config's
	 *         routing map when the front-end is active, otherwise from the
	 *         {@link DomainApp} property mappings. Null when the host is
	 *         unknown.
	 */
	private String appForHost( final String host ) {

		if( _domainToAppMap != null ) {
			return host == null ? null : _domainToAppMap.get( host.toLowerCase( Locale.ROOT ) );
		}

		return DomainApp.appForHost( host );
	}

	private static boolean isRegularFile( final Path path ) {
		return path != null && Files.isRegularFile( path );
	}

	/**
	 * Walks the configured sites at startup and warns about hostnames that
	 * won't route correctly: hostnames with no app mapping, or hostnames
	 * pointing at app names that aren't known to wotaskd.
	 * Helps catch misconfiguration before traffic hits modulo.
	 */
	private void logUnmappedDomains( final List<Site> sites ) {
		final List<String> hostnamesWithoutMapping = new ArrayList<>();
		final List<String> hostnamesPointingAtUnknownApp = new ArrayList<>();

		for( final Site site : sites ) {
			for( final String host : site.allHostnames() ) {
				final String mappedApp = appForHost( host );
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
			logger.warn( "{} site hostname(s) have no app mapping and won't route: {}",
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

	/**
	 * @return The active native sites config, null when not in use (plain
	 *         proxy or Apache-import path)
	 */
	public SitesConfig sitesConfig() {
		return _sitesConfig;
	}

	/**
	 * @return The front-end configuration, null when the front-end is disabled
	 */
	public FrontendConfig frontendConfig() {
		return _frontendConfig;
	}

	/**
	 * @return The error-condition → response registry, for assigning custom responders
	 */
	public ErrorHandling errorHandling() {
		return _errorHandling;
	}

	/**
	 * @return The buffer of recent noteworthy events, for the admin UI
	 */
	public EventLog events() {
		return _events;
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

			final String applicationName = applicationNameFromURI( originalURI, this::appForHost );

			final App application = _adaptorConfig.application( applicationName );

			if( application == null ) {
				throw new ProxyRoutingException( ErrorCondition.APP_UNAVAILABLE, "No application found with the name %s".formatted( applicationName ) );
			}

			final List<Instance> instances = application.instances();

			if( instances.isEmpty() ) {
				throw new ProxyRoutingException( ErrorCondition.NO_INSTANCES, "No instances registered for application %s".formatted( applicationName ) );
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

			// Per-request logging belongs in the access log — this is debug-only tracing
			logger.debug( "Forwarding {} -> {}", originalURI, targetURI );

			return targetURI;
		};
	}

	/**
	 * @param appForHost Resolves a hostname to the app serving it (null when unknown)
	 * @return The name of the application from the given URI
	 */
	static String applicationNameFromURI( final HttpURI uri, final Function<String, String> appForHost ) {

		final String uriString = uri.getPath();

		if( !uriString.startsWith( ADAPTOR_URL ) ) {
			final String host = uri.getHost();

			final String domainDefaultAppName = appForHost.apply( host );

			if( domainDefaultAppName != null ) {
				return domainDefaultAppName;
			}

			throw new ProxyRoutingException( ErrorCondition.NO_APP_FOR_HOST, "The uri '%s' does not start with an adaptor URL and host '%s' has no app configured".formatted( uriString, host ) );
		}

		String appName = uriString.substring( ADAPTOR_URL.length() );

		int periodIndex = appName.indexOf( ".woa" );

		if( periodIndex > -1 ) {
			appName = appName.substring( 0, periodIndex );
		}

		return appName;
	}
}