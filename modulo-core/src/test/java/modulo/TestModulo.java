package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jetty.http.HttpURI;
import org.junit.jupiter.api.Test;

public class TestModulo {

	@Test
	final void applicationNameFromURI() {
		final HttpURI uri = HttpURI.build( "/Apps/WebObjects/TestApp.woa/bla/bla" );
		assertEquals( "TestApp", Modulo.applicationNameFromURI( uri, host -> null ) );
	}

	@Test
	final void applicationNameFromDomain() {
		final HttpURI uri = HttpURI.build( "https://www.rebbi.is/some/path" );
		assertEquals( "Rebbi", Modulo.applicationNameFromURI( uri, host -> "www.rebbi.is".equals( host ) ? "Rebbi" : null ) );
	}
}