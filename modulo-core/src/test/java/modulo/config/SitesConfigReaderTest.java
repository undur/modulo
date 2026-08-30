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
import modulo.frontend.site.Site;

class SitesConfigReaderTest {

	private static SitesConfig parse( final String json ) {
		return SitesConfigReader.parse( json, "test" );
	}

	@Test
	void parsesFullExample( @TempDir Path tmp ) throws IOException {
		final String json = """
				{
				  // operator comments are allowed
				  "sites": [
				    {
				      "hostnames": [ "www.rebbi.is", "rebbi.is" ],
				      "app": "Rebbi",
				      "tls": { "mode": "manual", "cert": "/certs/rebbi/fullchain.pem", "key": "/certs/rebbi/privkey.pem" }
				    },
				    {
				      "hostnames": [ "static.example" ],
				      "tls": { "mode": "manual", "cert": "/c", "key": "/k" },
				      "canonicalRedirect": false,
				      "httpsRedirect": false,
				    },
				  ]
				}
				""";
		final Path file = tmp.resolve( "sites.json" );
		Files.writeString( file, json );

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
				{ "sites": [ { "hostnames": [ "WWW.Example.COM" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" } } ] }
				""" );
		assertEquals( "www.example.com", config.sites().getFirst().site().primaryHostname() );
	}

	@Test
	void acmeIsTheDefaultWhenTlsIsOmitted() {
		final SitesConfig config = parse( """
				{
				  "acme": { "email": "op@example.com", "storage": "/var/lib/modulo/acme", "directory": "letsencrypt-staging" },
				  "sites": [
				    { "hostnames": [ "www.rebbi.is", "rebbi.is" ], "app": "Rebbi" },
				    { "hostnames": [ "legacy.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" } }
				  ]
				}
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
				{ "acme": { "email": "op@example.com", "storage": "/s" },
				  "sites": [ { "hostnames": [ "a.example" ] } ] }
				""" );
		assertEquals( "acme://letsencrypt.org", config.acme().directoryUri().toString() );
	}

	@Test
	void rejectsAcmeSiteWithoutAcmeBlock() {
		final SitesConfigException e = assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [ { "hostnames": [ "a.example" ], "app": "A" } ] }
				""" ) );
		assertTrue( e.getMessage().contains( "\"acme\" block" ) );
	}

	@Test
	void rejectsAcmeModeWithExplicitCertPaths() {
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "acme": { "email": "op@example.com", "storage": "/s" },
				  "sites": [ { "hostnames": [ "a.example" ], "tls": { "mode": "acme", "cert": "/c" } } ] }
				""" ) );
	}

	@Test
	void rejectsAcmeBlockMissingEmailOrStorage() {
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "acme": { "storage": "/s" }, "sites": [] }
				""" ) );
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "acme": { "email": "op@example.com" }, "sites": [] }
				""" ) );
	}

	@Test
	void rejectsManualModeWithoutKey() {
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [ { "hostnames": [ "a.example" ], "tls": { "mode": "manual", "cert": "/c" } } ] }
				""" ) );
	}

	@Test
	void rejectsUnknownTlsMode() {
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [ { "hostnames": [ "a.example" ], "tls": { "mode": "telepathy", "cert": "/c", "key": "/k" } } ] }
				""" ) );
	}

	@Test
	void rejectsUnknownFields() {
		// "hostname" (typo for "hostnames") must not be silently ignored
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [ { "hostname": [ "a.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" } } ] }
				""" ) );
	}

	@Test
	void rejectsDuplicateHostnameAcrossSites() {
		final SitesConfigException e = assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [
				  { "hostnames": [ "a.example", "shared.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" } },
				  { "hostnames": [ "b.example", "SHARED.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" } }
				] }
				""" ) );
		assertTrue( e.getMessage().contains( "shared.example" ) );
	}

	@Test
	void rejectsSiteWithoutHostnames() {
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [ { "app": "A", "tls": { "mode": "manual", "cert": "/c", "key": "/k" } } ] }
				""" ) );
	}

	@Test
	void rejectsMissingSitesArray() {
		assertThrows( SitesConfigException.class, () -> parse( "{ }" ) );
	}

	@Test
	void rejectsMalformedJson() {
		assertThrows( SitesConfigException.class, () -> parse( "{ \"sites\": [ oops" ) );
	}

	@Test
	void parsesRewrites() {
		final SitesConfig config = parse( """
				{ "sites": [ {
				  "hostnames": [ "www.example" ],
				  "app": "App",
				  "tls": { "mode": "manual", "cert": "/c", "key": "/k" },
				  "rewrites": [
				    { "match": "^/$", "to": "/Apps/WebObjects/App.woa/wa/default" },
				    { "match": "^/x/(.*)$", "to": "/Apps/WebObjects/App.woa/wa/x?id=$1", "appendQuery": true, "encodeCaptures": true },
				    { "match": "^/gone$", "to": "https://elsewhere.example/", "redirect": "permanent" }
				  ]
				} ] }
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
	void siteWithoutRewritesGetsEmptyList() {
		final SitesConfig config = parse( """
				{ "sites": [ { "hostnames": [ "www.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" } } ] }
				""" );
		assertTrue( config.sites().getFirst().rewrites().isEmpty() );
		assertTrue( config.hostToRewrites().isEmpty() );
	}

	@Test
	void rejectsInvalidRewriteRegex() {
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [ { "hostnames": [ "www.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" },
				  "rewrites": [ { "match": "^/([bad$", "to": "/x" } ] } ] }
				""" ) );
	}

	@Test
	void rejectsUnknownRedirectValue() {
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [ { "hostnames": [ "www.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" },
				  "rewrites": [ { "match": "^/$", "to": "/x", "redirect": "sometimes" } ] } ] }
				""" ) );
	}

	@Test
	void parsesTomlMainFileWithIncludedTomlFragment( @TempDir Path tmp ) throws IOException {
		Files.createDirectories( tmp.resolve( "app/conf" ) );
		Files.writeString( tmp.resolve( "sites.toml" ), """
				# operator comments are native
				include = [ "app/conf/site.toml" ]

				[acme]
				email = "op@example.com"
				storage = "%s"
				""".formatted( tmp.resolve( "acme" ) ) );
		Files.writeString( tmp.resolve( "app/conf/site.toml" ), """
				[[sites]]
				hostnames = [ "www.example.com", "example.com" ]
				app = "MyApp"
				rewrites = [
				  { match = '^/entry/(\\d+)\\.html$', to = '/Apps/WebObjects/MyApp.woa/wa/entry?id=$1' },
				  { match = '^/old$', to = '/new', redirect = "permanent" },
				]

				[[sites]]
				hostnames = [ "manual.example" ]
				canonicalRedirect = false
				tls = { mode = "manual", cert = "/c.pem", key = "/k.pem" }
				""" );

		final SitesConfig config = SitesConfigReader.read( tmp.resolve( "sites.toml" ) );
		assertEquals( 2, config.sites().size() );

		final ConfiguredSite site = config.sites().getFirst();
		assertEquals( "www.example.com", site.site().primaryHostname() );
		assertEquals( "MyApp", site.app() );
		assertTrue( site.acmeManaged() );
		assertEquals( 2, site.rewrites().size() );
		// TOML literal strings carry regex backslashes through unescaped
		assertEquals( "^/entry/(\\d+)\\.html$", site.rewrites().getFirst().pattern().pattern() );
		assertEquals( modulo.rewrite.RewriteRule.Redirect.PERMANENT, site.rewrites().get( 1 ).redirect() );

		final ConfiguredSite manual = config.sites().get( 1 );
		assertFalse( manual.site().canonicalRedirect() );
		assertFalse( manual.acmeManaged() );
	}

	@Test
	void tomlIsStrictAboutUnknownFields( @TempDir Path tmp ) throws IOException {
		final Path file = tmp.resolve( "sites.toml" );
		Files.writeString( file, """
				[[sites]]
				hostnames = [ "www.example.com" ]
				aplication = "Typo"
				tls = { mode = "manual", cert = "/c", key = "/k" }
				""" );
		assertThrows( SitesConfigException.class, () -> SitesConfigReader.read( file ) );
	}

	@Test
	void mixedFormatIncludesWorkDuringMigration( @TempDir Path tmp ) throws IOException {
		Files.writeString( tmp.resolve( "sites.toml" ), """
				include = [ "a.toml", "b.json" ]
				""" );
		Files.writeString( tmp.resolve( "a.toml" ), """
				[[sites]]
				hostnames = [ "toml.example" ]
				tls = { mode = "manual", cert = "/c", key = "/k" }
				""" );
		Files.writeString( tmp.resolve( "b.json" ), """
				{ "sites": [ { "hostnames": [ "json.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" } } ] }
				""" );

		final SitesConfig config = SitesConfigReader.read( tmp.resolve( "sites.toml" ) );
		assertEquals( 2, config.sites().size() );
	}

	@Test
	void rejectsCaptureReferenceBeyondGroupCount() {
		assertThrows( SitesConfigException.class, () -> parse( """
				{ "sites": [ { "hostnames": [ "www.example" ], "tls": { "mode": "manual", "cert": "/c", "key": "/k" },
				  "rewrites": [ { "match": "^/(.*)$", "to": "/x?a=$1&b=$2" } ] } ] }
				""" ) );
	}
}
