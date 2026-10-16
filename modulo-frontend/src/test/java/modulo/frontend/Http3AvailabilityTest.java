package modulo.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class Http3AvailabilityTest {

	/** The shape of quiche's load failure: the useful reason is two causes deep, inside a suppressed exception. */
	@Test
	void reportsTheInnermostReason() {
		final UnsatisfiedLinkError glibc = new UnsatisfiedLinkError( "/tmp/libquiche.so: /lib64/libc.so.6: version `GLIBC_2.18' not found" );
		final UnsatisfiedLinkError notFound = new UnsatisfiedLinkError( "Cannot find quiche native library for architecture linux-x86-64" );
		notFound.initCause( glibc );
		final AssertionError suppressed = new AssertionError( "should not reach here", notFound );
		final IllegalStateException failure = new IllegalStateException( "no quiche binding implementation found" );
		failure.addSuppressed( suppressed );
		final ExceptionInInitializerError thrown = new ExceptionInInitializerError( failure );

		assertEquals( "/tmp/libquiche.so: /lib64/libc.so.6: version `GLIBC_2.18' not found", JettyFrontend.deepestMessage( thrown ) );
	}

	@Test
	void fallsBackToTheExceptionItself() {
		assertEquals( "java.lang.NoClassDefFoundError", JettyFrontend.deepestMessage( new NoClassDefFoundError() ) );
	}
}
