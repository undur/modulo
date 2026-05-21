package modulo.frontend.apache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import modulo.frontend.site.Site;

class ApacheConfigReaderTest {

	/** Helper: list every file in {@code dir} and parse them as a single batch. */
	private static List<Site> readAll( final Path dir ) throws IOException {
		final List<Path> files = new ArrayList<>();
		try( Stream<Path> s = Files.list( dir ) ) {
			s.filter( Files::isRegularFile ).sorted().forEach( files::add );
		}
		return new ApacheConfigReader( files ).read();
	}


	@Test
	void parsesTheLidamotExample( @TempDir Path tmp ) throws IOException {
		final String vhost = """
				<VirtualHost *:443>
				     ServerName www.lidamot.is
				     ServerAlias lidamot.is

				     DocumentRoot /rebbi/is.lidamot/html

				     TransferLog "|/usr/bin/rotatelogs /rebbi/is.lidamot/log/httpd/access_log 86400"

				     ProxyPass        / http://hz1.rebbi.is:1400/
				     ProxyPassReverse / http://hz1.rebbi.is:1400/

				     SSLCertificateFile /etc/letsencrypt/live/lidamot.is/fullchain.pem
				     SSLCertificateKeyFile /etc/letsencrypt/live/lidamot.is/privkey.pem
				</VirtualHost>
				""";
		Files.writeString( tmp.resolve( "lidamot.conf" ), vhost );

		final List<Site> sites = readAll( tmp );
		assertEquals( 1, sites.size() );

		final Site s = sites.getFirst();
		assertEquals( "www.lidamot.is", s.primaryHostname() );
		assertEquals( List.of( "lidamot.is" ), s.aliases() );
		assertEquals( Path.of( "/etc/letsencrypt/live/lidamot.is/fullchain.pem" ), s.certPath() );
		assertEquals( Path.of( "/etc/letsencrypt/live/lidamot.is/privkey.pem" ), s.keyPath() );
		assertTrue( s.canonicalRedirect() );
		assertTrue( s.httpsRedirect() );
	}

	@Test
	void skipsVhostsMissingRequiredDirectives( @TempDir Path tmp ) throws IOException {
		final String vhost = """
				<VirtualHost *:443>
				    ServerName partial.example
				</VirtualHost>
				""";
		Files.writeString( tmp.resolve( "partial.conf" ), vhost );
		assertEquals( 0, readAll( tmp ).size() );
	}

	@Test
	void ignoresPort80Vhosts( @TempDir Path tmp ) throws IOException {
		final String vhost = """
				<VirtualHost *:80>
				    ServerName plain.example
				    SSLCertificateFile /a
				    SSLCertificateKeyFile /b
				</VirtualHost>
				""";
		Files.writeString( tmp.resolve( "plain.conf" ), vhost );
		assertEquals( 0, readAll( tmp ).size() );
	}

	@Test
	void handlesMultipleAliasesOnOneLine( @TempDir Path tmp ) throws IOException {
		final String vhost = """
				<VirtualHost *:443>
				    ServerName a.example
				    ServerAlias b.example c.example
				    SSLCertificateFile /c
				    SSLCertificateKeyFile /k
				</VirtualHost>
				""";
		Files.writeString( tmp.resolve( "multi.conf" ), vhost );
		final Site s = readAll( tmp ).getFirst();
		assertEquals( List.of( "b.example", "c.example" ), s.aliases() );
	}

	@Test
	void manifestListsAbsoluteAndRelativePaths( @TempDir Path tmp ) throws IOException {
		final Path vhostsDir = tmp.resolve( "vhosts" );
		Files.createDirectories( vhostsDir );
		Files.writeString( vhostsDir.resolve( "a.conf" ), """
				<VirtualHost *:443>
				    ServerName a.example
				    SSLCertificateFile /c
				    SSLCertificateKeyFile /k
				</VirtualHost>
				""" );
		final Path bAbsolute = tmp.resolve( "b.conf" );
		Files.writeString( bAbsolute, """
				<VirtualHost *:443>
				    ServerName b.example
				    SSLCertificateFile /c
				    SSLCertificateKeyFile /k
				</VirtualHost>
				""" );

		final Path manifest = tmp.resolve( "manifest.txt" );
		Files.writeString( manifest, """
				# manifest with mixed forms
				vhosts/a.conf
				""" + bAbsolute.toAbsolutePath() + "\n   \n# trailing comment\n" );

		final List<Site> sites = ApacheConfigReader.fromManifest( manifest ).read();
		assertEquals( 2, sites.size() );
		assertEquals( "a.example", sites.get( 0 ).primaryHostname() );
		assertEquals( "b.example", sites.get( 1 ).primaryHostname() );
	}

	@Test
	void skipsCommentedDirectives( @TempDir Path tmp ) throws IOException {
		final String vhost = """
				<VirtualHost *:443>
				    ServerName real.example
				    # ServerAlias commented.example
				    SSLCertificateFile /c
				    SSLCertificateKeyFile /k
				</VirtualHost>
				""";
		Files.writeString( tmp.resolve( "commented.conf" ), vhost );
		final Site s = readAll( tmp ).getFirst();
		assertEquals( List.of(), s.aliases() );
	}
}
