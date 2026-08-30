package modulo.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import modulo.DomainApp;
import modulo.frontend.site.Site;

/**
 * One-shot migration tool: converts an Apache + mod_WebObjects vhost setup
 * into modulo's native sites config JSON.
 *
 * Reads Sites from a manifest file (one vhost-file path per line, {@code #}
 * comments allowed) and emits the TOML that {@link SitesConfigReader}
 * consumes, with every site in {@code manual} TLS mode pointing at the
 * vhosts' existing PEM paths. Run it once, review the output, point
 * {@code modulo.frontend.sites-file} at it — Apache is out of the loop.
 *
 * Hostname → app mappings aren't in Apache config, so sites are emitted
 * without an {@code "app"} (with a warning) for the operator to fill in.
 * Alternatively, supply mappings when running the importer via
 * {@code -Dmodulo.domain-app.<host>=<app>} properties (see {@link DomainApp}).
 *
 * Usage: {@code ApacheConfigImporter <manifest-file> [output-file]}
 * (prints to stdout when no output file is given).
 */
public class ApacheConfigImporter {

	private static final Logger logger = LoggerFactory.getLogger( ApacheConfigImporter.class );

	public static void main( final String[] args ) throws IOException {
		if( args.length < 1 || args.length > 2 ) {
			System.err.println( "Usage: ApacheConfigImporter <manifest-file> [output-file]" );
			System.exit( 1 );
		}

		final List<Site> sites = ApacheConfigReader.fromManifest( Path.of( args[0] ) ).read();
		final String toml = toToml( sites, DomainApp::appForHost );

		if( args.length == 2 ) {
			Files.writeString( Path.of( args[1] ), toml, StandardCharsets.UTF_8 );
			System.err.println( "Wrote %d site(s) to %s".formatted( sites.size(), args[1] ) );
		}
		else {
			System.out.println( toml );
		}
	}

	/**
	 * @param appForHost Resolves a hostname to its app name; null means "no app known for this host"
	 * @return The native sites config TOML for the given Sites
	 */
	public static String toToml( final List<Site> sites, final Function<String, String> appForHost ) {
		final StringBuilder toml = new StringBuilder();
		final Set<String> seenPrimaries = new HashSet<>();

		for( final Site site : sites ) {
			// A vhost file listed twice in the manifest yields duplicate Sites;
			// the strict reader would refuse the output, so drop repeats here
			if( !seenPrimaries.add( site.primaryHostname() ) ) {
				logger.warn( "Skipping duplicate site {} — listed more than once in the Apache config", site.primaryHostname() );
				continue;
			}
			// The old model maps individual hostnames to apps; a Site's app is the first of its hostnames that resolves
			final String app = site.allHostnames().stream()
					.map( appForHost )
					.filter( a -> a != null )
					.findFirst()
					.orElse( null );

			if( app == null ) {
				logger.warn( "No app mapping found for any hostname of site {} — emitting it without an \"app\"", site.primaryHostname() );
			}

			toml.append( "[[sites]]\n" );
			toml.append( "hostnames = [ " );
			toml.append( String.join( ", ", site.allHostnames().stream().map( h -> "\"" + h + "\"" ).toList() ) );
			toml.append( " ]\n" );
			if( app != null ) {
				toml.append( "app = \"" ).append( app ).append( "\"\n" );
			}
			toml.append( "tls = { mode = \"manual\", cert = \"" ).append( site.certPath() ).append( "\", key = \"" ).append( site.keyPath() ).append( "\" }\n" );
			toml.append( "\n" );
		}

		return toml.toString().stripTrailing() + "\n";
	}

}
