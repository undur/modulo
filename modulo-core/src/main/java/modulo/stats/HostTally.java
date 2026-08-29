package modulo.stats;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts requests per hostname for hosts NOT in the sites config. This is
 * where "unknown host" traffic becomes legible without flooding the event
 * stream: scanner spam piles up under its few hostnames, while a forgotten
 * alias — a domain that *should* have been configured — shows up as a
 * recognizable name an operator will spot at a glance.
 */
public class HostTally {

	/** Distinct-host cap so Host-header randomization can't grow the map without bound. */
	static final int MAX_DISTINCT_HOSTS = 500;

	private static final String OVERFLOW = "(other)";
	private static final String NO_HOST = "(no host)";

	private final Map<String, LongAdder> _counts = new ConcurrentHashMap<>();

	public void record( final String host ) {
		String key = host == null || host.isBlank() ? NO_HOST : host.toLowerCase( Locale.ROOT );

		if( _counts.size() >= MAX_DISTINCT_HOSTS && !_counts.containsKey( key ) ) {
			key = OVERFLOW;
		}

		_counts.computeIfAbsent( key, k -> new LongAdder() ).increment();
	}

	public record Entry( String host, long count ) {}

	/**
	 * @return The tallied hosts, most-hit first, at most [limit] entries
	 */
	public List<Entry> top( final int limit ) {
		return _counts.entrySet().stream()
				.map( entry -> new Entry( entry.getKey(), entry.getValue().sum() ) )
				.sorted( Comparator.comparingLong( Entry::count ).reversed() )
				.limit( limit )
				.toList();
	}

	public boolean isEmpty() {
		return _counts.isEmpty();
	}
}
