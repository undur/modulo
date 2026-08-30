package modulo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import modulo.config.SitesConfig.ConfiguredSite;

class SitesConfigReaderTest {

	private static SitesConfig parse( final String toml ) {
		return SitesConfigReader.parse( toml, "test" );
	}

	@Test
	void parsesFullExample( @TempDir Path tmp ) throws IOException {
		final String toml = """
				# operator comments are native
				[[sites]]
				hostnames = [ "www.rebbi.is", "rebbi.is" ]
				app = "Rebbi"
				tls = { mode = "manual", cert = "/certs/rebbi/fullchain.pem", key = "/certs/rebbi/privkey.pem" }

				[[sites]]
				hostnames = [ "static.example" ]
				tls = { mode = "manual", cert = "/c", key = "/k" }
				canonicalRedirect = false
				httpsRedirect = false
				""";
		final Path file = tmp.resolve( "sites.toml" );
		Files.writeString( file, toml );

		final SitesConfig config = SitesConfigReader.read( file );
		assertEquals( 2, config.sites().size() );

		final ConfiguredSite rebbi = config.sites().getFirst();
		assertEquals( "www.rebbi.is", rebbi.site().primaryHostname() );
		assertEquals( List.of( "rebbi.is" ), rebbi.site().aliases() );
		assertEquals( "Rebbi", rebbi.app() );
		assertEquals( Path.of( "/certs/rebbi/fullchain.pem" ), rebbi.site().certPath() );
		assertEquals( Path.of( "/certs/rebbi/privkey.pem" ), rebbi.site().keyPath() );
		assertTrue( rebbi.site().canonicalRedirect() );
		assertTrue( rebbi.site().httpsRedirect() );

		final ConfiguredSite appless = config.sites().get( 1 );
		assertNull( appless.app() );
		assertFalse( appless.site().canonicalRedirect() );
		assertFalse( appless.site().httpsRedirect() );

		final Map<String, String> map = config.domainToAppMap();
		assertEquals( Map.of( "www.rebbi.is", "Rebbi", "rebbi.is", "Rebbi" ), map );
	}

	@Test
	void normalizesHostnamesToLowercase() {
		final SitesConfig config = parse( """
				sites = [ { hostnames = [ "WWW.Example.COM" ], tls = { mode = "manual", cert = "/c", key = "/k" } } ]
				""" );
		assertEquals( "www.example.com", config.sites().getFirst().site().primaryHostname() );
	}

	@Test
	void acmeIsTheDefaultWhenTlsIsOmitted() {
		final SitesConfig config = parse( """
				[acme]
				email = "op@example.com"
				storage = "/var/lib/modulo/acme"
				directory = "letsencrypt-staging"

				[[sites]]
				hostnames = [ "www.rebbi.is", "rebbi.is" ]
				app = "Rebbi"

				[[sites]]
				hostnames = [ "legacy.example" ]
				tls = { mode = "manual", cert = "/c", key = "/k" }
				""" );

		assertEquals( "op@example.com", config.acme().email() );
		assertEquals( "acme://letsencrypt.org/staging", config.acme().directoryUri().toString() );

		final ConfiguredSite acmeSite = config.sites().getFirst();
		assertTrue( acmeSite.acmeManaged() );
		assertEquals( Path.of( "/var/lib/modulo/acme/sites/www.rebbi.is/cert.pem" ), acmeSite.site().certPath() );
		assertEquals( Path.of( "/var/lib/modulo/acme/sites/www.rebbi.is/key.pem" ), acmeSite.site().keyPath() );

		assertFalse( config.sites().get( 1 ).acmeManaged() );
		assertEquals( List.of( acmeSite.site() ), config.acmeManagedSites() );
	}

	@Test
	void acmeDirectoryDefaultsToLetsEncryptProduction() {
		final SitesConfig config = parse( """
				sites = [ { hostnames = [ "a.example" ] } ]

				[acme]
				email = "op@example.com"
				storage = "/s"
				""" );
		assertEquals( "acme://letsencrypt.org", config.acme().directoryUri().toString() );
	}

	@Test
	void rejectsAcmeSiteWithoutAcmeBlock() {
		final SitesConfigException e = assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { hostnames = [ "a.example" ], app = "A" } ]
				""" ) );
		assertTrue( e.getMessage().contains( "\"acme\" block" ) );
	}

	@Test
	void rejectsAcmeModeWithExplicitCertPaths() {
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { hostnames = [ "a.example" ], tls = { mode = "acme", cert = "/c" } } ]

				[acme]
				email = "op@example.com"
				storage = "/s"
				""" ) );
	}

	@Test
	void rejectsAcmeBlockMissingEmailOrStorage() {
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = []

				[acme]
				storage = "/s"
				""" ) );
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = []

				[acme]
				email = "op@example.com"
				""" ) );
	}

	@Test
	void rejectsManualModeWithoutKey() {
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { hostnames = [ "a.example" ], tls = { mode = "manual", cert = "/c" } } ]
				""" ) );
	}

	@Test
	void rejectsUnknownTlsMode() {
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { hostnames = [ "a.example" ], tls = { mode = "telepathy", cert = "/c", key = "/k" } } ]
				""" ) );
	}

	@Test
	void rejectsUnknownFields() {
		// "hostname" (typo for "hostnames") must not be silently ignored
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { hostname = [ "a.example" ], tls = { mode = "manual", cert = "/c", key = "/k" } } ]
				""" ) );
	}

	@Test
	void rejectsDuplicateHostnameAcrossSites() {
		final SitesConfigException e = assertThrows( SitesConfigException.class, () -> parse( """
				sites = [
				  { hostnames = [ "a.example", "shared.example" ], tls = { mode = "manual", cert = "/c", key = "/k" } },
				  { hostnames = [ "b.example", "SHARED.example" ], tls = { mode = "manual", cert = "/c", key = "/k" } },
				]
				""" ) );
		assertTrue( e.getMessage().contains( "shared.example" ) );
	}

	@Test
	void rejectsSiteWithoutHostnames() {
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { app = "A", tls = { mode = "manual", cert = "/c", key = "/k" } } ]
				""" ) );
	}

	@Test
	void rejectsEmptyConfig() {
		assertThrows( SitesConfigException.class, () -> parse( "" ) );
	}

	@Test
	void rejectsMalformedToml() {
		assertThrows( SitesConfigException.class, () -> parse( "sites = [ oops" ) );
	}

	@Test
	void parsesRewrites() {
		final SitesConfig config = parse( """
				[[sites]]
				hostnames = [ "www.example" ]
				app = "App"
				tls = { mode = "manual", cert = "/c", key = "/k" }
				rewrites = [
				  { match = '^/$', to = '/Apps/WebObjects/App.woa/wa/default' },
				  { match = '^/x/(.*)$', to = '/Apps/WebObjects/App.woa/wa/x?id=$1', appendQuery = true, encodeCaptures = true },
				  { match = '^/gone$', to = 'https://elsewhere.example/', redirect = "permanent" },
				]
				""" );

		final List<modulo.rewrite.RewriteRule> rules = config.sites().getFirst().rewrites();
		assertEquals( 3, rules.size() );
		assertEquals( modulo.rewrite.RewriteRule.Redirect.NONE, rules.get( 0 ).redirect() );
		assertTrue( rules.get( 1 ).appendQuery() );
		assertTrue( rules.get( 1 ).encodeCaptures() );
		assertEquals( modulo.rewrite.RewriteRule.Redirect.PERMANENT, rules.get( 2 ).redirect() );

		final Map<String, List<modulo.rewrite.RewriteRule>> byHost = config.hostToRewrites();
		assertEquals( rules, byHost.get( "www.example" ) );
	}

	@Test
	void tomlLiteralStringsCarryRegexesUnescaped() {
		final SitesConfig config = parse( """
				[[sites]]
				hostnames = [ "www.example" ]
				app = "App"
				tls = { mode = "manual", cert = "/c", key = "/k" }
				rewrites = [ { match = '^/entry/(\\d+)\\.html$', to = '/Apps/WebObjects/App.woa/wa/entry?id=$1' } ]
				""" );
		assertEquals( "^/entry/(\\d+)\\.html$", config.sites().getFirst().rewrites().getFirst().pattern().pattern() );
	}

	@Test
	void siteWithoutRewritesGetsEmptyList() {
		final SitesConfig config = parse( """
				sites = [ { hostnames = [ "www.example" ], tls = { mode = "manual", cert = "/c", key = "/k" } } ]
				""" );
		assertTrue( config.sites().getFirst().rewrites().isEmpty() );
		assertTrue( config.hostToRewrites().isEmpty() );
	}

	@Test
	void rejectsInvalidRewriteRegex() {
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { hostnames = [ "www.example" ], tls = { mode = "manual", cert = "/c", key = "/k" },
				  rewrites = [ { match = '^/([bad$', to = '/x' } ] } ]
				""" ) );
	}

	@Test
	void rejectsUnknownRedirectValue() {
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { hostnames = [ "www.example" ], tls = { mode = "manual", cert = "/c", key = "/k" },
				  rewrites = [ { match = '^/$', to = '/x', redirect = "sometimes" } ] } ]
				""" ) );
	}

	@Test
	void rejectsCaptureReferenceBeyondGroupCount() {
		assertThrows( SitesConfigException.class, () -> parse( """
				sites = [ { hostnames = [ "www.example" ], tls = { mode = "manual", cert = "/c", key = "/k" },
				  rewrites = [ { match = '^/(.*)$', to = '/x?a=$1&b=$2' } ] } ]
				""" ) );
	}

	@Test
	void parsesRootConfigWithBootstrapTables( @TempDir Path tmp ) throws IOException {
		final Path file = tmp.resolve( "modulo.toml" );
		Files.writeString( file, """
				[frontend]
				httpPort = 8080
				httpsPort = 8443
				http3 = false
				accessLogDir = "/var/log/modulo"

				[admin]
				password = "hunter2"

				[wotaskd]
				host = "localhost"
				port = 1085
				password = "na"

				[acme]
				email = "op@example.com"
				storage = "%s"

				[[sites]]
				hostnames = [ "www.example.com" ]
				app = "MyApp"
				""".formatted( tmp.resolve( "acme" ) ) );

		final SitesConfigReader.ParsedConfig parsed = SitesConfigReader.readWithBootstrap( file );
		assertEquals( 1, parsed.sites().sites().size() );
		assertEquals( 8080, parsed.bootstrap().httpPort() );
		assertEquals( "hunter2", parsed.bootstrap().adminPassword() );
		assertEquals( "localhost", parsed.bootstrap().wotaskdHost() );
		assertEquals( 1085, parsed.bootstrap().wotaskdPort() );

		// bootstrap diffing: same file → nothing changed; edited port → named
		assertTrue( parsed.bootstrap().changedSettings( parsed.bootstrap() ).isEmpty() );
		final modulo.config.BootstrapConfig edited = new modulo.config.BootstrapConfig( 80, 8443, false, "/var/log/modulo", null, "hunter2", "localhost", 1085, "na" );
		assertEquals( List.of( "frontend.httpPort" ), parsed.bootstrap().changedSettings( edited ) );
	}

	@Test
	void fragmentsRejectBootstrapTables( @TempDir Path tmp ) throws IOException {
		Files.writeString( tmp.resolve( "sites.toml" ), """
				include = [ "frag.toml" ]
				""" );
		Files.writeString( tmp.resolve( "frag.toml" ), """
				[frontend]
				httpPort = 8080

				[[sites]]
				hostnames = [ "www.example.com" ]
				tls = { mode = "manual", cert = "/c", key = "/k" }
				""" );
		assertThrows( SitesConfigException.class, () -> SitesConfigReader.read( tmp.resolve( "sites.toml" ) ) );
	}
}
