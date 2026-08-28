package modulo.frontend.tls.acme;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Deployment-wide ACME settings: the account contact, which CA directory to
 * order from, and where modulo keeps its ACME state on disk.
 *
 * The storage layout is owned by modulo:
 *
 * <pre>
 * &lt;storageDir&gt;/account-key.pem          ACME account keypair
 * &lt;storageDir&gt;/sites/&lt;hostname&gt;/cert.pem  full chain, PEM
 * &lt;storageDir&gt;/sites/&lt;hostname&gt;/key.pem   private key, PKCS#8 PEM
 * </pre>
 *
 * The per-site PEM paths double as the Site's certPath/keyPath, so the
 * existing {@link modulo.frontend.tls.CertStore} file-watching and hot-reload
 * machinery works unchanged for ACME-managed sites.
 */
public record AcmeSettings(
		String email,
		URI directoryUri,
		Path storageDir ) {

	public AcmeSettings {
		Objects.requireNonNull( email, "email" );
		Objects.requireNonNull( directoryUri, "directoryUri" );
		Objects.requireNonNull( storageDir, "storageDir" );
	}

	/**
	 * Resolves the config file's "directory" value to an acme4j server URI.
	 * The two Let's Encrypt endpoints get friendly names; anything else is
	 * taken as a literal URI (an {@code acme://} provider URI or an
	 * {@code https://} directory URL — the latter is what a local Pebble
	 * test server uses).
	 */
	public static URI resolveDirectory( final String directory ) {
		return switch( directory ) {
			case "letsencrypt" -> URI.create( "acme://letsencrypt.org" );
			case "letsencrypt-staging" -> URI.create( "acme://letsencrypt.org/staging" );
			default -> URI.create( directory );
		};
	}

	public Path accountKeyPath() {
		return storageDir.resolve( "account-key.pem" );
	}

	public Path certPathFor( final String primaryHostname ) {
		return siteDir( primaryHostname ).resolve( "cert.pem" );
	}

	public Path keyPathFor( final String primaryHostname ) {
		return siteDir( primaryHostname ).resolve( "key.pem" );
	}

	private Path siteDir( final String primaryHostname ) {
		return storageDir.resolve( "sites" ).resolve( primaryHostname );
	}
}
