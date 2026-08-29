package modulo.woadaptorconfig.model;

/**
 * @param refuseNewSessions The instance is configured (in wotaskd's adaptor
 *            config) to refuse new sessions — distinct from the runtime
 *            refusal state announced via response headers.
 */
public record Instance( int id, String host, int port, boolean refuseNewSessions ) {

	public Instance( int id, String host, int port ) {
		this( id, host, port, false );
	}
}
