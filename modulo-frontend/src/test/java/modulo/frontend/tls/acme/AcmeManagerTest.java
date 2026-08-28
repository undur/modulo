package modulo.frontend.tls.acme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import modulo.frontend.site.Site;
import modulo.frontend.tls.CertStore;

class AcmeManagerTest {

	private static X509Certificate readCert( final Path pem ) throws Exception {
		try( var in = new ByteArrayInputStream( Files.readAllBytes( pem ) ) ) {
			return (X509Certificate)CertificateFactory.getInstance( "X.509" ).generateCertificate( in );
		}
	}

	/**
	 * The placeholder machinery's whole point: written PEMs must load through
	 * the exact same code path real certs use — a CertStore.
	 */
	@Test
	void placeholderRoundTripsThroughCertStore( @TempDir Path tmp ) throws Exception {
		final Path cert = tmp.resolve( "sites/a.example/cert.pem" );
		final Path key = tmp.resolve( "sites/a.example/key.pem" );

		SelfSignedCertificates.writePlaceholder( cert, key, "a.example", List.of( "a.example", "b.example" ) );

		final Site site = new Site( "a.example", List.of( "b.example" ), cert, key );
		final CertStore store = new CertStore( List.of( site ) );
		assertNotNull( store.load().getKey( "a.example", "modulo".toCharArray() ) );
	}

	@Test
	void placeholderIsRecognizedAsNeedingIssuance( @TempDir Path tmp ) throws Exception {
		final Path cert = tmp.resolve( "cert.pem" );
		final Path key = tmp.resolve( "key.pem" );
		SelfSignedCertificates.writePlaceholder( cert, key, "a.example", List.of( "a.example" ) );

		assertTrue( AcmeManager.isSelfSigned( readCert( cert ) ) );
	}

	@Test
	void hostnameCoverageChecksSubjectAlternativeNames( @TempDir Path tmp ) throws Exception {
		final Path cert = tmp.resolve( "cert.pem" );
		final Path key = tmp.resolve( "key.pem" );
		SelfSignedCertificates.writePlaceholder( cert, key, "a.example", List.of( "a.example", "b.example" ) );
		final X509Certificate parsed = readCert( cert );

		assertTrue( AcmeManager.coversHostnames( parsed, List.of( "a.example", "b.example" ) ) );
		assertTrue( AcmeManager.coversHostnames( parsed, List.of( "A.EXAMPLE" ) ) );
		assertFalse( AcmeManager.coversHostnames( parsed, List.of( "a.example", "added-later.example" ) ) );
	}

	@Test
	void expiryWindowIsThirtyDays( @TempDir Path tmp ) throws Exception {
		final Path cert = tmp.resolve( "cert.pem" );
		final Path key = tmp.resolve( "key.pem" );
		SelfSignedCertificates.writePlaceholder( cert, key, "a.example", List.of( "a.example" ) );
		final X509Certificate parsed = readCert( cert );
		final Instant notAfter = parsed.getNotAfter().toInstant();

		assertFalse( AcmeManager.isExpiringSoon( parsed, notAfter.minus( 31, ChronoUnit.DAYS ) ) );
		assertTrue( AcmeManager.isExpiringSoon( parsed, notAfter.minus( 29, ChronoUnit.DAYS ) ) );
	}

	/**
	 * acme4j's key generation requests the "BC" JCA provider by name;
	 * AcmeManager's static initializer must have registered it. Caught in
	 * production: NoSuchProviderException on the first real issuance.
	 */
	@Test
	void bouncyCastleProviderIsRegisteredForAcme4j() {
		new AcmeManager( new AcmeSettings( "op@example.com", AcmeSettings.resolveDirectory( "letsencrypt" ), Path.of( "/tmp/unused" ) ), List.of() );
		assertNotNull( org.shredzone.acme4j.util.KeyPairUtils.createECKeyPair( "secp256r1" ) );
	}

	@Test
	void challengeContentServesOnlyKnownTokens() {
		final AcmeManager manager = new AcmeManager(
				new AcmeSettings( "op@example.com", AcmeSettings.resolveDirectory( "letsencrypt" ), Path.of( "/tmp/unused" ) ),
				List.of() );
		assertEquals( null, manager.challengeContent( "no-such-token" ) );
	}
}
