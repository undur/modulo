package modulo.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.json.JsonMapper;

import modulo.DomainApp;
import modulo.frontend.apache.ApacheConfigReader;
import modulo.frontend.site.Site;

/**
 * One-shot migration tool: converts the Apache-vhost-derived site setup into
 * modulo's native sites config JSON.
 *
 * Reads Sites from an Apache vhost manifest (the same file the transitional
 * runtime path uses) and resolves each site's app via the hardcoded
 * {@link DomainApp} map — producing the JSON that
 * {@link SitesConfigReader} consumes. Run it once per deployment, review the
 * output, point {@code modulo.frontend.sites-file} at it, and the Apache
 * config is out of the loop.
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
		final String json = toJson( sites, DomainApp::appForHost );

		if( args.length == 2 ) {
			Files.writeString( Path.of( args[1] ), json, StandardCharsets.UTF_8 );
			System.err.println( "Wrote %d site(s) to %s".formatted( sites.size(), args[1] ) );
		}
		else {
			System.out.println( json );
		}
	}

	/**
	 * @param appForHost Resolves a hostname to its app name; null means "no app known for this host"
	 * @return The native sites config JSON for the given Sites
	 */
	public static String toJson( final List<Site> sites, final Function<String, String> appForHost ) {
		final List<Map<String, Object>> siteEntries = new ArrayList<>();
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

			final Map<String, Object> entry = new LinkedHashMap<>();
			entry.put( "hostnames", site.allHostnames() );
			if( app != null ) {
				entry.put( "app", app );
			}
			entry.put( "tls", Map.of( "mode", "manual", "cert", site.certPath().toString(), "key", site.keyPath().toString() ) );
			siteEntries.add( entry );
		}

		return JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString( Map.of( "sites", siteEntries ) );
	}
}
