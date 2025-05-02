package modulo.runner;

import modulo.Modulo;
import ng.appserver.NGApplication;
import ng.plugins.Routes;

public class Application extends NGApplication {

	private final Modulo _modulo;

	public static void main( String[] args ) {
		NGApplication.run( args, Application.class );
	}

	public Application() {
		_modulo = new Modulo( 1400 );
		_modulo.start();
	}

	/**
	 * @return Our modulo instance, for checking out configuration and such fin
	 */
	public Modulo modulo() {
		return _modulo;
	}

	@Override
	public Routes routes() {
		return Routes
				.create()
				.map( "/", MDStartPage.class );
	}
}