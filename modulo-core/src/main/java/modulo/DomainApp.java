package modulo;

import java.util.HashMap;
import java.util.Map;

/**
 * FIXME: Temporary hardcoding of domain-to-app mapping until domains find home in config // Hugi 2026-01-27
 */
public class DomainApp {

	private static final Map<String, String> _domainToAppMap = createMap();

	private static Map<String, String> createMap() {
		final Map<String, String> domainToAppMap = new HashMap<>();

		domainToAppMap.put( "www.byosk.is", "oskgunnlaugs" );
		domainToAppMap.put( "www.oskgunnlaugs.com", "oskgunnlaugs" );

		domainToAppMap.put( "www.undurskriftasofnun.is", "Campaigns" );
		domainToAppMap.put( "www.allraheill.is", "Campagigns" );

		domainToAppMap.put( "www.godurkodi.is", "Rebbi" );
		domainToAppMap.put( "www.rebbi.is", "Rebbi" );
		domainToAppMap.put( "tools.rebbi.is", "Rebbi" );
		domainToAppMap.put( "maven.rebbi.is", "Rebbi" );

		domainToAppMap.put( "www.hugi.io", "Hugi" );
		domainToAppMap.put( "www.lidamot.is", "Lidamot" );
		domainToAppMap.put( "www.fermentedshark.com", "ng-website" );
		domainToAppMap.put( "www.husvordurinn.is", "husvordurinn-ng" );
		domainToAppMap.put( "www.kidwits.com", "KidWits" );
		domainToAppMap.put( "www.whoacommunity.com", "whoacommunity" );
		domainToAppMap.put( "jm.rebbi.is", "JavaMonitor" );
		domainToAppMap.put( "www.gagnasafn.is", "Gagnasafn" );

		return domainToAppMap;
	}

	public static String appForHost( String host ) {
		return _domainToAppMap.get( host );
	}
}