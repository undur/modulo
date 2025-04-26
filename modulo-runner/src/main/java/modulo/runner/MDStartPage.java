package modulo.runner;

import java.util.List;

import modulo.Modulo;
import modulo.woadaptorconfig.model.App;
import modulo.woadaptorconfig.model.Instance;
import ng.appserver.NGActionResults;
import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

public class MDStartPage extends NGComponent {

	public App currentApplication;
	public Instance currentInstance;

	public MDStartPage( NGContext context ) {
		super( context );
	}

	public NGActionResults reloadAdaptorConfig() {
		Modulo.reloadAdaptorConfig();
		return null;
	}

	public List<App> applications() {
		return Modulo
				.adaptorConfig()
				.applications()
				.values()
				.stream()
				.toList();
	}
}