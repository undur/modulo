package modulo.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import modulo.frontend.site.Site;
import modulo.frontend.tls.acme.AcmeSettings;

/**
 * The parsed native sites config: modulo's operator-facing source of truth
 * for which sites the front-end serves and which upstream app each one
 * routes to.
 *
 * Lives in modulo-core rather than modulo-frontend because it spans both
 * concerns: the front-end {@link Site} (hostnames, TLS, policy) and the
 * routing target ({@code app}), which the front-end deliberately knows
 * nothing about.
 *
 * @param acme Deployment-wide ACME settings; null when no site uses ACME.
 */
public record SitesConfig( List<ConfiguredSite> sites, AcmeSettings acme ) {

	public SitesConfig {
		Objects.requireNonNull( sites, "sites" );
		sites = List.copyOf( sites );
	}

	/**
	 * One entry from the config file: the front-end Site plus the name of the
	 * upstream app its hostnames route to. {@code app} may be null — such a
	 * site gets TLS and redirects but no proxying (a warning is logged at
	 * startup, matching the old behavior for hostnames missing from the
	 * hardcoded domain map). {@code acmeManaged} marks sites whose
	 * certificates modulo itself obtains and renews; their cert/key paths
	 * point into the ACME storage directory.
	 */
	public record ConfiguredSite( Site site, String app, boolean acmeManaged, List<modulo.rewrite.RewriteRule> rewrites, String woa ) {

		public ConfiguredSite {
			Objects.requireNonNull( site, "site" );
			rewrites = rewrites == null ? List.of() : List.copyOf( rewrites );
		}

		public ConfiguredSite( final Site site, final String app, final boolean acmeManaged ) {
			this( site, app, acmeManaged, List.of(), null );
		}
	}

	/**
	 * @return The front-end's view of the config — just the Sites.
	 */
	public List<Site> frontendSites() {
		return sites.stream().map( ConfiguredSite::site ).toList();
	}

	/**
	 * @return The Sites whose certificates modulo obtains/renews via ACME.
	 */
	public List<Site> acmeManagedSites() {
		return sites.stream().filter( ConfiguredSite::acmeManaged ).map( ConfiguredSite::site ).toList();
	}

	/**
	 * @return hostname → app name for every hostname of every site that has
	 *         an app configured. Hostnames are lowercase (normalized at parse
	 *         time).
	 */
	public Map<String, String> domainToAppMap() {
		final Map<String, String> map = new HashMap<>();

		for( final ConfiguredSite configuredSite : sites ) {
			if( configuredSite.app() != null ) {
				for( final String hostname : configuredSite.site().allHostnames() ) {
					map.put( hostname, configuredSite.app() );
				}
			}
		}

		return Map.copyOf( map );
	}

	/**
	 * @return hostname → the site's rewrite rules, for every hostname of
	 *         every site that has rules. Hostnames are lowercase.
	 */
	/**
	 * @return hostname → the site's .woa bundle path, for every hostname of
	 *         every site that declares one. Feeds the WOA-compat
	 *         WebServerResources handler; hostnames are lowercase.
	 */
	public Map<String, java.nio.file.Path> hostToWoa() {
		final Map<String, java.nio.file.Path> map = new HashMap<>();

		for( final ConfiguredSite configuredSite : sites ) {
			if( configuredSite.woa() != null ) {
				for( final String hostname : configuredSite.site().allHostnames() ) {
					map.put( hostname, java.nio.file.Path.of( configuredSite.woa() ) );
				}
			}
		}

		return Map.copyOf( map );
	}

	public Map<String, List<modulo.rewrite.RewriteRule>> hostToRewrites() {
		final Map<String, List<modulo.rewrite.RewriteRule>> map = new HashMap<>();

		for( final ConfiguredSite configuredSite : sites ) {
			if( !configuredSite.rewrites().isEmpty() ) {
				for( final String hostname : configuredSite.site().allHostnames() ) {
					map.put( hostname, configuredSite.rewrites() );
				}
			}
		}

		return Map.copyOf( map );
	}
}
