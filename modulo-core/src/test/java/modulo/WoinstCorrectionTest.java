package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.jupiter.api.Test;

class WoinstCorrectionTest {

	private static List<String> cookies( final HttpFields.Mutable headers ) {
		return headers.getValuesList( HttpHeader.SET_COOKIE );
	}

	@Test
	void woinstMinusOneIsCorrectedPreservingAttributes() {
		final HttpFields.Mutable headers = HttpFields.build();
		headers.add( HttpHeader.SET_COOKIE, "wosid=abc; version=\"1\"; path=/" );
		headers.add( HttpHeader.SET_COOKIE, "woinst=-1; version=\"1\"; path=/" );

		ModuloProxy.ensureTruthfulWoinst( 4, headers );
		assertEquals( List.of( "wosid=abc; version=\"1\"; path=/", "woinst=4; version=\"1\"; path=/" ), cookies( headers ) );
	}

	/**
	 * The app is never the authority: a WO app "knows" its instance number by
	 * echoing the request's woinst cookie, so after failover its confident
	 * value is the stale client cookie reflected back. Always overwrite.
	 */
	@Test
	void appAssertedWoinstIsOverriddenWithRoutedInstance() {
		final HttpFields.Mutable headers = HttpFields.build();
		headers.add( HttpHeader.SET_COOKIE, "woinst=2; path=/" );

		ModuloProxy.ensureTruthfulWoinst( 4, headers );
		assertEquals( List.of( "woinst=4; path=/" ), cookies( headers ) );
	}

	@Test
	void sessionWithoutWoinstGetsOneAdded() {
		final HttpFields.Mutable headers = HttpFields.build();
		headers.add( HttpHeader.SET_COOKIE, "wosid=abc; path=/" );

		ModuloProxy.ensureTruthfulWoinst( 3, headers );
		assertEquals( List.of( "wosid=abc; path=/", "woinst=3; path=/" ), cookies( headers ) );
	}

	@Test
	void sessionlessResponseIsUntouched() {
		final HttpFields.Mutable headers = HttpFields.build();
		headers.add( HttpHeader.SET_COOKIE, "routeid_app=app_2011; path=/" );

		ModuloProxy.ensureTruthfulWoinst( 3, headers );
		assertEquals( List.of( "routeid_app=app_2011; path=/" ), cookies( headers ) );
	}

	@Test
	void nullInstanceLeavesEverythingAlone() {
		final HttpFields.Mutable headers = HttpFields.build();
		headers.add( HttpHeader.SET_COOKIE, "woinst=-1; path=/" );

		ModuloProxy.ensureTruthfulWoinst( null, headers );
		assertEquals( List.of( "woinst=-1; path=/" ), cookies( headers ) );
	}

	@Test
	void anyTokenShapeIsReplaced() {
		assertEquals( "woinst=5; path=/", ModuloProxy.correctedWoinst( "woinst=\"-1\"; path=/", 5 ) );
		assertEquals( "woinst=5; path=/", ModuloProxy.correctedWoinst( "woinst=3; path=/", 5 ) );
		assertEquals( "woinst=5", ModuloProxy.correctedWoinst( "woinst=", 5 ) );
	}
}
