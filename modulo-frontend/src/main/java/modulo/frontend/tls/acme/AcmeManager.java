package modulo.frontend.tls.acme;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.frontend.site.Site;

/**
 * Obtains and renews certificates for ACME-managed Sites via HTTP-01,
 * replacing certbot.
 *
 * Design notes:
 *
 * <ul>
 * <li><b>Certificates live on disk</b> ({@code <storage>/sites/<host>/cert.pem}
 * + {@code key.pem}) and the managed Sites' certPath/keyPath point at them, so
 * the existing {@link modulo.frontend.tls.CertStore} loading/hot-reload works
 * unchanged, and operators can inspect certs with the usual openssl tooling.</li>
 * <li><b>No startup-order gymnastics.</b> A managed site with no cert on disk
 * gets a self-signed placeholder before the keystore is built, so the TLS
 * connector always starts immediately. The real certificate is ordered in the
 * background right after the server is up (the HTTP connector must be
 * answering challenges by then) and hot-swapped in via a CertStore reload.
 * Initial issuance and renewal are thereby the same code path.</li>
 * <li><b>Needs-issuance check:</b> cert file missing, certificate self-signed
 * (i.e. a placeholder), expiring within {@link #RENEW_BEFORE_EXPIRY}, or not
 * covering all of the site's current hostnames (an alias was added to the
 * config).</li>
 * <li><b>Challenges are answered from memory</b> — {@link #challengeContent}
 * is consulted by the front-end's ACME challenge handler; no webroot files.</li>
 * <li><b>Failures are non-fatal:</b> an issuance failure is logged loudly and
 * retried on the next cycle; the site keeps serving whatever cert it has.</li>
 * </ul>
 */
public class AcmeManager {

	private static final Logger logger = LoggerFactory.getLogger( AcmeManager.class );

	// acme4j's KeyPairUtils requests the "BC" JCA provider by name; having
	// Bouncy Castle on the classpath isn't enough — it must be registered
	static {
		if( Security.getProvider( "BC" ) == null ) {
			Security.addProvider( new BouncyCastleProvider() );
		}
	}

	/** Renew when less than this remains of the certificate's validity. Let's Encrypt certs live 90 days; 30 leaves ample retry room. */
	private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofDays( 30 );

	/** How often the renewal check runs. */
	private static final Duration CHECK_INTERVAL = Duration.ofHours( 12 );

	private static final Duration CHALLENGE_TIMEOUT = Duration.ofMinutes( 3 );
	private static final Duration ORDER_TIMEOUT = Duration.ofMinutes( 3 );

	/** May be null while no site is ACME-managed. Swappable via {@link #update} for config reload. */
	private volatile AcmeSettings settings;

	/** Swappable via {@link #update} for config reload. */
	private volatile List<Site> managedSites;

	/** token → key authorization, for challenges currently in flight. */
	private final Map<String, String> challengeTokens = new ConcurrentHashMap<>();

	private Runnable afterCertsChanged;
	private Timer timer;

	public AcmeManager( final AcmeSettings settings, final List<Site> managedSites ) {
		this.settings = settings;
		this.managedSites = List.copyOf( managedSites );
	}

	/**
	 * Replaces the ACME settings and managed Site list — the config reload
	 * path. Follow with {@link #ensurePlaceholders()} (so new sites can enter
	 * the keystore) and {@link #checkNow()} (so they get real certificates).
	 */
	public void update( final AcmeSettings newSettings, final List<Site> newManagedSites ) {
		settings = newSettings;
		managedSites = List.copyOf( newManagedSites );
	}

	/**
	 * Schedules an immediate issuance/renewal pass on the background timer
	 * (serialized with the periodic passes). No-op if {@link #start} hasn't
	 * run yet — the initial pass will cover it.
	 */
	public synchronized void checkNow() {
		if( timer != null ) {
			timer.schedule( new TimerTask() {
				@Override
				public void run() {
					try {
						checkAndIssue();
					}
					catch( final RuntimeException e ) {
						logger.error( "ACME check pass failed", e );
					}
				}
			}, 0 );
		}
	}

	/**
	 * The in-memory HTTP-01 answer: content to serve for
	 * {@code /.well-known/acme-challenge/<token>}, or null if the token is
	 * unknown. Wired into the front-end's challenge handler.
	 */
	public String challengeContent( final String token ) {
		return challengeTokens.get( token );
	}

	/**
	 * Writes a self-signed placeholder cert+key for every managed site that
	 * has no certificate on disk yet. Called before the keystore is built so
	 * the TLS connector can start without waiting for the CA.
	 */
	public void ensurePlaceholders() throws IOException {
		for( final Site site : managedSites ) {
			if( !Files.isRegularFile( site.certPath() ) || !Files.isRegularFile( site.keyPath() ) ) {
				logger.info( "No certificate on disk for {} yet — writing self-signed placeholder", site.primaryHostname() );
				SelfSignedCertificates.writePlaceholder( site.certPath(), site.keyPath(), site.primaryHostname(), site.allHostnames() );
			}
		}
	}

	/**
	 * Starts the background issuance/renewal loop: an immediate pass (initial
	 * issuance for placeholder'd sites), then a check every
	 * {@link #CHECK_INTERVAL}.
	 *
	 * @param afterCertsChanged Invoked after a pass that obtained at least one
	 *            certificate — typically the CertStore's reload trigger.
	 */
	public synchronized void start( final Runnable afterCertsChanged ) {
		if( timer != null ) {
			return;
		}
		this.afterCertsChanged = afterCertsChanged;
		timer = new Timer( "modulo-acme", true );
		timer.schedule( new TimerTask() {
			@Override
			public void run() {
				try {
					checkAndIssue();
				}
				catch( final RuntimeException e ) {
					logger.error( "ACME check pass failed", e );
				}
			}
		}, 0, CHECK_INTERVAL.toMillis() );
	}

	public synchronized void stop() {
		if( timer != null ) {
			timer.cancel();
			timer = null;
		}
	}

	/**
	 * One pass over the managed sites: order a certificate for every site
	 * that needs one. The ACME session/account is only established when at
	 * least one site actually needs work.
	 */
	void checkAndIssue() {
		Account account = null;
		boolean changed = false;

		for( final Site site : managedSites ) {
			try {
				if( !needsIssuance( site ) ) {
					continue;
				}
				if( account == null ) {
					account = account();
				}
				issue( site, account );
				changed = true;
			}
			catch( final InterruptedException e ) {
				Thread.currentThread().interrupt();
				return;
			}
			catch( final Exception e ) {
				logger.error( "ACME issuance failed for site {} — will retry within {}: {}", site.primaryHostname(), CHECK_INTERVAL, e.toString() );
			}
		}

		if( changed && afterCertsChanged != null ) {
			afterCertsChanged.run();
		}
	}

	private boolean needsIssuance( final Site site ) {
		if( !Files.isRegularFile( site.certPath() ) || !Files.isRegularFile( site.keyPath() ) ) {
			return true;
		}

		final X509Certificate cert;
		try {
			cert = readFirstCertificate( site.certPath() );
		}
		catch( final Exception e ) {
			logger.warn( "Could not parse certificate for {} ({}) — will reissue: {}", site.primaryHostname(), site.certPath(), e.toString() );
			return true;
		}

		if( isSelfSigned( cert ) ) {
			logger.info( "Site {} is serving a self-signed placeholder — ordering a real certificate", site.primaryHostname() );
			return true;
		}
		if( isExpiringSoon( cert, Instant.now() ) ) {
			logger.info( "Certificate for {} expires {} — renewing", site.primaryHostname(), cert.getNotAfter() );
			return true;
		}
		if( !coversHostnames( cert, site.allHostnames() ) ) {
			logger.info( "Certificate for {} does not cover all configured hostnames {} — reissuing", site.primaryHostname(), site.allHostnames() );
			return true;
		}
		return false;
	}

	static boolean isSelfSigned( final X509Certificate cert ) {
		return cert.getSubjectX500Principal().equals( cert.getIssuerX500Principal() );
	}

	static boolean isExpiringSoon( final X509Certificate cert, final Instant now ) {
		return cert.getNotAfter().toInstant().isBefore( now.plus( RENEW_BEFORE_EXPIRY ) );
	}

	static boolean coversHostnames( final X509Certificate cert, final List<String> hostnames ) {
		final Set<String> sans = new HashSet<>();
		try {
			final var sanEntries = cert.getSubjectAlternativeNames();
			if( sanEntries != null ) {
				for( final List<?> entry : sanEntries ) {
					if( Integer.valueOf( 2 ).equals( entry.get( 0 ) ) ) { // 2 = dNSName
						sans.add( ((String)entry.get( 1 )).toLowerCase( Locale.ROOT ) );
					}
				}
			}
		}
		catch( final Exception e ) {
			return false;
		}
		return hostnames.stream().map( h -> h.toLowerCase( Locale.ROOT ) ).allMatch( sans::contains );
	}

	private static X509Certificate readFirstCertificate( final Path certPath ) throws Exception {
		final byte[] pem = Files.readAllBytes( certPath );
		return (X509Certificate)CertificateFactory.getInstance( "X.509" ).generateCertificate( new ByteArrayInputStream( pem ) );
	}

	/**
	 * Loads (or creates on first run) the ACME account keypair, and
	 * registers/retrieves the account at the CA. Re-running with an already
	 * registered key just returns the existing account.
	 */
	private Account account() throws AcmeException, IOException {
		final KeyPair accountKeys;
		final Path keyPath = settings.accountKeyPath();

		if( Files.isRegularFile( keyPath ) ) {
			try( var reader = Files.newBufferedReader( keyPath, StandardCharsets.UTF_8 ) ) {
				accountKeys = KeyPairUtils.readKeyPair( reader );
			}
		}
		else {
			logger.info( "No ACME account key at {} — creating one", keyPath );
			accountKeys = KeyPairUtils.createECKeyPair( "secp256r1" );
			Files.createDirectories( keyPath.getParent() );
			try( var writer = Files.newBufferedWriter( keyPath, StandardCharsets.UTF_8 ) ) {
				KeyPairUtils.writeKeyPair( accountKeys, writer );
			}
		}

		final Session session = new Session( settings.directoryUri() );
		return new AccountBuilder()
				.addEmail( settings.email() )
				.agreeToTermsOfService()
				.useKeyPair( accountKeys )
				.create( session );
	}

	/**
	 * Orders, authorizes (HTTP-01) and downloads a certificate for the site,
	 * then writes chain + fresh private key to the site's PEM paths.
	 */
	private void issue( final Site site, final Account account ) throws AcmeException, IOException, InterruptedException {
		logger.info( "Ordering certificate for {} covering {}", site.primaryHostname(), site.allHostnames() );

		final Order order = account.newOrder().domains( site.allHostnames() ).create();

		for( final Authorization auth : order.getAuthorizations() ) {
			if( auth.getStatus() == Status.VALID ) {
				continue;
			}
			final Http01Challenge challenge = auth.findChallenge( Http01Challenge.class )
					.orElseThrow( () -> new AcmeException( "CA offered no http-01 challenge for " + auth.getIdentifier() ) );

			challengeTokens.put( challenge.getToken(), challenge.getAuthorization() );
			try {
				challenge.trigger();
				final Status status = challenge.waitForCompletion( CHALLENGE_TIMEOUT );
				if( status != Status.VALID ) {
					throw new AcmeException( "HTTP-01 challenge for %s ended as %s%s".formatted(
							auth.getIdentifier().getDomain(), status,
							challenge.getError().map( problem -> " — " + problem ).orElse( "" ) ) );
				}
			}
			finally {
				challengeTokens.remove( challenge.getToken() );
			}
		}

		final KeyPair domainKeys = KeyPairUtils.createECKeyPair( "secp256r1" );
		order.execute( domainKeys );

		final Status orderStatus = order.waitForCompletion( ORDER_TIMEOUT );
		if( orderStatus != Status.VALID ) {
			throw new AcmeException( "Order for %s ended as %s%s".formatted(
					site.primaryHostname(), orderStatus,
					order.getError().map( problem -> " — " + problem ).orElse( "" ) ) );
		}

		final var certificate = order.getCertificate();
		final StringWriter chain = new StringWriter();
		certificate.writeCertificate( chain );

		// Key first, then cert — CertStore's reload only fires listeners after both parse together
		PemFiles.writeAtomic( site.keyPath(), PemFiles.privateKeyPem( domainKeys.getPrivate() ) );
		PemFiles.writeAtomic( site.certPath(), chain.toString() );

		logger.info( "Obtained certificate for {} — valid until {}", site.primaryHostname(), certificate.getCertificate().getNotAfter() );
	}
}
