package modulo;

import java.util.HashMap;
import java.util.Map;

/**
 * Hostname → app mappings supplied via system properties:
 * {@code -Dmodulo.domain-app.<host>=<app>}.
 *
 * This is the routing source for deployments that don't use the native
 * sites config — i.e. modulo running as a plain reverse proxy with no
 * front-end (where clean-URL requests are routed by Host alone). When the
 * native sites config is active, routing comes from it and this class is
 * not consulted.
 *
 * The hardcoded production map that used to live here was retired
 * 2026-08-29, when the last deployment using it moved to the native sites
 * config. This property hook retires too once plain-proxy deployments can
 * declare routing in config — see the roadmap's single-service iteration.
 */
public class DomainApp {

	private static final Map<String, String> _domainToAppMap = createMap();

	private static Map<String, String> createMap() {
		final Map<String, String> domainToAppMap = new HashMap<>();

		System.getProperties()
				.stringPropertyNames()
				.stream()
				.filter( name -> name.startsWith( "modulo.domain-app." ) )
				.forEach( name -> domainToAppMap.put( name.substring( "modulo.domain-app.".length() ).toLowerCase(), System.getProperty( name ) ) );

		return domainToAppMap;
	}

	public static String appForHost( String host ) {
		return _domainToAppMap.get( host );
	}
}
