package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jetty.http.HttpURI;
import org.junit.jupiter.api.Test;

public class TestModulo {

	@Test
	final void applicationNameFromURI() {
		final HttpURI uri = HttpURI.build( "/Apps/WebObjects/TestApp.woa/bla/bla" );
		final Modulo.RequestTarget target = Modulo.targetFromURI( uri, host -> null );
		assertEquals( "TestApp", target.applicationName() );
		assertEquals( null, target.instanceNumber() );
	}

	@Test
	final void applicationNameFromDomain() {
		final HttpURI uri = HttpURI.build( "https://www.rebbi.is/some/path" );
		final Modulo.RequestTarget target = Modulo.targetFromURI( uri, host -> "www.rebbi.is".equals( host ) ? "Rebbi" : null );
		assertEquals( "Rebbi", target.applicationName() );
		assertEquals( null, target.instanceNumber() );
	}

	@Test
	final void urlEncodedInstanceNumber() {
		assertEquals( new Modulo.RequestTarget( "TestApp", 2 ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp.woa/2/wo/session" ), host -> null ) );
		assertEquals( new Modulo.RequestTarget( "TestApp", 12 ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp.woa/12" ), host -> null ) );
		// non-numeric first segment is a path, not an instance
		assertEquals( new Modulo.RequestTarget( "TestApp", null ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp.woa/wo/session" ), host -> null ) );
		// bare .woa, no trailing segment
		assertEquals( new Modulo.RequestTarget( "TestApp", null ), Modulo.targetFromURI( HttpURI.build( "/Apps/WebObjects/TestApp.woa" ), host -> null ) );
	}
}