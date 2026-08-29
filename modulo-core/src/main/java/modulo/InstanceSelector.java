package modulo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	 * "app:instanceId" → the moment the instance's dead cool-down (set on
	 * connect failure) expires. Any successful response clears it instantly.
	 */
	private final Map<String, Instant> _deadUntil = new ConcurrentHashMap<>();

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
		return select( application, requestedInstanceId, Set.of() );
	}

	/**
	 * @param excludedInstanceIds Instances already attempted for this request
	 *            (the failover path) — never selected, and a pin to one is
	 *            ignored. Null selection is possible only via
	 *            {@link #selectForRetry}.
	 */
	Selection select( final App application, final Integer requestedInstanceId, final Set<Integer> excludedInstanceIds ) {
		final List<Instance> instances = application.instances();

		if( requestedInstanceId != null && !excludedInstanceIds.contains( requestedInstanceId ) ) {
			for( final Instance instance : instances ) {
				if( instance.id() == requestedInstanceId ) {
					return new Selection( instance, false );
				}
			}
			return new Selection( roundRobin( application, excludedInstanceIds ), true );
		}

		return new Selection( roundRobin( application, excludedInstanceIds ), requestedInstanceId != null );
	}

	/**
	 * Selection for a failover retry: like round-robin but returns null when
	 * every instance has been attempted — the caller's signal to stop
	 * retrying and answer with an error.
	 */
	Instance selectForRetry( final App application, final Set<Integer> attemptedInstanceIds ) {
		final List<Instance> remaining = application.instances().stream()
				.filter( instance -> !attemptedInstanceIds.contains( instance.id() ) )
				.toList();

		if( remaining.isEmpty() ) {
			return null;
		}

		// Prefer live, willing, registered instances among the remaining; settle for any remaining
		final List<Instance> preferred = remaining.stream()
				.filter( instance -> instance.id() >= 0 )
				.filter( instance -> !isDead( application.name(), instance.id() ) && !isRefusing( application.name(), instance.id() ) )
				.toList();

		final List<Instance> candidates = preferred.isEmpty() ? remaining : preferred;
		final int counter = _roundRobinCounters.computeIfAbsent( application.name(), name -> new AtomicInteger() ).getAndIncrement();
		return candidates.get( Math.floorMod( counter, candidates.size() ) );
	}

	private Instance roundRobin( final App application, final Set<Integer> excludedInstanceIds ) {
		// New traffic avoids excluded (already-attempted) instances, dead
		// instances (in their post-connect-failure cool-down) and instances
		// refusing new sessions. Unregistered instances — wotaskd reports
		// processes that are alive but no longer configured with negative
		// instance numbers (-port) — are never balanced to, only reachable
		// by explicit pin (mod_WebObjects' schedulability rule). If the
		// filters leave nothing, serve from the non-excluded registered list
		// anyway — a reluctant instance beats an error page.
		final List<Instance> notExcluded = application.instances().stream()
				.filter( instance -> instance.id() >= 0 )
				.filter( instance -> !excludedInstanceIds.contains( instance.id() ) )
				.toList();

		List<Instance> candidates = notExcluded.stream()
				.filter( instance -> !isDead( application.name(), instance.id() ) )
				.filter( instance -> !isRefusing( application.name(), instance.id() ) )
				.toList();

		if( candidates.isEmpty() ) {
			candidates = notExcluded.isEmpty() ? application.instances() : notExcluded;
		}

		final int counter = _roundRobinCounters.computeIfAbsent( application.name(), name -> new AtomicInteger() ).getAndIncrement();
		return candidates.get( Math.floorMod( counter, candidates.size() ) );
	}

	/**
	 * Marks an instance dead (connect failure) for [coolDown] — round-robin
	 * avoids it until the cool-down expires or a successful response clears
	 * it.
	 *
	 * @return True on transition (wasn't already marked dead)
	 */
	boolean markDead( final String applicationName, final int instanceId, final Duration coolDown ) {
		final Instant previous = _deadUntil.put( key( applicationName, instanceId ), Instant.now().plus( coolDown ) );
		return previous == null || previous.isBefore( Instant.now() );
	}

	/**
	 * Clears an instance's dead state — any successful response is proof of life.
	 *
	 * @return True on transition (was marked dead)
	 */
	boolean clearDead( final String applicationName, final int instanceId ) {
		final Instant previous = _deadUntil.remove( key( applicationName, instanceId ) );
		return previous != null && previous.isAfter( Instant.now() );
	}

	boolean isDead( final String applicationName, final int instanceId ) {
		final Instant until = _deadUntil.get( key( applicationName, instanceId ) );
		return until != null && until.isAfter( Instant.now() );
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

	/**
	 * Clears an instance's refusing state — invoked when an upstream response
	 * arrives without a refusal announcement.
	 *
	 * @return True when this is a transition (the instance was marked refusing)
	 */
	boolean clearRefusing( final String applicationName, final int instanceId ) {
		final Instant previous = _refusingUntil.remove( key( applicationName, instanceId ) );
		return previous != null && previous.isAfter( Instant.now() );
	}

	boolean isRefusing( final String applicationName, final int instanceId ) {
		final Instant until = _refusingUntil.get( key( applicationName, instanceId ) );
		return until != null && until.isAfter( Instant.now() );
	}

	private static String key( final String applicationName, final int instanceId ) {
		return applicationName + ":" + instanceId;
	}
}
