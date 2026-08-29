package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import modulo.woadaptorconfig.model.App;
import modulo.woadaptorconfig.model.Instance;

class InstanceSelectorTest {

	private static final Instance ONE = new Instance( 1, "host-a", 2001 );
	private static final Instance TWO = new Instance( 2, "host-a", 2002 );
	private static final Instance THREE = new Instance( 3, "host-b", 2001 );
	private static final App APP = new App( "TestApp", List.of( ONE, TWO, THREE ) );

	@Test
	void pinnedRequestGoesToItsInstance() {
		final InstanceSelector selector = new InstanceSelector();
		for( int i = 0; i < 5; i++ ) {
			final InstanceSelector.Selection selection = selector.select( APP, 2 );
			assertEquals( TWO, selection.instance() );
			assertFalse( selection.fellBack() );
		}
	}

	@Test
	void unpinnedRequestsRoundRobinAcrossAllInstances() {
		final InstanceSelector selector = new InstanceSelector();
		final List<Instance> picked = IntStream.range( 0, 6 ).mapToObj( i -> selector.select( APP, null ).instance() ).toList();
		// Two full cycles: every instance hit exactly twice
		for( final Instance instance : APP.instances() ) {
			assertEquals( 2, picked.stream().filter( instance::equals ).count() );
		}
	}

	@Test
	void missingPinnedInstanceFallsBackAndFlags() {
		final InstanceSelector selector = new InstanceSelector();
		final InstanceSelector.Selection selection = selector.select( APP, 42 );
		assertTrue( selection.fellBack() );
		assertTrue( APP.instances().contains( selection.instance() ) );
	}

	@Test
	void singleInstanceAppAlwaysGetsThatInstance() {
		final App single = new App( "Single", List.of( ONE ) );
		final InstanceSelector selector = new InstanceSelector();
		final Set<Instance> picked = IntStream.range( 0, 4 ).mapToObj( i -> selector.select( single, null ).instance() ).collect( Collectors.toSet() );
		assertEquals( Set.of( ONE ), picked );
	}

	@Test
	void roundRobinCountersAreIndependentPerApp() {
		final App other = new App( "Other", List.of( ONE, TWO ) );
		final InstanceSelector selector = new InstanceSelector();
		selector.select( APP, null ); // advances TestApp's counter only
		assertEquals( ONE, selector.select( other, null ).instance() ); // Other starts fresh
	}
}
