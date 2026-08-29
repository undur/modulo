package modulo.rewrite;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One per-site URL rewrite rule: a regex matched against the request path and
 * a substitution target with {@code $1}–{@code $9} capture references —
 * modulo's replacement for the Apache {@code RewriteRule} directives that
 * mapped friendly URLs into adaptor URL space.
 *
 * A site's rules are tried in config order; the first whose pattern matches
 * decides the outcome (Apache's {@code [L]} behavior, always on). Rules are
 * only consulted for paths outside the adaptor URL space — an URL that is
 * already {@code /Apps/WebObjects/...} routes as-is, so a catch-all rule
 * can't loop and app-generated URLs pass untouched.
 *
 * Without {@code redirect} the rule rewrites internally (Apache's
 * {@code [PT]}): the request proceeds to the app under the substituted path.
 * With {@code redirect} ("temporary" or "permanent") the client is sent a
 * 302/301 to the target, which may be a path or an absolute URL.
 *
 * Query string semantics mirror Apache: a target without a query part keeps
 * the request's original query; a target with its own query replaces it,
 * unless {@code appendQuery} (Apache's {@code [QSA]}) merges the original in
 * after the rule's. {@code encodeCaptures} (Apache's {@code [B]}) URL-encodes
 * each substituted capture — for captures that become query parameter values.
 */
public record RewriteRule( Pattern pattern, String target, Redirect redirect, boolean appendQuery, boolean encodeCaptures ) {

	public enum Redirect {
		NONE, TEMPORARY, PERMANENT
	}

	public RewriteRule {
		Objects.requireNonNull( pattern, "pattern" );
		Objects.requireNonNull( target, "target" );
		Objects.requireNonNull( redirect, "redirect" );
	}

	/** The outcome of a matching rule. */
	public sealed interface Result {}

	/** Proceed to the app under the substituted path/query. Query is null when there is none. */
	public record Rewritten( String path, String query ) implements Result {}

	/** Answer the client with a redirect to [location]. */
	public record Redirected( String location, boolean permanent ) implements Result {}

	/**
	 * @return The outcome of the first matching rule, or null when no rule
	 *         matches (the request proceeds unrewritten)
	 */
	public static Result firstMatch( final List<RewriteRule> rules, final String path, final String query ) {
		for( final RewriteRule rule : rules ) {
			final Result result = rule.apply( path, query );
			if( result != null ) {
				return result;
			}
		}
		return null;
	}

	/**
	 * @return The outcome of this rule for the given request path/query, or
	 *         null when the pattern doesn't match
	 */
	Result apply( final String path, final String originalQuery ) {
		final Matcher matcher = pattern.matcher( path );

		// Unanchored, like Apache's RewriteRule — patterns anchor themselves with ^ and $
		if( !matcher.find() ) {
			return null;
		}

		final String substituted = substituteCaptures( matcher );

		// The target's own query part (after the first '?') replaces the
		// original; a target without one keeps the original; appendQuery
		// merges the original in after the rule's.
		final int questionMark = substituted.indexOf( '?' );
		final String targetPath = questionMark == -1 ? substituted : substituted.substring( 0, questionMark );
		final String targetQuery = questionMark == -1 ? null : substituted.substring( questionMark + 1 );

		final String effectiveQuery;
		if( targetQuery == null ) {
			effectiveQuery = emptyToNull( originalQuery );
		}
		else if( appendQuery && emptyToNull( originalQuery ) != null ) {
			effectiveQuery = targetQuery + "&" + originalQuery;
		}
		else {
			effectiveQuery = targetQuery;
		}

		if( redirect == Redirect.NONE ) {
			return new Rewritten( targetPath, effectiveQuery );
		}

		final String location = effectiveQuery == null ? targetPath : targetPath + "?" + effectiveQuery;
		return new Redirected( location, redirect == Redirect.PERMANENT );
	}

	/**
	 * @return [target] with each {@code $1}–{@code $9} replaced by the
	 *         corresponding capture group. {@code $$} escapes a literal '$'.
	 */
	private String substituteCaptures( final Matcher matcher ) {
		final StringBuilder out = new StringBuilder( target.length() );

		for( int i = 0; i < target.length(); i++ ) {
			final char c = target.charAt( i );

			if( c == '$' && i + 1 < target.length() ) {
				final char next = target.charAt( i + 1 );
				if( next >= '1' && next <= '9' ) {
					final String value = matcher.group( next - '0' );
					if( value != null ) {
						out.append( encodeCaptures ? URLEncoder.encode( value, StandardCharsets.UTF_8 ) : value );
					}
					i++;
					continue;
				}
				if( next == '$' ) {
					out.append( '$' );
					i++;
					continue;
				}
			}

			out.append( c );
		}

		return out.toString();
	}

	/**
	 * @return The highest capture group number this rule's target references —
	 *         for config-time validation against the pattern's group count
	 */
	public int highestReferencedGroup() {
		int highest = 0;
		for( int i = 0; i < target.length() - 1; i++ ) {
			if( target.charAt( i ) == '$' ) {
				final char next = target.charAt( i + 1 );
				if( next >= '1' && next <= '9' ) {
					highest = Math.max( highest, next - '0' );
				}
				i++;
			}
		}
		return highest;
	}

	private static String emptyToNull( final String value ) {
		return value == null || value.isEmpty() ? null : value;
	}
}
