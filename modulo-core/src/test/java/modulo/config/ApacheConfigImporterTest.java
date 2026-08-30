package modulo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import modulo.frontend.site.Site;

class ApacheConfigImporterTest {

	/**
	 * The importer's output must round-trip: Apache vhosts → JSON →
	 * SitesConfigReader → the same Sites and routing.
	 */
	@Test
	void roundTripsFromApacheVhosts( @TempDir Path tmp ) throws IOException {
		final String vhosts = """
				<VirtualHost *:443>
				    ServerName www.rebbi.is
				    ServerAlias rebbi.is
				    SSLCertificateFile /etc/letsencrypt/live/rebbi.is/fullchain.pem
				    SSLCertificateKeyFile /etc/letsencrypt/live/rebbi.is/privkey.pem
				</VirtualHost>
				<VirtualHost *:443>
				    ServerName www.unmapped.example
				    SSLCertificateFile /c
				    SSLCertificateKeyFile /k
				</VirtualHost>
				""";
		Files.writeString( tmp.resolve( "vhosts.conf" ), vhosts );
		final Path manifest = tmp.resolve( "manifest.txt" );
		Files.writeString( manifest, "vhosts.conf\n" );

		final List<Site> apacheSites = ApacheConfigReader.fromManifest( manifest ).read();
		final Map<String, String> domainToApp = Map.of( "www.rebbi.is", "Rebbi" );
		final String toml = ApacheConfigImporter.toToml( apacheSites, domainToApp::get );

		final SitesConfig roundTripped = SitesConfigReader.parse( toml, "imported" );

		assertEquals( apacheSites, roundTripped.frontendSites() );
		assertEquals( Map.of( "www.rebbi.is", "Rebbi", "rebbi.is", "Rebbi" ), roundTripped.domainToAppMap() );
		assertEquals( "Rebbi", roundTripped.sites().getFirst().app() );
		assertNull( roundTripped.sites().get( 1 ).app() );
	}

	/**
	 * A vhost file listed twice in the manifest (seen in the wild) must not
	 * produce output the strict reader rejects for duplicate hostnames.
	 */
	@Test
	void dropsDuplicateSites( @TempDir Path tmp ) throws IOException {
		final String vhost = """
				<VirtualHost *:443>
				    ServerName dup.example
				    SSLCertificateFile /c
				    SSLCertificateKeyFile /k
				</VirtualHost>
				""";
		Files.writeString( tmp.resolve( "dup.conf" ), vhost );
		final Path manifest = tmp.resolve( "manifest.txt" );
		Files.writeString( manifest, "dup.conf\ndup.conf\n" );

		final List<Site> apacheSites = ApacheConfigReader.fromManifest( manifest ).read();
		assertEquals( 2, apacheSites.size() );

		final String toml = ApacheConfigImporter.toToml( apacheSites, host -> null );
		assertEquals( 1, SitesConfigReader.parse( toml, "imported" ).sites().size() );
	}
}
