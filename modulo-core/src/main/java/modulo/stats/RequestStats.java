package modulo.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lightweight per-application request statistics: request counts and summed
 * response times, bucketed by minute over a sliding one-hour window. Feeds
 * the admin dashboard's traffic chart — the display-side sibling of the
 * event log. Deliberately in-memory and approximate; real metrics export is
 * a separate roadmap item.
 */
public class RequestStats {

	public static final int WINDOW_MINUTES = 60;

	/** One minute's numbers for one app. */
	private static class Cell {
		long count;
		long totalDurationMs;
	}

	/** Ring of minute buckets: epochMinute stamp → per-app cells. */
	private final long[] _bucketMinute = new long[WINDOW_MINUTES];
	@SuppressWarnings( "unchecked" )
	private final Map<String, Cell>[] _buckets = new Map[WINDOW_MINUTES];

	public synchronized void record( final String applicationName, final long durationMs ) {
		final long minute = System.currentTimeMillis() / 60_000L;
		final int index = (int)(minute % WINDOW_MINUTES);

		if( _bucketMinute[index] != minute ) {
			_bucketMinute[index] = minute;
			_buckets[index] = new LinkedHashMap<>();
		}

		final Cell cell = _buckets[index].computeIfAbsent( applicationName, name -> new Cell() );
		cell.count++;
		cell.totalDurationMs += durationMs;
	}

	/** Per-app aggregate over the whole window. */
	public record AppTotals( String applicationName, long requests, long averageResponseMs ) {}

	/** The chart-ready view: minute labels (oldest first) and a count series per app, aligned to the labels. */
	public record Snapshot( List<String> minuteLabels, Map<String, List<Long>> requestSeries, List<AppTotals> totals ) {}

	public synchronized Snapshot snapshot() {
		final long nowMinute = System.currentTimeMillis() / 60_000L;
		final long oldestMinute = nowMinute - WINDOW_MINUTES + 1;

		// Which apps appear anywhere in the window (stable alphabetical order)
		final TreeSet<String> apps = new TreeSet<>();
		for( int i = 0; i < WINDOW_MINUTES; i++ ) {
			if( _buckets[i] != null && _bucketMinute[i] >= oldestMinute ) {
				apps.addAll( _buckets[i].keySet() );
			}
		}

		final List<String> labels = new ArrayList<>();
		final Map<String, List<Long>> series = new LinkedHashMap<>();
		apps.forEach( app -> series.put( app, new ArrayList<>() ) );
		final Map<String, long[]> sums = new LinkedHashMap<>(); // app → [requests, totalMs]

		for( long minute = oldestMinute; minute <= nowMinute; minute++ ) {
			final int index = (int)(minute % WINDOW_MINUTES);
			final Map<String, Cell> bucket = _bucketMinute[index] == minute ? _buckets[index] : null;
			labels.add( java.time.format.DateTimeFormatter.ofPattern( "HH:mm" ).withZone( java.time.ZoneOffset.UTC ).format( java.time.Instant.ofEpochMilli( minute * 60_000L ) ) );

			for( final String app : apps ) {
				final Cell cell = bucket == null ? null : bucket.get( app );
				series.get( app ).add( cell == null ? 0L : cell.count );
				if( cell != null ) {
					final long[] sum = sums.computeIfAbsent( app, a -> new long[2] );
					sum[0] += cell.count;
					sum[1] += cell.totalDurationMs;
				}
			}
		}

		final List<AppTotals> totals = new ArrayList<>();
		for( final Map.Entry<String, long[]> entry : sums.entrySet() ) {
			final long requests = entry.getValue()[0];
			totals.add( new AppTotals( entry.getKey(), requests, requests == 0 ? 0 : entry.getValue()[1] / requests ) );
		}
		totals.sort( ( a, b ) -> Long.compare( b.requests(), a.requests() ) );

		return new Snapshot( labels, series, totals );
	}
}
