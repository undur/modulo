package modulo.runner;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import modulo.Modulo;
import modulo.frontend.FrontendConfig;
import modulo.frontend.JettyFrontend;
import modulo.frontend.tls.CertStore;
import modulo.frontend.tls.acme.AcmeManager;
import ng.appserver.NGApplication;
import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

/**
 * The admin start page: a glance at what this modulo is and is doing.
 * The details live on the other pages — /adaptor for apps/instances,
 * /overview for sites and certificates.
 */
public class MDStartPage extends NGComponent {

	private static final DateTimeFormatter STARTED_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm 'UTC'" ).withZone( ZoneOffset.UTC );

	public MDStartPage( NGContext context ) {
		super( context );
	}

	private Modulo modulo() {
		return ((Application)NGApplication.application()).modulo();
	}

	public String started() {
		final Instant startedAt = Application.startedAt();
		return "%s (up %s)".formatted( STARTED_FORMAT.format( startedAt ), humanDuration( Duration.between( startedAt, Instant.now() ) ) );
	}

	public String proxyPort() {
		return String.valueOf( Config.MODULO_PROXY_PORT );
	}

	public String frontendStatus() {
		if( modulo().sitesConfig() == null ) {
			return "not active — plain reverse proxy only";
		}
		return "active — %d site(s), %d ACME-managed".formatted( modulo().sitesConfig().sites().size(), modulo().sitesConfig().acmeManagedSites().size() );
	}

	public String applicationCount() {
		return "%d app(s) known to wotaskd".formatted( modulo().adaptorConfig().applications().size() );
	}

	private static String humanDuration( final Duration duration ) {
		final long days = duration.toDays();
		if( days > 0 ) {
			return "%dd %dh".formatted( days, duration.toHoursPart() );
		}
		if( duration.toHours() > 0 ) {
			return "%dh %dm".formatted( duration.toHours(), duration.toMinutesPart() );
		}
		if( duration.toMinutes() > 0 ) {
			return "%dm".formatted( duration.toMinutes() );
		}
		return "%ds".formatted( duration.toSeconds() );
	}

	// ------------------------------------------------------------------
	// The configuration inventory: every knob the server runs with —
	// configured values and hardcoded "sensible defaults" alike. Values
	// reference the actual constants/live objects so the display can't
	// drift from reality. The "not yet" rows double as the to-do list for
	// the future config surface.
	// ------------------------------------------------------------------

	public SettingRow currentSetting;

	public record SettingRow( String group, String name, String value, String source, String configurable ) {}

	public List<SettingRow> settings() {
		final List<SettingRow> rows = new java.util.ArrayList<>();
		final Modulo modulo = modulo();
		final FrontendConfig frontend = modulo.frontendConfig();
		final var acme = modulo.sitesConfig() == null ? null : modulo.sitesConfig().acme();
		final org.eclipse.jetty.client.HttpClient proxyClient = modulo.proxyHttpClient();

		// Proxy
		rows.add( new SettingRow( "Proxy", "Plain proxy port", String.valueOf( Config.MODULO_PROXY_PORT ), "-Dmodulo.proxy-port", "yes" ) );
		rows.add( new SettingRow( "Proxy", "Adaptor URL prefix", Modulo.ADAPTOR_URL, "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "Proxy", "Adaptor config reload interval", humanDuration( Modulo.DEFAULT_CONFIG_RELOAD_DURATION ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "Proxy", "Instance selection", "URL pin → woinst cookie → round-robin", "hardcoded behavior", "strategy not yet (round-robin only)" ) );
		rows.add( new SettingRow( "Proxy", "Worker threads, max (plain)", String.valueOf( Modulo.PLAIN_PROXY_MAX_THREADS ), "hardcoded", "not yet" ) );
		if( proxyClient != null ) {
			rows.add( new SettingRow( "Proxy", "Upstream connect timeout", humanDuration( Duration.ofMillis( proxyClient.getConnectTimeout() ) ), "Jetty default", "not yet" ) );
			rows.add( new SettingRow( "Proxy", "Upstream idle timeout", proxyClient.getIdleTimeout() <= 0 ? "unlimited" : humanDuration( Duration.ofMillis( proxyClient.getIdleTimeout() ) ), "Jetty default", "not yet" ) );
			rows.add( new SettingRow( "Proxy", "Upstream connections per destination, max", String.valueOf( proxyClient.getMaxConnectionsPerDestination() ), "Jetty default", "not yet" ) );
			rows.add( new SettingRow( "Proxy", "Upstream request queue per destination, max", String.valueOf( proxyClient.getMaxRequestsQueuedPerDestination() ), "Jetty default", "not yet" ) );
			rows.add( new SettingRow( "Proxy", "Proxy request buffer size", proxyClient.getRequestBufferSize() + " bytes", "Jetty default", "not yet" ) );
			rows.add( new SettingRow( "Proxy", "Proxy response buffer size", proxyClient.getResponseBufferSize() + " bytes", "Jetty default", "not yet" ) );
		}

		// Front-end
		if( frontend != null ) {
			rows.add( new SettingRow( "Front-end", "HTTP port", String.valueOf( frontend.httpPort() ), "modulo.conf", "yes" ) );
			rows.add( new SettingRow( "Front-end", "HTTPS port", String.valueOf( frontend.httpsPort() ), "modulo.conf", "yes" ) );
			rows.add( new SettingRow( "Front-end", "HTTP/3", frontend.http3() ? "enabled" : "disabled", "modulo.conf", "yes" ) );
			rows.add( new SettingRow( "Front-end", "Sites config file", String.valueOf( frontend.sitesFile() ), "modulo.conf", "yes" ) );
			rows.add( new SettingRow( "Front-end", "Access log directory", frontend.accessLogDir() == null ? "disabled" : frontend.accessLogDir().toString(), "modulo.conf", "yes" ) );
			rows.add( new SettingRow( "Front-end", "Access log retention", JettyFrontend.ACCESS_LOG_RETAIN_DAYS + " days", "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "Worker threads, max", String.valueOf( JettyFrontend.MAX_THREADS ), "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "Cert file poll interval", humanDuration( CertStore.DEFAULT_POLL_INTERVAL ), "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "gzip compression level", String.valueOf( JettyFrontend.COMPRESSION_LEVEL ), "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "gzip mime types", String.join( ", ", JettyFrontend.COMPRESS_MIME_TYPES ), "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "gzip methods", JettyFrontend.COMPRESS_GET_ONLY ? "GET only" : "all", "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "Alt-Svc max-age (h3)", humanDuration( Duration.ofSeconds( JettyFrontend.ALT_SVC_MAX_AGE_SECONDS ) ), "hardcoded", "not yet" ) );
		}

		// ACME
		if( acme != null ) {
			rows.add( new SettingRow( "ACME", "Directory", acme.directoryUri().toString(), "sites.json", "yes" ) );
			rows.add( new SettingRow( "ACME", "Contact", acme.email(), "sites.json", "yes" ) );
			rows.add( new SettingRow( "ACME", "Storage", acme.storageDir().toString(), "sites.json", "yes" ) );
		}
		rows.add( new SettingRow( "ACME", "Renewal check interval", humanDuration( AcmeManager.CHECK_INTERVAL ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "ACME", "Renew before expiry", humanDuration( AcmeManager.RENEW_BEFORE_EXPIRY ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "ACME", "Challenge timeout", humanDuration( AcmeManager.CHALLENGE_TIMEOUT ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "ACME", "Order timeout", humanDuration( AcmeManager.ORDER_TIMEOUT ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "ACME", "Key type", "EC secp256r1", "hardcoded", "not yet" ) );

		// Admin
		rows.add( new SettingRow( "Admin", "Admin password", ((Application)NGApplication.application()).adminPasswordConfigured() ? "set" : "not set", "modulo.conf", "yes" ) );
		rows.add( new SettingRow( "Admin", "Event buffer capacity", String.valueOf( Modulo.EVENT_BUFFER_CAPACITY ), "hardcoded", "not yet" ) );

		return rows;
	}
}
