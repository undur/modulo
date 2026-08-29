package modulo;

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
		final List<Instance> instances = application.instances();
		final int counter = _roundRobinCounters.computeIfAbsent( application.name(), name -> new AtomicInteger() ).getAndIncrement();
		return instances.get( Math.floorMod( counter, instances.size() ) );
	}
}
