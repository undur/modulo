package modulo.runner;

import java.time.Duration;
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
 * The configuration inventory: every knob the server runs with — configured
 * values and hardcoded "sensible defaults" alike. Values reference the
 * actual constants and live objects so the display can't drift from
 * reality. The "not yet" rows double as the to-do list for the future
 * config surface (and one day, parts of this page may become editable).
 */
public class MDConfigPage extends NGComponent {

	public SettingRow currentSetting;
	public SettingGroup currentGroup;

	public MDConfigPage( NGContext context ) {
		super( context );
	}

	public record SettingRow( String group, String name, String value, String source, String configurable ) {}

	public record SettingGroup( String name, List<SettingRow> rows ) {}

	private Modulo modulo() {
		return ((Application)NGApplication.application()).modulo();
	}

	/**
	 * @return The settings grouped for display — each group renders as a
	 *         header row spanning the table
	 */
	public List<SettingGroup> settingGroups() {
		final java.util.LinkedHashMap<String, List<SettingRow>> byGroup = new java.util.LinkedHashMap<>();
		for( final SettingRow row : settings() ) {
			byGroup.computeIfAbsent( row.group(), name -> new java.util.ArrayList<>() ).add( row );
		}
		return byGroup.entrySet().stream().map( entry -> new SettingGroup( entry.getKey(), entry.getValue() ) ).toList();
	}

	public List<SettingRow> settings() {
		final List<SettingRow> rows = new java.util.ArrayList<>();
		final Modulo modulo = modulo();
		final FrontendConfig frontend = modulo.frontendConfig();
		final var acme = modulo.sitesConfig() == null ? null : modulo.sitesConfig().acme();
		final org.eclipse.jetty.client.HttpClient proxyClient = modulo.proxyHttpClient();

		// Adaptor / wotaskd
		if( !Modulo.isTesting() ) {
			rows.add( new SettingRow( "Adaptor", "wotaskd host", Modulo.wotaskdHost(), "-Dmodulo.wotaskd.host", "yes" ) );
			rows.add( new SettingRow( "Adaptor", "wotaskd port", String.valueOf( Modulo.wotaskdPort() ), "-Dmodulo.wotaskd.port", "yes" ) );
			rows.add( new SettingRow( "Adaptor", "wotaskd password", "set", "-Dmodulo.wotaskd.password", "yes" ) );
		}
		rows.add( new SettingRow( "Adaptor", "Adaptor URL prefix", Modulo.ADAPTOR_URL, "-Dmodulo.adaptor-url", "yes" ) );
		rows.add( new SettingRow( "Adaptor", "Adaptor config reload interval", MDStartPage.humanDuration( Modulo.DEFAULT_CONFIG_RELOAD_DURATION ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "Adaptor", "Forced config-refresh debounce", MDStartPage.humanDuration( Modulo.FORCED_REFRESH_DEBOUNCE ), "hardcoded", "not yet" ) );

		// Proxy
		rows.add( new SettingRow( "Proxy", "Plain proxy port", String.valueOf( Config.MODULO_PROXY_PORT ), "-Dmodulo.proxy-port", "yes" ) );
		rows.add( new SettingRow( "Proxy", "Instance selection", "URL pin → woinst cookie → round-robin", "hardcoded behavior", "strategy not yet (round-robin only)" ) );
		rows.add( new SettingRow( "Proxy", "Dead-instance cool-down", MDStartPage.humanDuration( Modulo.DEAD_COOLDOWN ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "Proxy", "Failover", "unreachable instances retried across remaining (body-less requests)", "hardcoded behavior", "not yet" ) );
		rows.add( new SettingRow( "Proxy", "Worker threads, max (plain)", String.valueOf( Modulo.PLAIN_PROXY_MAX_THREADS ), "hardcoded", "not yet" ) );
		if( proxyClient != null ) {
			rows.add( new SettingRow( "Proxy", "Upstream connect timeout", MDStartPage.humanDuration( Duration.ofMillis( proxyClient.getConnectTimeout() ) ), "Jetty default", "not yet" ) );
			rows.add( new SettingRow( "Proxy", "Upstream idle timeout", proxyClient.getIdleTimeout() <= 0 ? "unlimited" : MDStartPage.humanDuration( Duration.ofMillis( proxyClient.getIdleTimeout() ) ), "Jetty default", "not yet" ) );
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
			rows.add( new SettingRow( "Front-end", "Cert file poll interval", MDStartPage.humanDuration( CertStore.DEFAULT_POLL_INTERVAL ), "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "gzip compression level", String.valueOf( JettyFrontend.COMPRESSION_LEVEL ), "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "gzip mime types", String.join( ", ", JettyFrontend.COMPRESS_MIME_TYPES ), "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "gzip methods", JettyFrontend.COMPRESS_GET_ONLY ? "GET only" : "all", "hardcoded", "not yet" ) );
			rows.add( new SettingRow( "Front-end", "Alt-Svc max-age (h3)", MDStartPage.humanDuration( Duration.ofSeconds( JettyFrontend.ALT_SVC_MAX_AGE_SECONDS ) ), "hardcoded", "not yet" ) );
		}

		// ACME
		if( acme != null ) {
			rows.add( new SettingRow( "ACME", "Directory", acme.directoryUri().toString(), "sites.json", "yes" ) );
			rows.add( new SettingRow( "ACME", "Contact", acme.email(), "sites.json", "yes" ) );
			rows.add( new SettingRow( "ACME", "Storage", acme.storageDir().toString(), "sites.json", "yes" ) );
		}
		rows.add( new SettingRow( "ACME", "Renewal check interval", MDStartPage.humanDuration( AcmeManager.CHECK_INTERVAL ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "ACME", "Renew before expiry", MDStartPage.humanDuration( AcmeManager.RENEW_BEFORE_EXPIRY ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "ACME", "Challenge timeout", MDStartPage.humanDuration( AcmeManager.CHALLENGE_TIMEOUT ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "ACME", "Order timeout", MDStartPage.humanDuration( AcmeManager.ORDER_TIMEOUT ), "hardcoded", "not yet" ) );
		rows.add( new SettingRow( "ACME", "Key type", "EC secp256r1", "hardcoded", "not yet" ) );

		// Admin
		rows.add( new SettingRow( "Admin", "modulo.conf location", System.getProperty( "modulo.config-file", "/opt/webobjects/modulo.conf" ), "-Dmodulo.config-file", "yes" ) );
		rows.add( new SettingRow( "Admin", "Admin password", ((Application)NGApplication.application()).adminPasswordConfigured() ? "set" : "not set", "modulo.conf", "yes" ) );
		rows.add( new SettingRow( "Admin", "Event buffer capacity", String.valueOf( Modulo.EVENT_BUFFER_CAPACITY ), "hardcoded", "not yet" ) );

		return rows;
	}
}
