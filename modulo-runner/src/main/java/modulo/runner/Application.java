package modulo.runner;

import modulo.Modulo;
import ng.appserver.NGApplication;
import ng.plugins.Routes;

public class Application extends NGApplication {

	public static void main( String[] args ) {
		NGApplication.run( args, Application.class );
		Modulo.main( args );
	}

	@Override
	public Routes routes() {
		return Routes
				.create()
				.map( "/", MDStartPage.class );
	}
}