package modulo.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Locks the cookie-scrubbing regex behavior used by JettyFrontend's
 * Cookie2ScrubHandler. The pattern lives there as a private static; this
 * test mirrors it. If the production regex ever changes, this test should
 * change with it, and intentionally so.
 */
class Cookie2ScrubTest {

	private static final Pattern PATTERN = Pattern.compile(
			"\\s*;\\s*\\$(?:Path|Domain|Port|Version)\\s*=\\s*\"?[^;\"]*\"?",
			Pattern.CASE_INSENSITIVE );

	private static String scrub( final String in ) {
		return PATTERN.matcher( in ).replaceAll( "" );
	}

	@Test
	void stripsFirefoxStyleDollarMetadata() {
		final String in = "routeid_hugi=hugi_2001;$Path=/;$Domain=hugi.io;woinst=-1;$Path=/;$Domain=hugi.io;wosid=yuUVUlPIbIjrtPtmTZbF7g;$Path=/;$Domain=hugi.io";
		final String expected = "routeid_hugi=hugi_2001;woinst=-1;wosid=yuUVUlPIbIjrtPtmTZbF7g";
		assertEquals( expected, scrub( in ) );
	}

	@Test
	void leavesCleanRfc6265CookiesAlone() {
		final String in = "wosid=lmq6YHdkaflhFo4ETItfWM; woinst=-1; routeid_hugi=hugi_2001";
		assertEquals( in, scrub( in ) );
	}

	@Test
	void stripsQuotedDollarMetadata() {
		final String in = "wosid=abc;$Path=\"/\";$Domain=\"hugi.io\"";
		assertEquals( "wosid=abc", scrub( in ) );
	}

	@Test
	void preservesCookieValuesContainingDollarsInValuePosition() {
		// '$' is allowed in cookie values themselves — only the leading-token form is metadata.
		final String in = "session=abc$def; routeid=x";
		assertEquals( in, scrub( in ) );
	}

	@Test
	void preservesNonMetadataValuesEvenWhenAdjacent() {
		final String in = "wosid=abc;$Path=/;woinst=-1";
		final String out = scrub( in );
		assertTrue( out.contains( "wosid=abc" ), out );
		assertTrue( out.contains( "woinst=-1" ), out );
	}
}
