package modulo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import modulo.woadaptorconfig.model.App;
import modulo.woadaptorconfig.model.Instance;

/**
 * Picks which instance of an application serves a request, emulating
 * mod_WebObjects' behavior:
 *
 * <ul>
 * <li>A request pinned to an instance (via a {@code .woa/N/} URL segment or
 * the {@code woinst} cookie) goes to that instance — WO sessions are
 * instance-local, so stickiness is correctness, not optimization.</li>
 * <li>An unpinned request (new visitor, sessionless direct action) is
 * balanced round-robin across the application's instances.</li>
 * <li>A pinned instance that's no longer registered falls back to
 * round-robin — the session is gone either way; a fresh session on a live
 * instance beats an error page. The fallback is flagged so the caller can
 * log/register the event.</li>
 * </ul>
 */
class InstanceSelector {

	private final Map<String, AtomicInteger> _roundRobinCounters = new ConcurrentHashMap<>();

	/** "app:instanceId" → the moment the instance's refusing-new-sessions state expires. */
	private final Map<String, Instant> _refusingUntil = new ConcurrentHashMap<>();

	/**
	 * @param instance The chosen instance
	 * @param fellBack True when a pinned instance was requested but is no
	 *            longer registered, and round-robin chose a replacement
	 */
	record Selection( Instance instance, boolean fellBack ) {}

	/**
	 * @param application The app to select an instance of; must have at least one instance
	 * @param requestedInstanceId The instance the request is pinned to, or null for unpinned
	 */
	Selection select( final App application, final Integer requestedInstanceId ) {
		final List<Instance> instances = application.instances();

		if( requestedInstanceId != null ) {
			for( final Instance instance : instances ) {
				if( instance.id() == requestedInstanceId ) {
					return new Selection( instance, false );
				}
			}
			return new Selection( roundRobin( application ), true );
		}

		return new Selection( roundRobin( application ), false );
	}

	private Instance roundRobin( final App application ) {
		// New traffic avoids instances currently refusing new sessions
		// (graceful bounce: they keep serving their pinned sessions until
		// those drain). If every instance is refusing, serve anyway — a
		// reluctant instance beats an error page.
		List<Instance> candidates = application.instances().stream()
				.filter( instance -> !isRefusing( application.name(), instance.id() ) )
				.toList();

		if( candidates.isEmpty() ) {
			candidates = application.instances();
		}

		final int counter = _roundRobinCounters.computeIfAbsent( application.name(), name -> new AtomicInteger() ).getAndIncrement();
		return candidates.get( Math.floorMod( counter, candidates.size() ) );
	}

	/**
	 * Marks an instance as refusing new sessions for [validity] — invoked when
	 * an upstream response carries the refusal header.
	 *
	 * @return True when this is a transition (the instance wasn't already
	 *         marked refusing) — so the caller can register an event once
	 *         rather than per response
	 */
	boolean markRefusing( final String applicationName, final int instanceId, final Duration validity ) {
		final Instant previous = _refusingUntil.put( key( applicationName, instanceId ), Instant.now().plus( validity ) );
		return previous == null || previous.isBefore( Instant.now() );
	}

	boolean isRefusing( final String applicationName, final int instanceId ) {
		final Instant until = _refusingUntil.get( key( applicationName, instanceId ) );
		return until != null && until.isAfter( Instant.now() );
	}

	private static String key( final String applicationName, final int instanceId ) {
		return applicationName + ":" + instanceId;
	}
}
