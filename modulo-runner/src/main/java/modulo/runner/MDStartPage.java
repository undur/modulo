package modulo.runner;

import java.util.List;

import modulo.Modulo;
import modulo.woadaptorconfig.model.App;
import modulo.woadaptorconfig.model.Instance;
import ng.appserver.NGActionResults;
import ng.appserver.NGApplication;
import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

public class MDStartPage extends NGComponent {

	public App currentApplication;
	public Instance currentInstance;

	public MDStartPage( NGContext context ) {
		super( context );
	}

	private Modulo modulo() {
		return ((Application)NGApplication.application()).modulo();
	}

	public NGActionResults reloadAdaptorConfig() {
		modulo().reloadAdaptorConfig();
		return null;
	}

	public List<App> applications() {
		return modulo()
				.adaptorConfig()
				.applications()
				.values()
				.stream()
				.toList();
	}
}