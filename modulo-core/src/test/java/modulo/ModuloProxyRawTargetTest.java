package modulo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ModuloProxyRawTargetTest {

	@Test
	public void charactersThatOnlyNeedEncodingQualify() {
		assertTrue( ModuloProxy.parsesOnceEncoded( "/wa/Action/a|b" ) );
		assertTrue( ModuloProxy.parsesOnceEncoded( "/x?q=a|b" ) );
		assertTrue( ModuloProxy.parsesOnceEncoded( "/x?q={json}&r=a^b" ) );
		assertTrue( ModuloProxy.parsesOnceEncoded( "/x?q=a\\b" ) );
		assertTrue( ModuloProxy.parsesOnceEncoded( "/sæla" ) );
	}

	@Test
	public void malformedEscapesDoNot() {
		assertFalse( ModuloProxy.parsesOnceEncoded( "/x?q=%zz" ) );
		assertFalse( ModuloProxy.parsesOnceEncoded( "/x?q=100%" ) );
		assertFalse( ModuloProxy.parsesOnceEncoded( "/a%2" ) );
		assertFalse( ModuloProxy.parsesOnceEncoded( null ) );
	}
}
