package modulo.frontend.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.frontend.site.Site;

/**
 * Builds and maintains an in-memory {@link KeyStore} containing one entry per
 * {@link Site}, keyed by primary hostname. Jetty's SNI selector picks the
 * right entry per incoming TLS handshake.
 *
 * Supports hot reload: a background timer polls cert/key file mtimes and
 * rebuilds the keystore when any change. Listeners (typically Jetty's
 * SslContextFactory.reload) are notified after a successful rebuild.
 *
 * PEM parsing is intentionally minimal: handles a concatenated chain in the
 * cert file (multiple {@code -----BEGIN CERTIFICATE-----} blocks) and a
 * PKCS#8 private key (the format certbot writes by default).
 */
public class CertStore {

	private static final Logger logger = LoggerFactory.getLogger( CertStore.class );

	/** Password used for the in-memory keystore. The store never touches disk so the value is arbitrary. */
	private static final char[] KEYSTORE_PASSWORD = "modulo".toCharArray();

	private final List<Site> sites;
	private final Duration pollInterval;
	private final AtomicReference<KeyStore> currentStore = new AtomicReference<>();
	private final Map<Path, Long> lastMtimes = new HashMap<>();
	private final List<Consumer<KeyStore>> reloadListeners = new ArrayList<>();
	private Timer pollTimer;

	public CertStore( final List<Site> sites ) {
		this( sites, Duration.ofMinutes( 5 ) );
	}

	public CertStore( final List<Site> sites, final Duration pollInterval ) {
		this.sites = List.copyOf( sites );
		this.pollInterval = pollInterval;
	}

	/**
	 * Build the initial keystore. Throws if no sites can be loaded — a frontend
	 * with zero certs is useless and we'd rather fail loudly at startup.
	 */
	public KeyStore load() {
		final KeyStore store = buildStore();
		currentStore.set( store );
		recordMtimes();
		return store;
	}

	/** Password used to wrap entries in the in-memory keystore (Jetty needs this for the SslContextFactory). */
	public char[] keyStorePassword() {
		return KEYSTORE_PASSWORD.clone();
	}

	public KeyStore current() {
		return currentStore.get();
	}

	/**
	 * Register a callback invoked after a successful reload. Jetty users will
	 * pass a lambda that calls {@code SslContextFactory.reload(...)}.
	 */
	public void onReload( final Consumer<KeyStore> listener ) {
		reloadListeners.add( listener );
	}

	/** Start background mtime polling. Idempotent. */
	public synchronized void startWatching() {
		if( pollTimer != null ) {
			return;
		}
		pollTimer = new Timer( "modulo-certstore-watcher", true );
		pollTimer.schedule( new TimerTask() {
			@Override
			public void run() {
				try {
					checkAndReload();
				}
				catch( final RuntimeException e ) {
					logger.warn( "Cert reload check failed: {}", e.toString() );
				}
			}
		}, pollInterval.toMillis(), pollInterval.toMillis() );
	}

	public synchronized void stopWatching() {
		if( pollTimer != null ) {
			pollTimer.cancel();
			pollTimer = null;
		}
	}

	private void checkAndReload() {
		boolean changed = false;
		for( final Site site : sites ) {
			if( mtimeChanged( site.certPath() ) || mtimeChanged( site.keyPath() ) ) {
				changed = true;
				break;
			}
		}
		if( !changed ) {
			return;
		}

		logger.info( "Detected cert/key change on disk; rebuilding keystore" );
		final KeyStore rebuilt;
		try {
			rebuilt = buildStore();
		}
		catch( final RuntimeException e ) {
			logger.warn( "Cert reload failed, keeping previous keystore: {}", e.toString() );
			return;
		}
		currentStore.set( rebuilt );
		recordMtimes();
		for( final Consumer<KeyStore> listener : reloadListeners ) {
			try {
				listener.accept( rebuilt );
			}
			catch( final RuntimeException e ) {
				logger.warn( "Reload listener threw: {}", e.toString() );
			}
		}
	}

	private boolean mtimeChanged( final Path p ) {
		try {
			final long current = Files.getLastModifiedTime( p ).toMillis();
			final Long previous = lastMtimes.get( p );
			return previous == null || previous != current;
		}
		catch( final IOException e ) {
			return false;
		}
	}

	private void recordMtimes() {
		for( final Site site : sites ) {
			recordOne( site.certPath() );
			recordOne( site.keyPath() );
		}
	}

	private void recordOne( final Path p ) {
		try {
			lastMtimes.put( p, Files.getLastModifiedTime( p ).toMillis() );
		}
		catch( final IOException e ) {
			// fine — next poll will likely warn separately
		}
	}

	private KeyStore buildStore() {
		final KeyStore store;
		try {
			store = KeyStore.getInstance( "PKCS12" );
			store.load( null, null );
		}
		catch( final Exception e ) {
			throw new RuntimeException( "Failed to initialise in-memory keystore", e );
		}

		int loaded = 0;
		for( final Site site : sites ) {
			try {
				final Certificate[] chain = readCertChain( site.certPath() );
				final PrivateKey key = readPrivateKey( site.keyPath() );
				store.setKeyEntry( site.primaryHostname(), key, KEYSTORE_PASSWORD, chain );
				loaded++;
			}
			catch( final Exception e ) {
				logger.warn( "Failed to load TLS material for site {}: {}", site.primaryHostname(), e.toString() );
			}
		}

		if( loaded == 0 ) {
			throw new IllegalStateException( "No TLS certificates could be loaded — refusing to start front-end with empty keystore" );
		}

		logger.info( "Loaded {} certificate(s) into in-memory keystore", loaded );
		return store;
	}

	private static Certificate[] readCertChain( final Path certFile ) throws IOException {
		final String pem = Files.readString( certFile, StandardCharsets.UTF_8 );
		final CertificateFactory cf;
		try {
			cf = CertificateFactory.getInstance( "X.509" );
		}
		catch( final Exception e ) {
			throw new RuntimeException( e );
		}

		final Collection<? extends Certificate> certs;
		try( ByteArrayInputStream in = new ByteArrayInputStream( pem.getBytes( StandardCharsets.UTF_8 ) ) ) {
			certs = cf.generateCertificates( in );
		}
		catch( final Exception e ) {
			throw new RuntimeException( "Failed to parse certificate chain from " + certFile, e );
		}

		final List<X509Certificate> ordered = new ArrayList<>();
		for( final Certificate c : certs ) {
			ordered.add( (X509Certificate)c );
		}
		return ordered.toArray( new Certificate[0] );
	}

	private static PrivateKey readPrivateKey( final Path keyFile ) throws IOException {
		final String pem = Files.readString( keyFile, StandardCharsets.UTF_8 );
		final String base64 = extractPemBody( pem );
		final byte[] der = Base64.getMimeDecoder().decode( base64 );
		try {
			final KeyFactory kf = KeyFactory.getInstance( "RSA" );
			return kf.generatePrivate( new PKCS8EncodedKeySpec( der ) );
		}
		catch( final Exception ignored ) {
			// fall through and try EC — Let's Encrypt issues ECDSA certs too
		}
		try {
			final KeyFactory kf = KeyFactory.getInstance( "EC" );
			return kf.generatePrivate( new PKCS8EncodedKeySpec( der ) );
		}
		catch( final Exception e ) {
			throw new RuntimeException( "Failed to parse private key from " + keyFile + " as PKCS#8 RSA or EC", e );
		}
	}

	private static String extractPemBody( final String pem ) {
		final StringBuilder body = new StringBuilder();
		boolean inside = false;
		for( final String line : pem.split( "\\R" ) ) {
			if( line.startsWith( "-----BEGIN" ) ) {
				inside = true;
				continue;
			}
			if( line.startsWith( "-----END" ) ) {
				inside = false;
				continue;
			}
			if( inside ) {
				body.append( line );
			}
		}
		return body.toString();
	}
}
