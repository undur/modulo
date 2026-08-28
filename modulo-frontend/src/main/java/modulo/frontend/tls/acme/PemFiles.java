package modulo.frontend.tls.acme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

/**
 * Writes the PEM files the ACME machinery produces, in exactly the shapes
 * {@link modulo.frontend.tls.CertStore} reads back: X.509 certificate chains,
 * and private keys as PKCS#8 (which {@link PrivateKey#getEncoded()} already
 * is for JCA keys — no Bouncy Castle involvement needed).
 */
class PemFiles {

	private static final Base64.Encoder BASE64_MIME = Base64.getMimeEncoder( 64, "\n".getBytes( StandardCharsets.US_ASCII ) );

	static String privateKeyPem( final PrivateKey key ) {
		return pemBlock( "PRIVATE KEY", key.getEncoded() );
	}

	static String certificateChainPem( final List<X509Certificate> chain ) {
		final StringBuilder out = new StringBuilder();
		for( final X509Certificate cert : chain ) {
			try {
				out.append( pemBlock( "CERTIFICATE", cert.getEncoded() ) );
			}
			catch( final CertificateEncodingException e ) {
				throw new IllegalStateException( "Failed to encode certificate " + cert.getSubjectX500Principal(), e );
			}
		}
		return out.toString();
	}

	private static String pemBlock( final String type, final byte[] der ) {
		return "-----BEGIN %s-----\n%s\n-----END %s-----\n".formatted( type, BASE64_MIME.encodeToString( der ), type );
	}

	/**
	 * Writes content via a temp file + atomic move, so a reader (CertStore's
	 * poller) never sees a half-written PEM.
	 */
	static void writeAtomic( final Path target, final String content ) throws IOException {
		Files.createDirectories( target.getParent() );
		final Path temp = target.resolveSibling( target.getFileName() + ".tmp" );
		Files.writeString( temp, content, StandardCharsets.UTF_8 );
		Files.move( temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
	}
}
