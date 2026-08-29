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
	void refusingInstanceIsSkippedByRoundRobin() {
		final InstanceSelector selector = new InstanceSelector();
		assertTrue( selector.markRefusing( APP.name(), 2, java.time.Duration.ofMinutes( 1 ) ) );
		assertTrue( selector.isRefusing( APP.name(), 2 ) );
		// re-marking while already refusing is not a transition
		assertFalse( selector.markRefusing( APP.name(), 2, java.time.Duration.ofMinutes( 1 ) ) );

		final Set<Instance> picked = IntStream.range( 0, 10 ).mapToObj( i -> selector.select( APP, null ).instance() ).collect( Collectors.toSet() );
		assertEquals( Set.of( ONE, THREE ), picked );
	}

	@Test
	void pinnedRequestsStillReachRefusingInstance() {
		final InstanceSelector selector = new InstanceSelector();
		selector.markRefusing( APP.name(), 2, java.time.Duration.ofMinutes( 1 ) );
		assertEquals( TWO, selector.select( APP, 2 ).instance() );
	}

	@Test
	void allInstancesRefusingStillServes() {
		final InstanceSelector selector = new InstanceSelector();
		for( final Instance instance : APP.instances() ) {
			selector.markRefusing( APP.name(), instance.id(), java.time.Duration.ofMinutes( 1 ) );
		}
		assertTrue( APP.instances().contains( selector.select( APP, null ).instance() ) );
	}

	@Test
	void clearedRefusalRejoinsRotationAndFlags() {
		final InstanceSelector selector = new InstanceSelector();
		selector.markRefusing( APP.name(), 2, java.time.Duration.ofMinutes( 5 ) );
		assertTrue( selector.clearRefusing( APP.name(), 2 ) );
		assertFalse( selector.isRefusing( APP.name(), 2 ) );
		// clearing an unmarked instance is not a transition
		assertFalse( selector.clearRefusing( APP.name(), 2 ) );
	}

	@Test
	void refusalExpires() {
		final InstanceSelector selector = new InstanceSelector();
		selector.markRefusing( APP.name(), 2, java.time.Duration.ZERO );
		assertFalse( selector.isRefusing( APP.name(), 2 ) );
		// and expired refusal counts as a transition when re-marked
		assertTrue( selector.markRefusing( APP.name(), 2, java.time.Duration.ofMinutes( 1 ) ) );
	}

	@Test
	void deadInstanceIsSkippedUntilCleared() {
		final InstanceSelector selector = new InstanceSelector();
		assertTrue( selector.markDead( APP.name(), 1, java.time.Duration.ofMinutes( 1 ) ) );

		final Set<Instance> picked = IntStream.range( 0, 8 ).mapToObj( i -> selector.select( APP, null ).instance() ).collect( Collectors.toSet() );
		assertEquals( Set.of( TWO, THREE ), picked );

		assertTrue( selector.clearDead( APP.name(), 1 ) );
		final Set<Instance> after = IntStream.range( 0, 9 ).mapToObj( i -> selector.select( APP, null ).instance() ).collect( Collectors.toSet() );
		assertEquals( Set.of( ONE, TWO, THREE ), after );
	}

	@Test
	void retrySelectionExcludesAttemptedAndExhausts() {
		final InstanceSelector selector = new InstanceSelector();
		final java.util.Set<Integer> attempted = new java.util.HashSet<>( List.of( 1 ) );

		final Instance second = selector.selectForRetry( APP, attempted );
		assertTrue( Set.of( TWO, THREE ).contains( second ) );
		attempted.add( second.id() );

		final Instance third = selector.selectForRetry( APP, attempted );
		assertTrue( Set.of( TWO, THREE ).contains( third ) );
		assertFalse( third.equals( second ) );
		attempted.add( third.id() );

		assertEquals( null, selector.selectForRetry( APP, attempted ) );
	}

	@Test
	void retryPrefersLiveWillingInstances() {
		final InstanceSelector selector = new InstanceSelector();
		selector.markDead( APP.name(), 2, java.time.Duration.ofMinutes( 1 ) );

		// 1 attempted, 2 dead → 3 is the only live remaining candidate
		for( int i = 0; i < 4; i++ ) {
			assertEquals( THREE, selector.selectForRetry( APP, Set.of( 1 ) ) );
		}
		// but with 1 and 3 attempted, dead 2 is still better than nothing
		assertEquals( TWO, selector.selectForRetry( APP, Set.of( 1, 3 ) ) );
	}

	@Test
	void roundRobinCountersAreIndependentPerApp() {
		final App other = new App( "Other", List.of( ONE, TWO ) );
		final InstanceSelector selector = new InstanceSelector();
		selector.select( APP, null ); // advances TestApp's counter only
		assertEquals( ONE, selector.select( other, null ).instance() ); // Other starts fresh
	}
}
