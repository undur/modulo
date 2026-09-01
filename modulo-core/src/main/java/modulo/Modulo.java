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
 * FIXME: Statistics/profiling pages remain to be built (events + access logs exist) // Hugi 2025-04-22
 * FIXME: Shutdown/startup of an application in a related wotaskd should really trigger an adaptor reload // Hugi 2025-10-09
 * FIXME: Load-balancing strategies beyond round-robin, health checks, draining — see roadmap // Hugi 2026-08-29
 */

public class Modulo {

	private static Logger logger = LoggerFactory.getLogger( Modulo.class );

	/**
	 * Duration between reloads of adaptor configuration
	 *
	 * FIXME: Should be settable/configurable // Hugi 2025-10-09
	 */
	public static final Duration DEFAULT_CONFIG_RELOAD_DURATION = Duration.ofSeconds( 10 );

	/**
	 * The URL prefix identifying adaptor-style requests
	 * ({@code /<prefix>/AppName.woa/...}). Settable via
	 * {@code -Dmodulo.adaptor-url}; the deployed apps' own adaptor-URL
	 * setting should match, since they generate URLs with it.
	 */
	public static final String ADAPTOR_URL = normalizedAdaptorURL( System.getProperty( "modulo.adaptor-url", "/Apps/WebObjects/" ) );

	private static String normalizedAdaptorURL( final String value ) {
		String url = value.startsWith( "/" ) ? value : "/" + value;
		return url.endsWith( "/" ) ? url : url + "/";
	}

	/**
	 * Max worker threads for the plain proxy server. FIXME: Make configurable // 2026-08-29
	 */
	public static final int PLAIN_PROXY_MAX_THREADS = 200;

	/**
	 * How many recent events the in-memory buffer keeps. FIXME: Make configurable // 2026-08-29
	 */
	public static final int EVENT_BUFFER_CAPACITY = 1000;

	/**
	 * How long an instance stays out of rotation after a failed connect
	 * (cleared instantly by any successful response). mod_WebObjects calls
	 * this "dormant", same default. FIXME: Make configurable // 2026-08-29
	 */
	public static final Duration DEAD_COOLDOWN = Duration.ofSeconds( 30 );

	/**
	 * Minimum spacing between out-of-band adaptor-config refreshes (triggered
	 * by requests naming unknown apps/instances) — keeps a burst of requests
	 * for a nonexistent app from hammering wotaskd.
	 */
	public static final Duration FORCED_REFRESH_DEBOUNCE = Duration.ofSeconds( 3 );

	/**
	 * The port to the proxy will run on
	 */
	private final int _port;

	/**
	 * FIXME: We're going to want to hold multiple adaptor configuration
	 * sources — roadmap iteration 7's config-declared supervised apps are
	 * exactly this // Hugi 2025-05-02
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
	 * Hostname → the site's rewrite rules, for sites that have any. Applied
	 * to non-adaptor-URL requests before routing. Empty in plain-proxy mode.
	 */
	private Map<String, List<modulo.rewrite.RewriteRule>> _hostToRewrites = Map.of();

	/**
	 * The parsed sites config when the front-end is active, null in
	 * plain-proxy mode. Exposed for the overview page.
	 */
	private SitesConfig _sitesConfig;

	/**
	 * WOA compat: hostname → the site's .woa bundle path, for sites that
	 * declare one. Feeds {@link WebServerResourcesHandler}; empty otherwise.
	 */
	private Map<String, java.nio.file.Path> _hostToWoa = Map.of();

	/** WOA compat: live lookup for {@link WebServerResourcesHandler} — reads the field each call, so site reloads apply */
	private java.nio.file.Path woaForHost( final String host ) {
		return _hostToWoa.get( host );
	}

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
	private final EventLog _events = new EventLog( EVENT_BUFFER_CAPACITY );

	/** Per-application request counts and response times, minute-bucketed — feeds the admin dashboard. */
	private final modulo.stats.RequestStats _requestStats = new modulo.stats.RequestStats();

	/**
	 * Requests for hostnames not in the sites config, tallied per host —
	 * mostly scanner spam, but a forgotten alias shows up here too. The
	 * aggregate view of what deliberately doesn't reach the event stream.
	 */
	private final modulo.stats.HostTally _unknownHosts = new modulo.stats.HostTally();

	/** The proxy handler in use, kept so the admin UI can read the live proxy-client settings. */
	private ModuloProxy _proxy;

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
		this( port, frontendConfig, null );
	}

	/**
	 * @param bootstrap The root config file's restart-required tables
	 *            ([frontend]/[admin]/[wotaskd]) as parsed at startup — the
	 *            baseline reloads are diffed against. Null when running from
	 *            legacy properties (values then come from -D system
	 *            properties alone).
	 */
	public Modulo( final int port, final FrontendConfig frontendConfig, final modulo.config.BootstrapConfig bootstrap ) {
		_port = port;
		_frontendConfig = frontendConfig;
		_activeBootstrap = bootstrap;

		reloadAdaptorConfig();
	}

	/**
	 * The bootstrap values this instance is actually running with — set once
	 * at construction, never by reload (that's the point: reload warns when
	 * the file disagrees with these). Static because the wotaskd accessors
	 * are static; a JVM runs one Modulo.
	 */
	private static volatile modulo.config.BootstrapConfig _activeBootstrap;

	/**
	 * A -D system property overrides the config file — handy for local
	 * testing and the only source when running from legacy properties.
	 */
	private static String bootstrapValue( final String systemProperty, final Object configured ) {
		final String override = System.getProperty( systemProperty );
		if( override != null ) {
			return override;
		}
		if( configured != null ) {
			return String.valueOf( configured );
		}
		throw new IllegalStateException( "%s is set neither in the config file's [wotaskd] table nor as a system property".formatted( systemProperty ) );
	}

	/**
	 * @return The host wotaskd is running on
	 */
	public static String wotaskdHost() {
		final modulo.config.BootstrapConfig b = _activeBootstrap;
		return bootstrapValue( "modulo.wotaskd.host", b == null ? null : b.wotaskdHost() );
	}

	/**
	 * @return Port number to fetch wotaskd's configuration from
	 */
	public static int wotaskdPort() {
		final modulo.config.BootstrapConfig b = _activeBootstrap;
		return Integer.parseInt( bootstrapValue( "modulo.wotaskd.port", b == null ? null : b.wotaskdPort() ) );
	}

	/**
	 * @return The password for getting configuration from the targeted wotaskd instance
	 */
	public static String wotaskdPassword() {
		final modulo.config.BootstrapConfig b = _activeBootstrap;
		return bootstrapValue( "modulo.wotaskd.password", b == null ? null : b.wotaskdPassword() );
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
		threadPool.setMaxThreads( PLAIN_PROXY_MAX_THREADS );
		threadPool.setVirtualThreadsExecutor( Executors.newVirtualThreadPerTaskExecutor() );
		final Server server = new Server( threadPool );

		final HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.setSendServerVersion( false );

		final HttpConnectionFactory connectionFactory = new HttpConnectionFactory( httpConfig );
		final ServerConnector connector = new ServerConnector( server, connectionFactory );
		connector.setPort( _port );
		server.addConnector( connector );
		_proxy = new ModuloProxy( rewriteURIFunction(), _errorHandling, _events, _refusalObserver, _requestStats );
		// WOA compat (self-contained, easy to remove — see WebServerResourcesHandler)
		server.setHandler( new WebServerResourcesHandler( this::woaForHost, new WebSocketTunnelHandler( _proxy, rewriteURIFunction(), _errorHandling ) ) );
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
		_hostToRewrites = sitesConfig.hostToRewrites();
		_hostToWoa = sitesConfig.hostToWoa();
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
				// WOA compat (self-contained, easy to remove — see WebServerResourcesHandler)
				new WebServerResourcesHandler( this::woaForHost,
						new WebSocketTunnelHandler( _proxy = new ModuloProxy( rewriteURIFunction(), _errorHandling, _events, _refusalObserver, _requestStats ), rewriteURIFunction(), _errorHandling ) ),
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

		final SitesConfigReader.ParsedConfig parsed = SitesConfigReader.readWithBootstrap( _frontendConfig.sitesFile() );
		final SitesConfig newConfig = parsed.sites();
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
		_hostToRewrites = newConfig.hostToRewrites();
		_hostToWoa = newConfig.hostToWoa();

		logUnmappedDomains( sites );
		_acmeManager.checkNow();

		String summary = "Reloaded sites config: %d site(s), %d ACME-managed".formatted( sites.size(), newConfig.acmeManagedSites().size() );

		// Reload granularity is per setting, not per file: sites/acme just
		// applied, but the bootstrap tables only take effect at startup —
		// when the file now disagrees with the running values, say so
		// explicitly rather than silently ignoring the edit.
		if( _activeBootstrap != null ) {
			final List<String> changed = _activeBootstrap.changedSettings( parsed.bootstrap() );
			if( !changed.isEmpty() ) {
				summary += ". NOTE: restart-required setting(s) changed and NOT applied: %s — restart modulo to apply".formatted( String.join( ", ", changed ) );
			}
		}

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
	 * @return True if [host] is one of the sites config's hostnames — i.e.
	 *         something the operator deliberately configured, as opposed to
	 *         scanner/stray-DNS traffic
	 */
	private boolean isConfiguredSiteHost( final String host ) {
		final SitesConfig sitesConfig = _sitesConfig;
		if( sitesConfig == null || host == null ) {
			return false;
		}
		final String lowercased = host.toLowerCase( Locale.ROOT );
		return sitesConfig.frontendSites().stream().anyMatch( site -> site.allHostnames().contains( lowercased ) );
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

	/** When the last out-of-band (request-triggered) config refresh ran, for debouncing. */
	private volatile java.time.Instant _lastForcedRefresh = java.time.Instant.EPOCH;

	/**
	 * Re-polls wotaskd immediately, outside the regular poll interval —
	 * triggered by a request naming an app the current config doesn't know.
	 * Debounced so a burst of requests for a genuinely nonexistent app can't
	 * hammer wotaskd.
	 *
	 * @return True if a refresh was actually performed
	 */
	private synchronized boolean forceAdaptorConfigRefresh( final String applicationName ) {
		if( java.time.Instant.now().isBefore( _lastForcedRefresh.plus( FORCED_REFRESH_DEBOUNCE ) ) ) {
			return false;
		}
		_lastForcedRefresh = java.time.Instant.now();
		logger.info( "Request for unknown app {} — forcing an out-of-band adaptor config refresh", applicationName );
		_events.add( Event.Severity.INFO, "config-refresh-forced", null, applicationName, "Request named unknown app '%s'; adaptor config re-polled out of band".formatted( applicationName ) );
		try {
			reloadAdaptorConfig();
			return true;
		}
		catch( final RuntimeException e ) {
			logger.warn( "Forced adaptor config refresh failed (wotaskd down?): {}", e.toString() );
			return false;
		}
	}

	/** True while we're refusing to adopt an empty config over a populated one — for event-noise suppression. */
	private volatile boolean _skippingEmptyConfig = false;

	public void reloadAdaptorConfig() {
		final AdaptorConfig fetched = fetchAdaptorConfig();

		// A freshly restarted wotaskd reports an EMPTY config until its
		// instances re-register via lifebeats (~30s) — adopting it wholesale
		// turned a wotaskd deploy into a fleet-wide brownout. Never trade a
		// populated config for an empty one; the next poll picks up reality.
		if( fetched.applications().isEmpty() && _adaptorConfig != null && !_adaptorConfig.applications().isEmpty() ) {
			if( !_skippingEmptyConfig ) {
				_skippingEmptyConfig = true;
				logger.warn( "wotaskd returned an empty config while we hold {} app(s) — keeping the current config (wotaskd restarting?)", _adaptorConfig.applications().size() );
				_events.add( Event.Severity.WARN, "empty-config-skipped", null, null, "wotaskd returned an empty adaptor config; keeping the current one until it repopulates" );
			}
			return;
		}

		if( _skippingEmptyConfig && !fetched.applications().isEmpty() ) {
			_skippingEmptyConfig = false;
			logger.info( "wotaskd config repopulated with {} app(s)", fetched.applications().size() );
			_events.add( Event.Severity.INFO, "config-repopulated", null, null, "wotaskd's adaptor config repopulated with %d app(s)".formatted( fetched.applications().size() ) );
		}

		_adaptorConfig = fetched;

		// FIXME: Hardcoded modulo reference should not really be present —
		// its proper home is a second adaptor-config source (config-declared
		// apps, roadmap iteration 7) // Hugi 2026-01-28
		final Map<String, App> applications = new HashMap<>( _adaptorConfig.applications() );
		final App moduloApp = new App( "Modulo", List.of( new Instance( 1, "localhost", 45678 ) ) );
		applications.put( "Modulo", moduloApp );

		_adaptorConfig = new AdaptorConfig( applications );

		// Config-declared refusal: an instance flagged refuseNewSessions=YES
		// in the adaptor config is marked refusing for a few poll intervals —
		// continuously re-marked while the flag persists, expiring naturally
		// once it's removed. Same steering machinery as header-announced
		// refusal.
		for( final App application : applications.values() ) {
			for( final Instance instance : application.instances() ) {
				if( instance.refuseNewSessions() ) {
					markRefusingWithEvent( application.name(), instance.id(), (int)DEFAULT_CONFIG_RELOAD_DURATION.toSeconds() * 3 );
				}
			}
		}
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

	/**
	 * @return Per-application request statistics for the admin dashboard
	 */
	public modulo.stats.RequestStats requestStats() {
		return _requestStats;
	}

	/**
	 * @return The unknown-host tally, for the admin UI
	 */
	public modulo.stats.HostTally unknownHosts() {
		return _unknownHosts;
	}

	/**
	 * @return The proxy → upstream HttpClient (for reading its live settings
	 *         in the admin UI). Null before startup completes.
	 */
	public org.eclipse.jetty.client.HttpClient proxyHttpClient() {
		return _proxy == null ? null : _proxy.getHttpClient();
	}

	private void startAdaptorConfigAutoReloader() {
		final TimerTask adaptorConfigReloadTask = new TimerTask() {
			@Override
			public void run() {
				// A thrown exception would permanently cancel the Timer —
				// wotaskd being briefly down (a restart, a deploy) must not
				// stop config polling forever. The old config keeps serving.
				try {
					reloadAdaptorConfig();
				}
				catch( final RuntimeException e ) {
					logger.warn( "Adaptor config reload failed (wotaskd down?) — keeping current config: {}", e.toString() );
				}
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

	/** Selects which instance of an app serves each request (pinning + round-robin + refusal avoidance). */
	private final InstanceSelector _instanceSelector = new InstanceSelector();

	/**
	 * Invoked when an upstream response announces its instance is refusing
	 * new sessions: mark it so round-robin steers new traffic elsewhere
	 * (pinned session traffic keeps flowing so sessions drain), registering
	 * an event on the transition.
	 */
	private final ModuloProxy.RefusalObserver _refusalObserver = new ModuloProxy.RefusalObserver() {

		@Override
		public void refusing( final String applicationName, final int instanceId, final int timeoutSeconds ) {
			markRefusingWithEvent( applicationName, instanceId, timeoutSeconds );
			proofOfLife( applicationName, instanceId );
		}

		@Override
		public void accepting( final String applicationName, final int instanceId ) {
			final boolean transition = _instanceSelector.clearRefusing( applicationName, instanceId );
			if( transition ) {
				logger.info( "Instance {} of {} is accepting new sessions again", instanceId, applicationName );
				_events.add( Event.Severity.INFO, "instance-accepting", null, applicationName, "Instance %d is accepting new sessions again".formatted( instanceId ) );
			}
			proofOfLife( applicationName, instanceId );
		}

		@Override
		public void unreachable( final String applicationName, final int instanceId ) {
			final boolean transition = _instanceSelector.markDead( applicationName, instanceId, DEAD_COOLDOWN );
			if( transition ) {
				logger.warn( "Instance {} of {} is unreachable — out of rotation for {} (or until it responds)", instanceId, applicationName, DEAD_COOLDOWN );
				_events.add( Event.Severity.WARN, "instance-unreachable", null, applicationName, "Instance %d is unreachable; out of rotation for %ds unless it responds sooner".formatted( instanceId, DEAD_COOLDOWN.toSeconds() ) );
			}
		}
	};

	private void markRefusingWithEvent( final String applicationName, final int instanceId, final int timeoutSeconds ) {
		final boolean transition = _instanceSelector.markRefusing( applicationName, instanceId, Duration.ofSeconds( timeoutSeconds ) );
		if( transition ) {
			logger.info( "Instance {} of {} is refusing new sessions — steering new traffic to other instances", instanceId, applicationName );
			_events.add( Event.Severity.INFO, "instance-refusing", null, applicationName, "Instance %d is refusing new sessions; new traffic steered elsewhere while its sessions drain".formatted( instanceId ) );
		}
	}

	/** Any response from an instance clears its dead mark — refusing or not, it's alive. */
	private void proofOfLife( final String applicationName, final int instanceId ) {
		if( _instanceSelector.clearDead( applicationName, instanceId ) ) {
			logger.info( "Instance {} of {} responded — back in rotation", instanceId, applicationName );
			_events.add( Event.Severity.INFO, "instance-recovered", null, applicationName, "Instance %d responded and is back in rotation".formatted( instanceId ) );
		}
	}

	/**
	 * @return True if the given instance is currently marked refusing new sessions — for the admin UI
	 */
	public boolean instanceRefusing( final String applicationName, final int instanceId ) {
		return _instanceSelector.isRefusing( applicationName, instanceId );
	}

	/**
	 * @return True if the given instance is in its post-connect-failure cool-down — for the admin UI
	 */
	public boolean instanceDead( final String applicationName, final int instanceId ) {
		return _instanceSelector.isDead( applicationName, instanceId );
	}

	/**
	 * @return A Function that generates the instance URI to target
	 */
	public Function<Request, HttpURI> rewriteURIFunction() {
		return request -> {
			// Per-site rewrite rules map friendly URLs into adaptor URL space
			// before routing; a redirect-type rule surfaces as an exception the
			// proxy handler answers with a 301/302 instead of proxying.
			final HttpURI routingURI = applySiteRewrites( request.getHttpURI() );

			final RequestTarget target;
			try {
				target = targetFromURI( routingURI, this::appForHost, this::isConfiguredSiteHost );
			}
			catch( final ProxyRoutingException e ) {
				if( e.condition() == ErrorCondition.UNKNOWN_HOST ) {
					_unknownHosts.record( routingURI.getHost() );
				}
				throw e;
			}

			App application = _adaptorConfig.application( target.applicationName() );

			// A request naming an app (or an app with no instances yet) the
			// last config poll didn't know about may simply have raced a
			// fresh deployment — force an out-of-band re-poll (debounced)
			// and look again before failing. Cuts deployment turnaround from
			// "wait for the poll" to "first request finds it".
			if( application == null || application.instances().isEmpty() ) {
				if( forceAdaptorConfigRefresh( target.applicationName() ) ) {
					application = _adaptorConfig.application( target.applicationName() );
				}
			}

			if( application == null ) {
				throw new ProxyRoutingException( ErrorCondition.APP_UNAVAILABLE, "No application found with the name %s".formatted( target.applicationName() ) );
			}

			final List<Instance> instances = application.instances();

			if( instances.isEmpty() ) {
				throw new ProxyRoutingException( ErrorCondition.NO_INSTANCES, "No instances registered for application %s".formatted( target.applicationName() ) );
			}

			final Instance targetInstance;

			@SuppressWarnings( "unchecked" )
			final Set<Integer> attempted = (Set<Integer>)request.getAttribute( ModuloProxy.ATTEMPTED_INSTANCES_ATTRIBUTE );

			if( attempted != null && !attempted.isEmpty() ) {
				// Failover retry: the previous instance was unreachable — pick
				// among the not-yet-attempted; none left means the app is down.
				targetInstance = _instanceSelector.selectForRetry( application, attempted );
				if( targetInstance == null ) {
					throw new ProxyRoutingException( ErrorCondition.APP_UNAVAILABLE, "All %d instance(s) of %s were unreachable".formatted( instances.size(), application.name() ) );
				}
			}
			else {
				// The request is pinned to an instance by an explicit .woa/N/ URL
				// segment, or failing that by the woinst cookie (WO sessions are
				// instance-local). Unpinned requests are balanced round-robin.
				Integer requestedInstance = target.instanceNumber() != null ? target.instanceNumber() : woinstCookie( request );

				// A pin only matters for continuing a session. A session-less
				// request pinned to a refusing instance would just get bounced
				// (WO answers it with a rebalance redirect) — route it to a
				// willing instance directly instead.
				if( requestedInstance != null && _instanceSelector.isRefusing( target.applicationName(), requestedInstance ) && !hasSessionCookie( request ) ) {
					requestedInstance = null;
				}

				final InstanceSelector.Selection selection = _instanceSelector.select( application, requestedInstance );

				if( selection.fellBack() ) {
					logger.warn( "Instance {} of {} is no longer registered — rerouting to instance {}", requestedInstance, application.name(), selection.instance().id() );
					_events.add( Event.Severity.WARN, "instance-rerouted", routingURI.getHost(), application.name(),
							"Pinned instance %d is gone; request rerouted to instance %d (session lost)".formatted( requestedInstance, selection.instance().id() ) );
				}

				targetInstance = selection.instance();
			}

			// Record the routing decision so response observers (refusal/death
			// detection) can attribute upstream events to the right instance,
			// and track attempted instances for potential failover retries
			request.setAttribute( ModuloProxy.TARGET_APP_ATTRIBUTE, application.name() );
			request.setAttribute( ModuloProxy.TARGET_INSTANCE_ATTRIBUTE, targetInstance.id() );
			if( attempted == null ) {
				final Set<Integer> newAttempted = java.util.concurrent.ConcurrentHashMap.newKeySet();
				newAttempted.add( targetInstance.id() );
				request.setAttribute( ModuloProxy.ATTEMPTED_INSTANCES_ATTRIBUTE, newAttempted );
			}
			else {
				attempted.add( targetInstance.id() );
			}

			final String hostName = targetInstance.host();
			final int port = targetInstance.port();

			final HttpURI.Mutable targetURI = HttpURI
					.build( routingURI )
					.host( hostName )
					.scheme( HttpScheme.HTTP )
					.port( port );

			// Per-request logging belongs in the access log — this is debug-only tracing
			logger.debug( "Forwarding {} -> {}", routingURI, targetURI );

			return targetURI;
		};
	}

	/**
	 * Applies the site's rewrite rules (if any) to the given request URI.
	 * Paths already inside the adaptor URL space pass untouched — rewrites
	 * exist to map friendly URLs INTO it, so app-generated URLs can't be
	 * re-rewritten and a catch-all rule can't loop.
	 *
	 * @return The URI to route on: rewritten, or the original when no rule
	 *         matched
	 * @throws modulo.rewrite.RewriteRedirectException when a redirect-type
	 *             rule matches
	 */
	private HttpURI applySiteRewrites( final HttpURI uri ) {
		final String host = uri.getHost();
		final String path = uri.getPath();

		if( host == null || path == null || path.startsWith( ADAPTOR_URL ) ) {
			return uri;
		}

		final List<modulo.rewrite.RewriteRule> rules = _hostToRewrites.get( host.toLowerCase( java.util.Locale.ROOT ) );

		if( rules == null ) {
			return uri;
		}

		final modulo.rewrite.RewriteRule.Result result = modulo.rewrite.RewriteRule.firstMatch( rules, path, uri.getQuery() );

		return switch( result ) {
			case null -> uri;
			case modulo.rewrite.RewriteRule.Rewritten rewritten -> {
				final HttpURI rewrittenURI = HttpURI.build( uri ).path( rewritten.path() ).query( rewritten.query() ).asImmutable();
				logger.debug( "Rewrote {} -> {}", uri, rewrittenURI );
				yield rewrittenURI;
			}
			case modulo.rewrite.RewriteRule.Redirected redirected -> throw new modulo.rewrite.RewriteRedirectException( redirected.location(), redirected.permanent() );
		};
	}

	/**
	 * The application a request routes to, plus the instance the URL pins it
	 * to ({@code .woa/N/} segment) — null when the URL carries no instance.
	 */
	record RequestTarget( String applicationName, Integer instanceNumber ) {}

	/**
	 * @param appForHost Resolves a hostname to the app serving it (null when unknown)
	 * @return The application (and URL-pinned instance, if any) targeted by the given URI
	 */
	static RequestTarget targetFromURI( final HttpURI uri, final Function<String, String> appForHost, final java.util.function.Predicate<String> isConfiguredSiteHost ) {

		final String uriString = uri.getPath();

		if( !uriString.startsWith( ADAPTOR_URL ) ) {
			final String host = uri.getHost();

			final String domainDefaultAppName = appForHost.apply( host );

			if( domainDefaultAppName != null ) {
				return new RequestTarget( domainDefaultAppName, null );
			}

			// Two very different failures: a configured Site with no app is
			// an operational signal; a hostname we've never heard of is
			// scanner/spam noise (once it flooded the event stream 107:1).
			if( host != null && isConfiguredSiteHost.test( host ) ) {
				throw new ProxyRoutingException( ErrorCondition.NO_APP_FOR_HOST, "Host '%s' is a configured site but has no app mapped".formatted( host ) );
			}
			throw new ProxyRoutingException( ErrorCondition.UNKNOWN_HOST, "The uri '%s' does not start with an adaptor URL and host '%s' is not a configured site".formatted( uriString, host ) );
		}

		String appName = uriString.substring( ADAPTOR_URL.length() );

		final int periodIndex = appName.indexOf( ".woa" );

		// Extensionless URLs (/<prefix>/AppName or /<prefix>/AppName/...) —
		// the app name is everything up to the first slash
		if( periodIndex == -1 ) {
			final int slashIndex = appName.indexOf( '/' );

			if( slashIndex != -1 ) {
				appName = appName.substring( 0, slashIndex );
			}

			return new RequestTarget( appName, null );
		}

		// A numeric segment right after ".woa/" pins the request to that
		// instance (mod_WebObjects convention). A leading '-' is allowed —
		// negative numbers are wotaskd's ids for unregistered instances,
		// which are reachable only via this explicit pin.
		final String afterWoa = appName.substring( periodIndex + ".woa".length() );
		appName = appName.substring( 0, periodIndex );

		if( afterWoa.startsWith( "/" ) ) {
			final int nextSlash = afterWoa.indexOf( '/', 1 );
			final String segment = nextSlash == -1 ? afterWoa.substring( 1 ) : afterWoa.substring( 1, nextSlash );
			if( isInstanceNumber( segment ) ) {
				return new RequestTarget( appName, Integer.valueOf( segment ) );
			}
		}

		return new RequestTarget( appName, null );
	}

	/**
	 * @return The instance number from the request's {@code woinst} cookie,
	 *         null when absent or unparsable. WO traditionally quotes the
	 *         cookie value.
	 */
	/**
	 * @return True if the request carries a session cookie — classic WO
	 *         ({@code wosid}) or ng-objects ({@code ngsid})
	 */
	static boolean hasSessionCookie( final Request request ) {
		for( final org.eclipse.jetty.http.HttpCookie cookie : Request.getCookies( request ) ) {
			if( ("wosid".equals( cookie.getName() ) || "ngsid".equals( cookie.getName() )) && !cookie.getValue().isBlank() ) {
				return true;
			}
		}
		return false;
	}

	static Integer woinstCookie( final Request request ) {
		for( final org.eclipse.jetty.http.HttpCookie cookie : Request.getCookies( request ) ) {
			if( "woinst".equals( cookie.getName() ) ) {
				final String value = cookie.getValue().replace( "\"", "" ).trim();
				// -1 means "instance unknown" — not a pin. Other negative
				// values pin to unregistered instances, positives to regular.
				if( isInstanceNumber( value ) && !"-1".equals( value ) ) {
					return Integer.valueOf( value );
				}
				return null;
			}
		}
		return null;
	}

	/**
	 * @return True if [value] is a valid instance number: digits with an
	 *         optional leading '-' (wotaskd ids unregistered instances with
	 *         negative numbers)
	 */
	private static boolean isInstanceNumber( final String value ) {
		final String digits = value.startsWith( "-" ) ? value.substring( 1 ) : value;
		return !digits.isEmpty() && digits.chars().allMatch( Character::isDigit );
	}
}