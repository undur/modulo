package modulo.woadaptorconfig.model;

import java.util.HashMap;
import java.util.Map;

public record AdaptorConfig( Map<String, App> applications ) {

	public AdaptorConfig() {
		this( new HashMap<>() );
	}

	public App application( String applicationName ) {
		return applications.get( applicationName );
	}
}