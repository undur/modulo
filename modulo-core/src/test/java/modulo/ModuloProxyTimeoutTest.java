package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import modulo.woadaptorconfig.model.Instance;

public class ModuloProxyTimeoutTest {

	@Test
	void recvTimeoutBecomesTheUpstreamIdleTimeout() {
		assertEquals( Duration.ofSeconds( 90 ), ModuloProxy.upstreamIdleTimeout( new Instance( 1, "h", 2001, false, 60, 90, 5 ) ) );
	}

	@Test
	void sendTimeoutIsTheFallback() {
		assertEquals( Duration.ofSeconds( 60 ), ModuloProxy.upstreamIdleTimeout( new Instance( 1, "h", 2001, false, 60, null, 5 ) ) );
	}

	@Test
	void noPublishedTimeoutMeansJettyDefault() {
		assertNull( ModuloProxy.upstreamIdleTimeout( new Instance( 1, "h", 2001 ) ) );
		// cnctTimeout alone doesn't count — it's deliberately not honored
		assertNull( ModuloProxy.upstreamIdleTimeout( new Instance( 1, "h", 2001, false, null, null, 60 ) ) );
	}
}
