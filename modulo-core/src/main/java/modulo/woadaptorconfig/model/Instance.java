package modulo.woadaptorconfig.model;

/**
 * @param refuseNewSessions The instance is configured (in wotaskd's adaptor
 *            config) to refuse new sessions — distinct from the runtime
 *            refusal state announced via response headers.
 * @param sendTimeout Seconds the adaptor may take to send the request to
 *            the instance, per JavaMonitor's per-instance/app/site setting;
 *            null when wotaskd publishes none
 * @param recvTimeout Seconds the adaptor waits for the instance's response;
 *            null when wotaskd publishes none. What modulo honors as the
 *            upstream idle timeout for requests routed to this instance
 * @param cnctTimeout Seconds the adaptor may take to connect; null when
 *            wotaskd publishes none. Parsed but deliberately not applied —
 *            a connect timeout only ever fires against a dead instance,
 *            where it gates failover
 */
public record Instance( int id, String host, int port, boolean refuseNewSessions, Integer sendTimeout, Integer recvTimeout, Integer cnctTimeout ) {

	public Instance( int id, String host, int port ) {
		this( id, host, port, false );
	}

	public Instance( int id, String host, int port, boolean refuseNewSessions ) {
		this( id, host, port, refuseNewSessions, null, null, null );
	}
}
