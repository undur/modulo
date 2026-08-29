package modulo.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import modulo.rewrite.RewriteRule.Redirect;
import modulo.rewrite.RewriteRule.Redirected;
import modulo.rewrite.RewriteRule.Result;
import modulo.rewrite.RewriteRule.Rewritten;

public class RewriteRuleTest {

	private static RewriteRule rule( final String match, final String to ) {
		return new RewriteRule( Pattern.compile( match ), to, Redirect.NONE, false, false );
	}

	@Test
	public void exactPathRewrite() {
		final Result result = rule( "^/$", "/Apps/WebObjects/Strimillinn.woa/wa/default" ).apply( "/", null );
		assertEquals( new Rewritten( "/Apps/WebObjects/Strimillinn.woa/wa/default", null ), result );
	}

	@Test
	public void nonMatchingPatternReturnsNull() {
		assertNull( rule( "^/$", "/target" ).apply( "/something", null ) );
	}

	@Test
	public void capturesSubstituteIntoQuery() {
		final Result result = rule( "^/app/([^/]+)/receipts/([^/]+)$", "/Apps/WebObjects/Strimillinn.woa/wa/AppAction/receipt?token=$1&id=$2" )
				.apply( "/app/abc123/receipts/42", null );
		assertEquals( new Rewritten( "/Apps/WebObjects/Strimillinn.woa/wa/AppAction/receipt", "token=abc123&id=42" ), result );
	}

	@Test
	public void targetWithoutQueryKeepsOriginalQuery() {
		final Result result = rule( "^/page/(.*)$", "/Apps/WebObjects/SW.woa/wa/dp" ).apply( "/page/whatever", "lang=is" );
		assertEquals( new Rewritten( "/Apps/WebObjects/SW.woa/wa/dp", "lang=is" ), result );
	}

	@Test
	public void targetWithQueryReplacesOriginalQuery() {
		final Result result = rule( "^/page/(.*)$", "/Apps/WebObjects/SW.woa/wa/dp?name=$1" ).apply( "/page/frontpage", "lang=is" );
		assertEquals( new Rewritten( "/Apps/WebObjects/SW.woa/wa/dp", "name=frontpage" ), result );
	}

	@Test
	public void appendQueryMergesOriginalAfterTargets() {
		final RewriteRule qsa = new RewriteRule( Pattern.compile( "^(.*)$" ), "/Apps/WebObjects/ASI.woa/wa/RouteAction/handler?url=$1", Redirect.NONE, true, false );
		assertEquals( new Rewritten( "/Apps/WebObjects/ASI.woa/wa/RouteAction/handler", "url=/members/list&page=2" ), qsa.apply( "/members/list", "page=2" ) );
	}

	@Test
	public void encodeCapturesUrlEncodesSubstitutedValues() {
		final RewriteRule b = new RewriteRule( Pattern.compile( "^/s/(.*)$" ), "/Apps/WebObjects/Strimillinn.woa/wa/search?s=$1", Redirect.NONE, false, true );
		assertEquals( new Rewritten( "/Apps/WebObjects/Strimillinn.woa/wa/search", "s=kaffi+%26+te" ), b.apply( "/s/kaffi & te", null ) );
	}

	@Test
	public void redirectRuleAnswersWithLocation() {
		final RewriteRule permanent = new RewriteRule( Pattern.compile( "^/policy$" ), "/privacy", Redirect.PERMANENT, false, false );
		assertEquals( new Redirected( "/privacy", true ), permanent.apply( "/policy", null ) );

		final RewriteRule temporary = new RewriteRule( Pattern.compile( "^/ndwc2016$" ), "http://www.ndwc.is/", Redirect.TEMPORARY, false, false );
		assertEquals( new Redirected( "http://www.ndwc.is/", false ), temporary.apply( "/ndwc2016", null ) );
	}

	@Test
	public void redirectKeepsOriginalQuery() {
		final RewriteRule redirect = new RewriteRule( Pattern.compile( "^/old$" ), "/new", Redirect.PERMANENT, false, false );
		assertEquals( new Redirected( "/new?a=1", true ), redirect.apply( "/old", "a=1" ) );
	}

	@Test
	public void firstMatchWins() {
		final List<RewriteRule> rules = List.of(
				rule( "^/page/special$", "/Apps/WebObjects/SW.woa/wa/special" ),
				rule( "^/page/(.*)$", "/Apps/WebObjects/SW.woa/wa/dp?name=$1" ),
				rule( "^(.*)$", "/Apps/WebObjects/SW.woa/wa/catchall?url=$1" ) );

		assertEquals( new Rewritten( "/Apps/WebObjects/SW.woa/wa/special", null ), RewriteRule.firstMatch( rules, "/page/special", null ) );
		assertEquals( new Rewritten( "/Apps/WebObjects/SW.woa/wa/dp", "name=other" ), RewriteRule.firstMatch( rules, "/page/other", null ) );
		assertEquals( new Rewritten( "/Apps/WebObjects/SW.woa/wa/catchall", "url=/anything" ), RewriteRule.firstMatch( rules, "/anything", null ) );
	}

	@Test
	public void noRuleMatchingReturnsNull() {
		assertNull( RewriteRule.firstMatch( List.of( rule( "^/only$", "/target" ) ), "/other", null ) );
	}

	@Test
	public void prefixRewriteWithCapture() {
		// SW's classic-WO URL compatibility rule
		final Result result = rule( "^/cgi-bin/WebObjects/(.*)$", "/Apps/WebObjects/$1" ).apply( "/cgi-bin/WebObjects/SW.woa/wa/dp", "id=5" );
		assertEquals( new Rewritten( "/Apps/WebObjects/SW.woa/wa/dp", "id=5" ), result );
	}

	@Test
	public void dollarDollarEscapesLiteralDollar() {
		assertEquals( new Rewritten( "/price/$99", null ), rule( "^/deal$", "/price/$$99" ).apply( "/deal", null ) );
	}

	@Test
	public void highestReferencedGroup() {
		assertEquals( 2, rule( "^/a/(.*)/b/(.*)$", "/x?p=$1&q=$2" ).highestReferencedGroup() );
		assertEquals( 0, rule( "^/a$", "/x" ).highestReferencedGroup() );
	}
}
