package modulo.error;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Answers one {@link ErrorCondition}. Implementations must complete the
 * response (write content and succeed/fail the callback).
 */
@FunctionalInterface
public interface ErrorResponder {

	void respond( ErrorCondition condition, Request request, Response response, Callback callback );
}
