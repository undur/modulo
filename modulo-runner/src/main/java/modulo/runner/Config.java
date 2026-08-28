package modulo.runner;

/**
 * Temporary placeholder for configuration variables
 */

public class Config {

	/**
	 * The port that the modulo proxy will run on. Overridable with
	 * -Dmodulo.proxy-port — e.g. 80 for simple LAN deployments where the plain
	 * proxy IS the front-facing server (grant CAP_NET_BIND_SERVICE for that).
	 */
	public static final int MODULO_PROXY_PORT = Integer.getInteger( "modulo.proxy-port", 1400 );
}