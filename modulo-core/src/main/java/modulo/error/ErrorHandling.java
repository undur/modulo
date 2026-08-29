package modulo.error;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * The registry deciding how each {@link ErrorCondition} is answered.
 *
 * Starts with every condition mapped to the default error page; individual
 * conditions can be reassigned via {@link #assign} — e.g. a maintenance
 * page for {@link ErrorCondition#APP_UNAVAILABLE}, or a redirect for
 * {@link ErrorCondition#NO_APP_FOR_HOST}. Global for now; per-Site
 * assignment can layer on top once the config schema grows a slot for it
 * (issue #5).
 */
public class ErrorHandling {

	private final Map<ErrorCondition, ErrorResponder> _responders = new EnumMap<>( ErrorCondition.class );

	private ErrorHandling() {}

	/**
	 * @return A registry with every condition answered by the default error page
	 */
	public static ErrorHandling withDefaults() {
		final ErrorHandling handling = new ErrorHandling();
		for( final ErrorCondition condition : ErrorCondition.values() ) {
			handling.assign( condition, DefaultErrorPage::respond );
		}
		return handling;
	}

	/**
	 * Assigns a custom responder for [condition], replacing the current one.
	 */
	public void assign( final ErrorCondition condition, final ErrorResponder responder ) {
		Objects.requireNonNull( condition, "condition" );
		Objects.requireNonNull( responder, "responder" );
		_responders.put( condition, responder );
	}

	/**
	 * Answers the request with the responder assigned to [condition].
	 */
	public void respond( final ErrorCondition condition, final Request request, final Response response, final Callback callback ) {
		_responders.get( condition ).respond( condition, request, response, callback );
	}
}
