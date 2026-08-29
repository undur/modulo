package modulo.runner;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import modulo.Modulo;
import modulo.config.SitesConfig;
import modulo.config.SitesConfig.ConfiguredSite;
import modulo.frontend.FrontendConfig;
import ng.appserver.NGApplication;
import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

/**
 * Configuration overview: the front-end setup, ACME settings and every
 * configured site with its routing target and certificate state.
 *
 * Display-only. Reached via /overview, which is open in development mode
 * and guarded by the modulo.admin-password property in production (see
 * {@link Application}).
 */
public class MDOverviewPage extends NGComponent {

	private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd" ).withZone( ZoneOffset.UTC );

	public SiteRow currentSite;

	public MDOverviewPage( NGContext context ) {
		super( context );
	}

	/**
	 * One row in the sites table. A record so the template can reach the
	 * components via keypaths.
	 */
	public record SiteRow(
			String canonicalHostname,
			String aliases,
			String app,
			boolean appKnown,
			String tlsMode,
			String certExpiry,
			long certDaysLeft,
			boolean certExpiringSoon,
			String redirects ) {}

	private Modulo modulo() {
		return ((Application)NGApplication.application()).modulo();
	}

	public boolean hasSitesConfig() {
		return modulo().sitesConfig() != null;
	}

	public boolean hasAcme() {
		return hasSitesConfig() && modulo().sitesConfig().acme() != null;
	}

	public String sitesFile() {
		final FrontendConfig config = modulo().frontendConfig();
		return config == null || config.sitesFile() == null ? "—" : config.sitesFile().toString();
	}

	public String ports() {
		final FrontendConfig config = modulo().frontendConfig();
		return config == null ? "—" : "http %d / https %d".formatted( config.httpPort(), config.httpsPort() );
	}

	public String acmeEmail() {
		return hasAcme() ? modulo().sitesConfig().acme().email() : "—";
	}

	public String acmeDirectory() {
		return hasAcme() ? modulo().sitesConfig().acme().directoryUri().toString() : "—";
	}

	public String acmeStorage() {
		return hasAcme() ? modulo().sitesConfig().acme().storageDir().toString() : "—";
	}

	public List<SiteRow> sites() {
		final SitesConfig sitesConfig = modulo().sitesConfig();

		if( sitesConfig == null ) {
			return List.of();
		}

		return sitesConfig.sites().stream().map( this::toRow ).toList();
	}

	private SiteRow toRow( final ConfiguredSite configuredSite ) {
		final var site = configuredSite.site();

		String certExpiry = "—";
		long daysLeft = -1;
		try {
			final byte[] pem = Files.readAllBytes( site.certPath() );
			final X509Certificate cert = (X509Certificate)CertificateFactory.getInstance( "X.509" ).generateCertificate( new ByteArrayInputStream( pem ) );
			final Instant notAfter = cert.getNotAfter().toInstant();
			daysLeft = ChronoUnit.DAYS.between( Instant.now(), notAfter );
			certExpiry = EXPIRY_FORMAT.format( notAfter );
		}
		catch( final Exception e ) {
			certExpiry = "unreadable";
		}

		final String redirects = (site.httpsRedirect() ? "https" : "") + (site.httpsRedirect() && site.canonicalRedirect() ? " + " : "") + (site.canonicalRedirect() ? "canonical" : "");

		return new SiteRow(
				site.primaryHostname(),
				String.join( ", ", site.aliases() ),
				configuredSite.app() == null ? "—" : configuredSite.app(),
				configuredSite.app() == null || modulo().adaptorConfig().application( configuredSite.app() ) != null,
				configuredSite.acmeManaged() ? "acme" : "manual",
				certExpiry,
				daysLeft,
				daysLeft >= 0 && daysLeft < 14,
				redirects.isEmpty() ? "—" : redirects );
	}
}
