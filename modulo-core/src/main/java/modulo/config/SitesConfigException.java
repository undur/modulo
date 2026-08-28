package modulo.config;

/**
 * Thrown when the native sites config can't be parsed or fails validation.
 *
 * A distinct type (rather than a bare IllegalArgumentException) so future
 * consumers — notably a config-validating admin API — can catch "the
 * operator's config is bad" separately from "something broke".
 */
public class SitesConfigException extends RuntimeException {

	public SitesConfigException( final String message ) {
		super( message );
	}

	public SitesConfigException( final String message, final Throwable cause ) {
		super( message, cause );
	}
}
