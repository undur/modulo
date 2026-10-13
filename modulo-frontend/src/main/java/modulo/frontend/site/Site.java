package modulo.frontend.site;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Operator-facing front-end configuration for one logical site.
 *
 * A Site ties together the hostnames the front-end answers for, the TLS
 * material it presents, and policy flags. It deliberately knows nothing
 * about upstream routing — that remains modulo's existing responsibility.
 *
 * {@code name} is the site's identity: it names the site's certificate
 * directory, its access-log directory and its keystore entry, and is what
 * the admin pages, logs and events call it. It is deliberately separate from
 * {@code primaryHostname}, which only says where aliases redirect to, so
 * changing a site's canonical domain doesn't move anything on disk. It
 * defaults to the primary hostname.
 *
 * Populated from the sites config; see {@code modulo.config.SitesConfigReader}.
 */
public record Site(
		String name,
		String primaryHostname,
		List<String> aliases,
		Path certPath,
		Path keyPath,
		boolean canonicalRedirect,
		boolean httpsRedirect ) {

	public Site {
		Objects.requireNonNull( name, "name" );
		Objects.requireNonNull( primaryHostname, "primaryHostname" );
		Objects.requireNonNull( aliases, "aliases" );
		Objects.requireNonNull( certPath, "certPath" );
		Objects.requireNonNull( keyPath, "keyPath" );
		aliases = List.copyOf( aliases );
	}

	/**
	 * A site named after its primary hostname.
	 */
	public Site( String primaryHostname, List<String> aliases, Path certPath, Path keyPath, boolean canonicalRedirect, boolean httpsRedirect ) {
		this( primaryHostname, primaryHostname, aliases, certPath, keyPath, canonicalRedirect, httpsRedirect );
	}

	/**
	 * A site named after its primary hostname, with the policy defaults (both redirects on).
	 */
	public Site( String primaryHostname, List<String> aliases, Path certPath, Path keyPath ) {
		this( primaryHostname, aliases, certPath, keyPath, true, true );
	}

	/**
	 * @return All hostnames this site answers for — primary + aliases.
	 */
	public List<String> allHostnames() {
		final List<String> all = new java.util.ArrayList<>( aliases.size() + 1 );
		all.add( primaryHostname );
		all.addAll( aliases );
		return List.copyOf( all );
	}
}
