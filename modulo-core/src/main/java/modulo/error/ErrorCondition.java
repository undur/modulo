package modulo.error;

/**
 * The proxy-level failure conditions modulo can respond to. Each carries the
 * HTTP status it maps to and the default human-facing text; how a condition
 * is actually answered is decided by {@link ErrorHandling}, where a custom
 * {@link ErrorResponder} can be assigned per condition.
 */
public enum ErrorCondition {

	/** The request's hostname has no app configured (and isn't an adaptor URL). */
	NO_APP_FOR_HOST( 404, "Nothing here", "No application is configured to serve this address." ),

	/** The site's app isn't in the adaptor config — down, or being restarted. */
	APP_UNAVAILABLE( 503, "Temporarily unavailable", "The application serving this site is not available right now — it may be restarting. Please try again in a moment." ),

	/** The app is known but has no running instances registered. */
	NO_INSTANCES( 503, "Temporarily unavailable", "The application serving this site has no running instances — it may be starting up. Please try again in a moment." ),

	/** Connecting to the app instance failed (refused, reset, mid-response failure). */
	UPSTREAM_UNREACHABLE( 502, "Temporarily unavailable", "The application serving this site could not be reached — it may be restarting. Please try again in a moment." ),

	/** The app instance did not respond within the proxy timeout. */
	UPSTREAM_TIMEOUT( 504, "No response", "The application serving this site did not respond in time. Please try again in a moment." ),

	/** Anything else — the catch-all. */
	INTERNAL( 500, "Something went wrong", "An unexpected error occurred while handling this request." );

	private final int _httpStatus;
	private final String _title;
	private final String _message;

	ErrorCondition( final int httpStatus, final String title, final String message ) {
		_httpStatus = httpStatus;
		_title = title;
		_message = message;
	}

	public int httpStatus() {
		return _httpStatus;
	}

	public String title() {
		return _title;
	}

	public String message() {
		return _message;
	}
}
