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
 * Iteration 1: populated by reading existing Apache vhost files. See
 * {@code modulo.frontend.apache.ApacheConfigReader}.
 */
public record Site(
		String primaryHostname,
		List<String> aliases,
		Path certPath,
		Path keyPath,
		boolean canonicalRedirect,
		boolean httpsRedirect ) {

	public Site {
		Objects.requireNonNull( primaryHostname, "primaryHostname" );
		Objects.requireNonNull( aliases, "aliases" );
		Objects.requireNonNull( certPath, "certPath" );
		Objects.requireNonNull( keyPath, "keyPath" );
		aliases = List.copyOf( aliases );
	}

	/**
	 * Convenience constructor with the policy defaults (both redirects on).
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
