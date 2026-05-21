package modulo.frontend.apache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.frontend.site.Site;

/**
 * Reads Apache vhost files and produces {@link Site} objects.
 *
 * Iteration 1 scope: only {@code <VirtualHost *:443>} blocks, and only the four
 * directives that matter for SNI + TLS setup: ServerName, ServerAlias,
 * SSLCertificateFile, SSLCertificateKeyFile. Everything else is ignored.
 *
 * This is deliberately not a real Apache config parser. Apache's grammar
 * (nested IfModule, Include, env vars, line continuations) is intentionally
 * out of scope — vhosts that the dumb reader can't fully understand are
 * logged and skipped, leaving Apache to keep handling them during the
 * transition.
 */
public class ApacheConfigReader {

	private static final Logger logger = LoggerFactory.getLogger( ApacheConfigReader.class );

	/** {@code <VirtualHost ...:443>} ... {@code </VirtualHost>}, case-insensitive, dotall so {@code .} matches newlines. */
	private static final Pattern VHOST_443 = Pattern.compile(
			"<VirtualHost\\s+[^>]*:443\\s*>(.*?)</VirtualHost>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL );

	private static final Pattern SERVER_NAME = directivePattern( "ServerName" );
	private static final Pattern SERVER_ALIAS = directivePattern( "ServerAlias" );
	private static final Pattern SSL_CERT = directivePattern( "SSLCertificateFile" );
	private static final Pattern SSL_KEY = directivePattern( "SSLCertificateKeyFile" );

	private static Pattern directivePattern( final String name ) {
		// match the directive name at start of line (after optional whitespace), then capture the rest of the line
		return Pattern.compile( "(?im)^[ \\t]*" + Pattern.quote( name ) + "[ \\t]+(.+?)[ \\t]*$" );
	}

	private final List<Path> configFiles;

	/**
	 * @param configFiles Explicit list of Apache vhost files to read.
	 */
	public ApacheConfigReader( final List<Path> configFiles ) {
		this.configFiles = List.copyOf( configFiles );
	}

	/**
	 * Reads paths to Apache vhost files from a manifest file — one path per
	 * line, blank lines and {@code #}-prefixed comments ignored. Relative
	 * paths are resolved against the manifest's parent directory.
	 */
	public static ApacheConfigReader fromManifest( final Path manifest ) throws IOException {
		final List<Path> files = new ArrayList<>();
		final Path base = manifest.toAbsolutePath().getParent();
		for( final String raw : Files.readAllLines( manifest, StandardCharsets.UTF_8 ) ) {
			final String line = stripInlineComment( raw ).trim();
			if( line.isEmpty() ) {
				continue;
			}
			Path p = Path.of( line );
			if( !p.isAbsolute() && base != null ) {
				p = base.resolve( p );
			}
			files.add( p );
		}
		return new ApacheConfigReader( files );
	}

	private static String stripInlineComment( final String line ) {
		final int hash = line.indexOf( '#' );
		return hash < 0 ? line : line.substring( 0, hash );
	}

	/**
	 * @return All Sites parsed from the configured files. Files that can't
	 *         be read, or vhost blocks missing required directives, are
	 *         logged and skipped.
	 */
	public List<Site> read() {
		final List<Site> sites = new ArrayList<>();
		for( final Path file : configFiles ) {
			if( !Files.isRegularFile( file ) ) {
				logger.warn( "Apache config file does not exist or is not a regular file: {}", file );
				continue;
			}
			sites.addAll( readFile( file ) );
		}
		return sites;
	}

	private List<Site> readFile( final Path file ) {
		final String content;
		try {
			content = Files.readString( file, StandardCharsets.UTF_8 );
		}
		catch( final IOException e ) {
			logger.warn( "Failed to read Apache config file {}: {}", file, e.toString() );
			return List.of();
		}

		final List<Site> sites = new ArrayList<>();
		final Matcher vhostMatcher = VHOST_443.matcher( stripComments( content ) );
		while( vhostMatcher.find() ) {
			final String body = vhostMatcher.group( 1 );
			final Site site = parseVhost( file, body );
			if( site != null ) {
				sites.add( site );
			}
		}
		return sites;
	}

	private Site parseVhost( final Path file, final String body ) {
		final String serverName = firstMatch( SERVER_NAME, body );
		final String certPath = firstMatch( SSL_CERT, body );
		final String keyPath = firstMatch( SSL_KEY, body );

		if( serverName == null || certPath == null || keyPath == null ) {
			logger.warn( "Skipping vhost in {} — missing one of ServerName / SSLCertificateFile / SSLCertificateKeyFile", file );
			return null;
		}

		final List<String> aliases = allMatches( SERVER_ALIAS, body ).stream()
				.flatMap( line -> Stream.of( line.split( "\\s+" ) ) )
				.filter( s -> !s.isEmpty() )
				.toList();

		return new Site( serverName, aliases, Path.of( certPath ), Path.of( keyPath ) );
	}

	private static String firstMatch( final Pattern p, final String body ) {
		final Matcher m = p.matcher( body );
		return m.find() ? m.group( 1 ).trim() : null;
	}

	private static List<String> allMatches( final Pattern p, final String body ) {
		final List<String> results = new ArrayList<>();
		final Matcher m = p.matcher( body );
		while( m.find() ) {
			results.add( m.group( 1 ).trim() );
		}
		return results;
	}

	/**
	 * Strips Apache-style {@code # ...} comments so directives inside
	 * commented-out lines are not picked up.
	 */
	private static String stripComments( final String content ) {
		final StringBuilder out = new StringBuilder( content.length() );
		for( final String line : content.split( "\\R", -1 ) ) {
			final int hash = indexOfUnquoted( line, '#' );
			out.append( hash < 0 ? line : line.substring( 0, hash ) ).append( '\n' );
		}
		return out.toString();
	}

	private static int indexOfUnquoted( final String s, final char target ) {
		boolean inQuote = false;
		for( int i = 0; i < s.length(); i++ ) {
			final char c = s.charAt( i );
			if( c == '"' ) {
				inQuote = !inQuote;
			}
			else if( !inQuote && c == target ) {
				return i;
			}
		}
		return -1;
	}
}
