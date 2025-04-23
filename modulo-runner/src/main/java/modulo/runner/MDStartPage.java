package modulo.runner;

import java.util.List;

import modulo.Modulo;
import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

public class MDStartPage extends NGComponent {

	public modulo.AdaptorConfigParser.Application currentApplication;
	public modulo.AdaptorConfigParser.Instance currentInstance;

	public MDStartPage( NGContext context ) {
		super( context );
	}

	public List<modulo.AdaptorConfigParser.Application> applications() {
		return Modulo
				.adaptorConfig()
				.applications()
				.values()
				.stream()
				.toList();
	}
}