package modulo.error;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Modulo's default error page: a small, self-contained HTML document —
 * status code, a short human title and message, nothing else. Deliberately
 * reveals no internals (no app names, hosts, stack traces or server
 * version); the log has the details.
 */
public class DefaultErrorPage {

	private static final String TEMPLATE = """
			<!doctype html>
			<html lang="en">
			<head>
			<meta charset="utf-8">
			<meta name="viewport" content="width=device-width, initial-scale=1">
			<title>%d — %s</title>
			<style>
				:root { color-scheme: light dark; }
				body { margin: 0; min-height: 100vh; display: grid; place-items: center;
				       font-family: system-ui, -apple-system, sans-serif;
				       background: light-dark(#fafafa, #16161a); color: light-dark(#1a1a1a, #e8e8e8); }
				main { text-align: center; padding: 2rem; max-width: 34rem; }
				.status { font-size: 5rem; font-weight: 200; letter-spacing: 0.05em;
				          color: light-dark(#c0c0c4, #46464e); margin: 0; }
				h1 { font-size: 1.4rem; font-weight: 600; margin: 0.5rem 0 1rem; }
				p { margin: 0; line-height: 1.6; color: light-dark(#555, #a5a5ad); }
				.layer { margin-top: 2.5rem; font-size: 1.2rem; opacity: 0.5; cursor: default; }
			</style>
			</head>
			<body>
			<main>
				<p class="status">%d</p>
				<h1>%s</h1>
				<p>%s</p>
				<p class="layer" title="This page comes from modulo, the front-facing proxy — the error occurred before your request reached an application.">🤖</p>
			</main>
			</body>
			</html>
			""";

	static void respond( final ErrorCondition condition, final Request request, final Response response, final Callback callback ) {
		final byte[] content = html( condition.httpStatus(), condition.title(), condition.message() ).getBytes( StandardCharsets.UTF_8 );

		response.setStatus( condition.httpStatus() );
		response.getHeaders().put( HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8" );
		response.getHeaders().put( HttpHeader.CONTENT_LENGTH, String.valueOf( content.length ) );
		response.getHeaders().put( HttpHeader.CACHE_CONTROL, "no-store" );

		// The "try again in a moment" conditions get a polite retry hint
		if( condition.httpStatus() >= 502 ) {
			response.getHeaders().put( HttpHeader.RETRY_AFTER, "10" );
		}

		response.write( true, ByteBuffer.wrap( content ), callback );
	}

	/**
	 * @return The page HTML — pure function, exposed for testing
	 */
	static String html( final int status, final String title, final String message ) {
		return TEMPLATE.formatted( status, title, status, title, message );
	}
}
