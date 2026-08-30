package modulo.frontend.events;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A bounded in-memory buffer of recent {@link Event}s — the tee that keeps
 * the last N noteworthy occurrences inspectable through the admin UI without
 * anyone having to grep a log file. Everything recorded here is also
 * expected to be logged normally by the recorder; this is the structured,
 * bounded view, not the system of record.
 */
public class EventLog {

	private final int capacity;
	private final Deque<Event> events = new ArrayDeque<>();

	public EventLog( final int capacity ) {
		this.capacity = capacity;
	}

	public synchronized void add( final Event.Severity severity, final String kind, final String site, final String app, final String message ) {
		events.addLast( new Event( Instant.now(), severity, kind, site, app, message ) );
		while( events.size() > capacity ) {
			events.removeFirst();
		}
	}

	/**
	 * Empties the buffer — an operator drawing a line under handled events.
	 * The underlying log files are untouched; this is view-state only.
	 */
	public synchronized void clear() {
		events.clear();
	}

	/**
	 * @return The buffered events, newest first
	 */
	public synchronized List<Event> recent() {
		final List<Event> list = new ArrayList<>( events );
		Collections.reverse( list );
		return list;
	}
}
