package modulo.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ErrorHandlingTest {

	@Test
	void defaultPageContainsStatusTitleAndMessage() {
		for( final ErrorCondition condition : ErrorCondition.values() ) {
			final String html = DefaultErrorPage.html( condition.httpStatus(), condition.title(), condition.message() );
			assertTrue( html.contains( String.valueOf( condition.httpStatus() ) ) );
			assertTrue( html.contains( condition.title() ) );
			assertTrue( html.contains( condition.message() ) );
		}
	}

	@Test
	void defaultPageLeaksNoInternals() {
		final String html = DefaultErrorPage.html( 502, ErrorCondition.UPSTREAM_UNREACHABLE.title(), ErrorCondition.UPSTREAM_UNREACHABLE.message() );
		assertFalse( html.toLowerCase().contains( "jetty" ) );
		assertFalse( html.toLowerCase().contains( "exception" ) );
		assertFalse( html.toLowerCase().contains( "modulo" ) );
	}

	@Test
	void assignedResponderReplacesTheDefault() {
		final ErrorHandling handling = ErrorHandling.withDefaults();
		final List<ErrorCondition> invoked = new ArrayList<>();

		handling.assign( ErrorCondition.APP_UNAVAILABLE, ( condition, request, response, callback ) -> invoked.add( condition ) );

		// The custom responder runs for its condition (null request/response/callback — the responder ignores them)
		handling.respond( ErrorCondition.APP_UNAVAILABLE, null, null, null );
		assertEquals( List.of( ErrorCondition.APP_UNAVAILABLE ), invoked );
	}

	@Test
	void conditionStatusMapping() {
		assertEquals( 404, ErrorCondition.NO_APP_FOR_HOST.httpStatus() );
		assertEquals( 503, ErrorCondition.APP_UNAVAILABLE.httpStatus() );
		assertEquals( 503, ErrorCondition.NO_INSTANCES.httpStatus() );
		assertEquals( 502, ErrorCondition.UPSTREAM_UNREACHABLE.httpStatus() );
		assertEquals( 504, ErrorCondition.UPSTREAM_TIMEOUT.httpStatus() );
		assertEquals( 500, ErrorCondition.INTERNAL.httpStatus() );
	}
}
