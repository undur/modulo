package modulo.runner;

import modulo.Modulo;
import ng.appserver.NGApplication;
import ng.appserver.NGResponse;
import ng.plugins.Routes;

public class Application extends NGApplication {

	private final Modulo _modulo;

	public static void main( String[] args ) {
		NGApplication.run( args, Application.class );
	}

	public Application() {
		_modulo = new Modulo( Config.MODULO_PROXY_PORT );
		_modulo.start();
	}

	/**
	 * @return Our modulo instance, for checking out configuration, status and statistics
	 */
	public Modulo modulo() {
		return _modulo;
	}

	@Override
	public Routes routes() {
		return Routes
				.create()
				.map( "/WOAdaptorInfo", request -> new NGResponse( _modulo.adaptorConfig().toString(), 200 ) )
				.map( "/", MDStartPage.class );
	}
}