package modulo.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import modulo.config.SitesConfig.ConfiguredSite;
import modulo.frontend.site.Site;
import modulo.frontend.tls.acme.AcmeSettings;
import modulo.rewrite.RewriteRule;

/**
 * Reads modulo's native sites config — a single JSON file:
 *
 * <pre>
 * {
 *   "acme": {
 *     "email": "operator@example.com",
 *     "storage": "/var/lib/modulo/acme",
 *     "directory": "letsencrypt"            // optional; also: "letsencrypt-staging" or a directory URI
 *   },
 *   "sites": [
 *     {
 *       "hostnames": [ "www.rebbi.is", "rebbi.is" ],
 *       "app": "Rebbi"
 *       // no "tls" — ACME is the default: modulo obtains and renews the cert itself
 *     },
 *     {
 *       "hostnames": [ "legacy.example" ],
 *       "app": "Legacy",
 *       "tls": { "mode": "manual", "cert": "/path/fullchain.pem", "key": "/path/privkey.pem" }
 *     }
 *   ]
 * }
 * </pre>
 *
 * The first hostname is the site's primary (canonical) hostname; the rest are
 * aliases. {@code app} is optional. {@code canonicalRedirect} and
 * {@code httpsRedirect} are optional booleans defaulting to true.
 *
 * TLS: an omitted {@code tls} block (or {@code "mode": "acme"}) means modulo
 * manages the certificate via ACME — this requires the top-level {@code acme}
 * block, and the site's cert/key paths are derived inside the ACME storage
 * directory. {@code "mode": "manual"} takes explicit PEM paths instead.
 *
 * A site may carry {@code "rewrites"} — an ordered list of URL rewrite rules
 * tried first-match-wins against the request path (only for paths outside the
 * adaptor URL space):
 *
 * <pre>
 * "rewrites": [
 *   { "match": "^/$", "to": "/Apps/WebObjects/Strimillinn.woa/wa/default" },
 *   { "match": "^/app/([^/]+)/receipts$", "to": "/Apps/WebObjects/Strimillinn.woa/wa/AppAction/receipts?token=$1" },
 *   { "match": "^/policy$", "to": "/privacy", "redirect": "permanent" }
 * ]
 * </pre>
 *
 * {@code match} is a Java regex (unanchored — anchor with ^ and $), {@code to}
 * the substitution with {@code $1}–{@code $9} capture references. Without
 * {@code redirect} the rule rewrites internally and the request proceeds to
 * the site's app; {@code "redirect": "temporary"|"permanent"} answers 302/301
 * instead (paths and absolute URLs both work as targets). Optional booleans:
 * {@code appendQuery} (merge the original query string after the target's,
 * Apache's QSA) and {@code encodeCaptures} (URL-encode substituted captures,
 * Apache's B). See {@link modulo.rewrite.RewriteRule} for full semantics.
 *
 * Sites may also live in separate files: the main file's {@code "include"}
 * lists paths or glob patterns (e.g. {@code "/rebbi/*&#47;conf/site.json"};
 * relative patterns resolve against the main file's directory), and each
 * included file contributes a {@code "sites"} array of its own. The
 * deployment-wide blocks ({@code acme}, {@code include}) belong to the main
 * file only. A pattern without wildcards must match an existing file; a
 * wildcard pattern may match none (logged as a warning).
 *
 * Parsing is strict on purpose: unknown fields, duplicate hostnames and
 * malformed entries all throw {@link SitesConfigException} rather than being
 * skipped — this file is the source of truth, so a typo should stop the
 * front-end from starting with half a config. {@code //} comments and
 * trailing commas are allowed as operator-friendliness.
 */
public class SitesConfigReader {

	private static final Logger logger = LoggerFactory.getLogger( SitesConfigReader.class );

	private static final JsonMapper MAPPER = JsonMapper.builder()
			.enable( JsonReadFeature.ALLOW_JAVA_COMMENTS )
			.enable( JsonReadFeature.ALLOW_TRAILING_COMMA )
			.enable( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES )
			.build();

	/** The raw JSON shape of the main config file. Field names here are the config file's schema. */
	record Root( Acme acme, List<String> include, List<SiteEntry> sites ) {}

	/**
	 * The raw shape of an included file: sites only. The deployment-wide
	 * blocks ({@code acme}, {@code include}) live in the main file alone —
	 * strict parsing rejects them here as unknown fields.
	 */
	record Fragment( List<SiteEntry> sites ) {}

	record Acme( String email, String directory, String storage ) {}

	record SiteEntry( List<String> hostnames, String app, Tls tls, Boolean canonicalRedirect, Boolean httpsRedirect, List<RewriteEntry> rewrites ) {}

	record Tls( String mode, String cert, String key ) {}

	record RewriteEntry( String match, String to, String redirect, Boolean appendQuery, Boolean encodeCaptures ) {}

	public static SitesConfig read( final Path file ) throws IOException {
		final Root root = parseRoot( Files.readString( file, StandardCharsets.UTF_8 ), file.toString() );
		return assemble( root, file );
	}

	/**
	 * Parses a config given as a string. Since there is no file to resolve
	 * against, {@code include} is not allowed here.
	 *
	 * @param json The config file's content
	 * @param source Where the content came from, for error messages (typically the file path)
	 */
	public static SitesConfig parse( final String json, final String source ) {
		final Root root = parseRoot( json, source );
		if( root.include() != null && !root.include().isEmpty() ) {
			throw new SitesConfigException( "\"include\" in %s requires the config to be read from a file (patterns resolve relative to it)".formatted( source ) );
		}
		try {
			return assemble( root, Path.of( source ) );
		}
		catch( final IOException e ) {
			throw new SitesConfigException( "Failed reading includes of %s: %s".formatted( source, e.getMessage() ), e );
		}
	}

	private static Root parseRoot( final String json, final String source ) {
		final Root root;
		try {
			root = MAPPER.readValue( json, Root.class );
		}
		catch( final JacksonException e ) {
			throw new SitesConfigException( "Failed to parse sites config %s: %s".formatted( source, e.getMessage() ), e );
		}

		if( root == null || (root.sites() == null && (root.include() == null || root.include().isEmpty())) ) {
			throw new SitesConfigException( "Sites config %s needs a top-level \"sites\" array, an \"include\" list, or both".formatted( source ) );
		}
		return root;
	}

	private static SitesConfig assemble( final Root root, final Path mainFile ) throws IOException {
		final String mainSource = mainFile.toString();
		final AcmeSettings acmeSettings = toAcmeSettings( root.acme(), mainSource );

		final List<ConfiguredSite> sites = new ArrayList<>();
		final Map<String, String> hostnameOwner = new HashMap<>();

		if( root.sites() != null ) {
			addSites( root.sites(), mainSource, acmeSettings, sites, hostnameOwner );
		}

		if( root.include() != null ) {
			final Path baseDir = mainFile.toAbsolutePath().getParent();
			for( final String pattern : root.include() ) {
				final List<Path> files = IncludeResolver.resolve( baseDir, pattern );
				if( files.isEmpty() ) {
					logger.warn( "Include pattern \"{}\" in {} matched no files", pattern, mainSource );
				}
				for( final Path included : files ) {
					final Fragment fragment;
					try {
						fragment = MAPPER.readValue( Files.readString( included, StandardCharsets.UTF_8 ), Fragment.class );
					}
					catch( final JacksonException e ) {
						throw new SitesConfigException( "Failed to parse included sites file %s: %s".formatted( included, e.getMessage() ), e );
					}
					if( fragment == null || fragment.sites() == null ) {
						throw new SitesConfigException( "Included sites file %s is missing its \"sites\" array".formatted( included ) );
					}
					addSites( fragment.sites(), included.toString(), acmeSettings, sites, hostnameOwner );
				}
			}
		}

		return new SitesConfig( sites, acmeSettings );
	}

	private static void addSites(
			final List<SiteEntry> entries,
			final String source,
			final AcmeSettings acmeSettings,
			final List<ConfiguredSite> sites,
			final Map<String, String> hostnameOwner ) {

		for( int i = 0; i < entries.size(); i++ ) {
			final ConfiguredSite configuredSite = toConfiguredSite( entries.get( i ), i, acmeSettings, source );
			for( final String hostname : configuredSite.site().allHostnames() ) {
				final String owner = "%s (%s)".formatted( configuredSite.site().primaryHostname(), source );
				final String previousOwner = hostnameOwner.putIfAbsent( hostname, owner );
				if( previousOwner != null ) {
					throw new SitesConfigException( "Hostname %s appears more than once — in site %s and again in site %s".formatted( hostname, previousOwner, owner ) );
				}
			}
			sites.add( configuredSite );
		}
	}

	private static AcmeSettings toAcmeSettings( final Acme acme, final String source ) {

		if( acme == null ) {
			return null;
		}

		if( acme.email() == null ) {
			throw new SitesConfigException( "The \"acme\" block in %s needs an \"email\" (the CA sends expiry warnings there)".formatted( source ) );
		}
		if( acme.storage() == null ) {
			throw new SitesConfigException( "The \"acme\" block in %s needs a \"storage\" directory (where modulo keeps its account key and issued certs)".formatted( source ) );
		}

		final URI directoryUri;
		try {
			directoryUri = AcmeSettings.resolveDirectory( acme.directory() == null ? "letsencrypt" : acme.directory() );
		}
		catch( final IllegalArgumentException e ) {
			throw new SitesConfigException( "The \"acme\" block in %s has an unusable \"directory\": %s".formatted( source, e.getMessage() ), e );
		}

		return new AcmeSettings( acme.email(), directoryUri, Path.of( acme.storage() ) );
	}

	private static ConfiguredSite toConfiguredSite( final SiteEntry entry, final int index, final AcmeSettings acmeSettings, final String source ) {
		final String context = "site #%d in %s".formatted( index + 1, source );

		if( entry == null ) {
			throw new SitesConfigException( "%s is null".formatted( context ) );
		}

		if( entry.hostnames() == null || entry.hostnames().isEmpty() ) {
			throw new SitesConfigException( "%s has no \"hostnames\" — every site needs at least one, the first being the primary/canonical hostname".formatted( context ) );
		}

		// Hostnames are case-insensitive by nature; normalize to lowercase so lookups are trivial
		final List<String> hostnames = entry.hostnames().stream()
				.map( h -> h == null ? "" : h.trim().toLowerCase( Locale.ROOT ) )
				.toList();

		if( hostnames.stream().anyMatch( String::isEmpty ) ) {
			throw new SitesConfigException( "%s contains an empty hostname".formatted( context ) );
		}

		final String primaryHostname = hostnames.getFirst();
		final Tls tls = entry.tls();
		final String mode = (tls == null || tls.mode() == null) ? "acme" : tls.mode();

		final Path certPath;
		final Path keyPath;
		final boolean acmeManaged;

		switch( mode ) {
			case "acme" -> {
				if( acmeSettings == null ) {
					throw new SitesConfigException( "%s uses ACME (the default when \"tls\" is omitted) but %s has no top-level \"acme\" block — add \"acme\": { \"email\": ..., \"storage\": ... }, or give the site \"tls\": { \"mode\": \"manual\", ... }".formatted( context, source ) );
				}
				if( tls != null && (tls.cert() != null || tls.key() != null) ) {
					throw new SitesConfigException( "%s: tls mode \"acme\" derives cert storage from the \"acme\" block — remove \"cert\"/\"key\"".formatted( context ) );
				}
				certPath = acmeSettings.certPathFor( primaryHostname );
				keyPath = acmeSettings.keyPathFor( primaryHostname );
				acmeManaged = true;
			}
			case "manual" -> {
				if( tls.cert() == null || tls.key() == null ) {
					throw new SitesConfigException( "%s: tls mode \"manual\" requires both \"cert\" and \"key\" PEM paths".formatted( context ) );
				}
				certPath = Path.of( tls.cert() );
				keyPath = Path.of( tls.key() );
				acmeManaged = false;
			}
			default -> throw new SitesConfigException( "%s: unknown tls mode \"%s\" (valid: \"acme\", \"manual\")".formatted( context, mode ) );
		}

		final Site site = new Site(
				primaryHostname,
				hostnames.subList( 1, hostnames.size() ),
				certPath,
				keyPath,
				entry.canonicalRedirect() == null || entry.canonicalRedirect(),
				entry.httpsRedirect() == null || entry.httpsRedirect() );

		return new ConfiguredSite( site, entry.app(), acmeManaged, toRewriteRules( entry.rewrites(), context ) );
	}

	private static List<RewriteRule> toRewriteRules( final List<RewriteEntry> entries, final String siteContext ) {

		if( entries == null || entries.isEmpty() ) {
			return List.of();
		}

		final List<RewriteRule> rules = new ArrayList<>( entries.size() );

		for( int i = 0; i < entries.size(); i++ ) {
			final RewriteEntry entry = entries.get( i );
			final String context = "rewrite #%d of %s".formatted( i + 1, siteContext );

			if( entry == null || entry.match() == null || entry.match().isBlank() ) {
				throw new SitesConfigException( "%s needs a \"match\" regex".formatted( context ) );
			}
			if( entry.to() == null || entry.to().isBlank() ) {
				throw new SitesConfigException( "%s needs a \"to\" target".formatted( context ) );
			}

			final Pattern pattern;
			try {
				pattern = Pattern.compile( entry.match() );
			}
			catch( final PatternSyntaxException e ) {
				throw new SitesConfigException( "%s has an invalid \"match\" regex: %s".formatted( context, e.getMessage() ), e );
			}

			final RewriteRule.Redirect redirect = switch( entry.redirect() == null ? "" : entry.redirect() ) {
				case "" -> RewriteRule.Redirect.NONE;
				case "temporary" -> RewriteRule.Redirect.TEMPORARY;
				case "permanent" -> RewriteRule.Redirect.PERMANENT;
				default -> throw new SitesConfigException( "%s has unknown \"redirect\" value \"%s\" (valid: \"temporary\", \"permanent\", or omit for an internal rewrite)".formatted( context, entry.redirect() ) );
			};

			final RewriteRule rule = new RewriteRule(
					pattern,
					entry.to(),
					redirect,
					entry.appendQuery() != null && entry.appendQuery(),
					entry.encodeCaptures() != null && entry.encodeCaptures() );

			if( rule.highestReferencedGroup() > pattern.matcher( "" ).groupCount() ) {
				throw new SitesConfigException( "%s references capture group $%d but \"match\" only has %d group(s)".formatted( context, rule.highestReferencedGroup(), pattern.matcher( "" ).groupCount() ) );
			}

			rules.add( rule );
		}

		return List.copyOf( rules );
	}
}
