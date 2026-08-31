package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jetty.http.HttpURI;
import org.junit.jupiter.api.Test;

public class TestModulo {

	@Test
	final void applicationNameFromURI() {
		final HttpURI uri = HttpURI.build( "/Apps/WebObjects/TestApp.woa/bla/bla" );
		final Modulo.RequestTarget target = Modulo.targetFromURI( uri, host -> null, host -> false );
		assertEquals( "TestApp", target.applicationName() );
		assertEquals( null, target.instanceNumber() );
	}

	@Test
	final void applicationNameFromDomain() {
		final HttpURI uri = HttpURI.build( "https://www.rebbi.is/some/path" );
		final Modulo.RequestTarget target = Modulo.targetFromURI( uri, host -> "www.rebbi.is".equals( host ) ? "Rebbi" : null, host -> false );
		assertEquals( "Rebbi", target.applicationName() );
		assertEquals( null, target.instanceNumber() );
	}

	@Test
	final void urlEncodedInstanceNumber() {
		assertEquals( new Modulo.RequestTarget( "TestApp", 2 ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp.woa/2/wo/session" ), host -> null, host -> false ) );
		assertEquals( new Modulo.RequestTarget( "TestApp", 12 ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp.woa/12" ), host -> null, host -> false ) );
		// non-numeric first segment is a path, not an instance
		assertEquals( new Modulo.RequestTarget( "TestApp", null ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp.woa/wo/session" ), host -> null, host -> false ) );
		// bare .woa, no trailing segment
		assertEquals( new Modulo.RequestTarget( "TestApp", null ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp.woa" ), host -> null, host -> false ) );
	}

	@Test
	final void extensionlessApplicationName() {
		assertEquals( new Modulo.RequestTarget( "TestApp", null ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp" ), host -> null, host -> false ) );
		assertEquals( new Modulo.RequestTarget( "TestApp", null ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp/" ), host -> null, host -> false ) );
		assertEquals( new Modulo.RequestTarget( "TestApp", null ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp/bla/bla" ), host -> null, host -> false ) );
	}

	/** A configured-but-appless site is an operational signal; an unknown host is spam noise. */
	@Test
	final void unknownHostVersusConfiguredSiteWithoutApp() {
		final HttpURI uri = HttpURI.build( "https://m.spam.example/" );
		try {
			Modulo.targetFromURI( uri, host -> null, host -> "www.appless.example".equals( host ) );
			throw new AssertionError( "expected ProxyRoutingException" );
		}
		catch( final modulo.error.ProxyRoutingException e ) {
			assertEquals( modulo.error.ErrorCondition.UNKNOWN_HOST, e.condition() );
		}
		try {
			Modulo.targetFromURI( HttpURI.build( "https://www.appless.example/" ), host -> null, host -> "www.appless.example".equals( host ) );
			throw new AssertionError( "expected ProxyRoutingException" );
		}
		catch( final modulo.error.ProxyRoutingException e ) {
			assertEquals( modulo.error.ErrorCondition.NO_APP_FOR_HOST, e.condition() );
		}
	}
}
