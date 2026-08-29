package modulo.error;

/**
 * Thrown when a request can't be routed to an upstream instance, carrying
 * the {@link ErrorCondition} describing why — so the proxy can answer with
 * the right error page instead of a generic 500.
 */
public class ProxyRoutingException extends RuntimeException {

	private final ErrorCondition _condition;

	public ProxyRoutingException( final ErrorCondition condition, final String message ) {
		super( message );
		_condition = condition;
	}

	public ErrorCondition condition() {
		return _condition;
	}
}
