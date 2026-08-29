package modulo.rewrite;

/**
 * Thrown from the routing path when a redirect-type rewrite rule matches —
 * not an error, but the same exception channel routing failures use to
 * surface from deep inside URI rewriting up to the proxy handler, which
 * answers with the 301/302 instead of proxying.
 */
public class RewriteRedirectException extends RuntimeException {

	private final String _location;
	private final boolean _permanent;

	public RewriteRedirectException( final String location, final boolean permanent ) {
		super( "Rewrite redirect to " + location );
		_location = location;
		_permanent = permanent;
	}

	public String location() {
		return _location;
	}

	public boolean permanent() {
		return _permanent;
	}
}
