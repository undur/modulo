package modulo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SitesConfigIncludeTest {

	private static final String SITE_TEMPLATE = """
			[[sites]]
			hostnames = [ "%s" ]
			app = "%s"
			tls = { mode = "manual", cert = "/c", key = "/k" }
			""";

	private static Path writeMain( final Path dir, final String content ) throws IOException {
		final Path main = dir.resolve( "sites.toml" );
		Files.writeString( main, content );
		return main;
	}

	@Test
	void includesGlobMatchedFilesInSortedOrder( @TempDir Path tmp ) throws IOException {
		// Mimics the /rebbi/<domain>/conf/site.toml layout
		Files.createDirectories( tmp.resolve( "apps/beta/conf" ) );
		Files.createDirectories( tmp.resolve( "apps/alpha/conf" ) );
		Files.createDirectories( tmp.resolve( "apps/noconf" ) );
		Files.writeString( tmp.resolve( "apps/beta/conf/site.toml" ), SITE_TEMPLATE.formatted( "beta.example", "Beta" ) );
		Files.writeString( tmp.resolve( "apps/alpha/conf/site.toml" ), SITE_TEMPLATE.formatted( "alpha.example", "Alpha" ) );

		final Path main = writeMain( tmp, """
				include = [ "apps/*/conf/site.toml" ]

				[[sites]]
				hostnames = [ "main.example" ]
				app = "Main"
				tls = { mode = "manual", cert = "/c", key = "/k" }
				""" );

		final SitesConfig config = SitesConfigReader.read( main );
		final List<String> primaries = config.frontendSites().stream().map( s -> s.primaryHostname() ).toList();
		// main file's own sites first, then includes in sorted path order
		assertEquals( List.of( "main.example", "alpha.example", "beta.example" ), primaries );
		assertEquals( "Alpha", config.domainToAppMap().get( "alpha.example" ) );
	}

	@Test
	void mainFileMayHoldOnlyAcmeAndIncludes( @TempDir Path tmp ) throws IOException {
		Files.writeString( tmp.resolve( "one.toml" ), SITE_TEMPLATE.formatted( "a.example", "A" ) );
		final Path main = writeMain( tmp, """
				include = [ "one.toml" ]
				""" );
		assertEquals( 1, SitesConfigReader.read( main ).sites().size() );
	}

	@Test
	void includedSitesUseTheMainFilesAcmeBlock( @TempDir Path tmp ) throws IOException {
		Files.writeString( tmp.resolve( "acme-site.toml" ), """
				[[sites]]
				hostnames = [ "a.example" ]
				app = "A"
				""" );
		final Path main = writeMain( tmp, """
				include = [ "acme-site.toml" ]

				[acme]
				email = "op@example.com"
				storage = "/var/lib/modulo/acme"
				""" );
		final SitesConfig config = SitesConfigReader.read( main );
		assertTrue( config.sites().getFirst().acmeManaged() );
		assertEquals( Path.of( "/var/lib/modulo/acme/sites/a.example/cert.pem" ), config.frontendSites().getFirst().certPath() );
	}

	@Test
	void rejectsAcmeBlockInIncludedFile( @TempDir Path tmp ) throws IOException {
		Files.writeString( tmp.resolve( "bad.toml" ), """
				sites = []

				[acme]
				email = "x@example.com"
				storage = "/s"
				""" );
		final Path main = writeMain( tmp, """
				include = [ "bad.toml" ]
				""" );
		final SitesConfigException e = assertThrows( SitesConfigException.class, () -> SitesConfigReader.read( main ) );
		assertTrue( e.getMessage().contains( "bad.toml" ) );
	}

	@Test
	void rejectsDuplicateHostnameAcrossFilesNamingBoth( @TempDir Path tmp ) throws IOException {
		Files.writeString( tmp.resolve( "one.toml" ), SITE_TEMPLATE.formatted( "dup.example", "A" ) );
		Files.writeString( tmp.resolve( "two.toml" ), SITE_TEMPLATE.formatted( "dup.example", "B" ) );
		final Path main = writeMain( tmp, """
				include = [ "one.toml", "two.toml" ]
				""" );
		final SitesConfigException e = assertThrows( SitesConfigException.class, () -> SitesConfigReader.read( main ) );
		assertTrue( e.getMessage().contains( "one.toml" ) );
		assertTrue( e.getMessage().contains( "two.toml" ) );
	}

	@Test
	void missingExplicitIncludeFails_emptyGlobDoesNot( @TempDir Path tmp ) throws IOException {
		final Path explicit = writeMain( tmp, """
				include = [ "nope.toml" ]
				""" );
		assertThrows( SitesConfigException.class, () -> SitesConfigReader.read( explicit ) );

		final Path glob = writeMain( tmp, """
				include = [ "nothing/*/site.toml" ]

				[[sites]]
				hostnames = [ "a.example" ]
				app = "A"
				tls = { mode = "manual", cert = "/c", key = "/k" }
				""" );
		assertEquals( 1, SitesConfigReader.read( glob ).sites().size() );
	}

	@Test
	void rejectsRecursiveGlobs( @TempDir Path tmp ) throws IOException {
		final Path main = writeMain( tmp, """
				include = [ "apps/**/site.toml" ]
				""" );
		assertThrows( SitesConfigException.class, () -> SitesConfigReader.read( main ) );
	}
}
