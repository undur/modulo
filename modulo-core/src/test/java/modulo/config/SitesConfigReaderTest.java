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
}
