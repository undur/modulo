package modulo.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

public class HostTallyTest {

	@Test
	public void countsAndOrdersByFrequency() {
		final HostTally tally = new HostTally();
		tally.record( "spam.example.com" );
		tally.record( "spam.example.com" );
		tally.record( "SPAM.example.com" ); // case-folds
		tally.record( "forgotten-alias.example.com" );

		final List<HostTally.Entry> top = tally.top( 10 );
		assertEquals( 2, top.size() );
		assertEquals( new HostTally.Entry( "spam.example.com", 3 ), top.get( 0 ) );
		assertEquals( new HostTally.Entry( "forgotten-alias.example.com", 1 ), top.get( 1 ) );
	}

	@Test
	public void missingHostGetsPlaceholder() {
		final HostTally tally = new HostTally();
		tally.record( null );
		tally.record( " " );
		assertEquals( new HostTally.Entry( "(no host)", 2 ), tally.top( 1 ).get( 0 ) );
	}

	@Test
	public void distinctHostsAreCapped() {
		final HostTally tally = new HostTally();

		for( int i = 0; i < HostTally.MAX_DISTINCT_HOSTS + 50; i++ ) {
			tally.record( "host-" + i + ".example.com" );
		}

		final List<HostTally.Entry> all = tally.top( Integer.MAX_VALUE );
		assertTrue( all.size() <= HostTally.MAX_DISTINCT_HOSTS + 1 ); // +1 for the (other) bucket
		assertEquals( 50, all.stream().filter( e -> e.host().equals( "(other)" ) ).findFirst().orElseThrow().count() );
	}
}
