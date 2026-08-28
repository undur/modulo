package modulo.frontend.tls.acme;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates self-signed placeholder certificates for ACME-managed sites that
 * don't have a real certificate on disk yet.
 *
 * The placeholder exists so the TLS connector can start immediately — the
 * keystore refuses to be empty, and an SNI handshake for the new site needs
 * *something* to present. Browsers will (correctly) distrust it; the real
 * certificate is ordered asynchronously right after startup and hot-swapped
 * in via CertStore reload, typically within seconds.
 *
 * A placeholder is recognizable as such by being self-signed (issuer ==
 * subject), which is exactly what {@link AcmeManager}'s needs-issuance check
 * looks for. Bouncy Castle is used for the certificate building — it's
 * already on the classpath as an acme4j dependency.
 */
class SelfSignedCertificates {

	/**
	 * Normally the placeholder is replaced by a real cert within seconds of
	 * startup; the 30 days give a stuck deployment (say, CA unreachable and
	 * nobody watching the logs) an unmissable expiry rather than a subtle one.
	 */
	private static final Duration VALIDITY = Duration.ofDays( 30 );

	/**
	 * Generates a fresh keypair + self-signed certificate covering
	 * [hostnames] and writes both PEMs to the given paths.
	 */
	static void writePlaceholder( final Path certPath, final Path keyPath, final String primaryHostname, final List<String> hostnames ) throws IOException {
		try {
			final KeyPairGenerator generator = KeyPairGenerator.getInstance( "EC" );
			generator.initialize( new ECGenParameterSpec( "secp256r1" ) );
			final KeyPair keyPair = generator.generateKeyPair();

			final X500Name subject = new X500Name( "CN=" + primaryHostname );
			final Instant now = Instant.now();

			final JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
					subject,
					new BigInteger( 64, new SecureRandom() ),
					Date.from( now.minus( Duration.ofHours( 1 ) ) ),
					Date.from( now.plus( VALIDITY ) ),
					subject,
					keyPair.getPublic() );

			final GeneralName[] sans = hostnames.stream()
					.map( host -> new GeneralName( GeneralName.dNSName, host ) )
					.toArray( GeneralName[]::new );
			builder.addExtension( Extension.subjectAlternativeName, false, new GeneralNames( sans ) );

			final ContentSigner signer = new JcaContentSignerBuilder( "SHA256withECDSA" ).build( keyPair.getPrivate() );
			final X509Certificate certificate = new JcaX509CertificateConverter().getCertificate( builder.build( signer ) );

			PemFiles.writeAtomic( keyPath, PemFiles.privateKeyPem( keyPair.getPrivate() ) );
			PemFiles.writeAtomic( certPath, PemFiles.certificateChainPem( List.of( certificate ) ) );
		}
		catch( final IOException e ) {
			throw e;
		}
		catch( final Exception e ) {
			throw new IllegalStateException( "Failed to generate placeholder certificate for " + primaryHostname, e );
		}
	}
}
