package modulo.runner;

import java.util.List;

import modulo.Modulo;
import modulo.woadaptorconfig.model.App;
import modulo.woadaptorconfig.model.Instance;
import ng.appserver.NGActionResults;
import ng.appserver.NGApplication;
import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

public class MDApplicationsPage extends NGComponent {

	public App currentApplication;
	public Instance currentInstance;

	public MDApplicationsPage( NGContext context ) {
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
				.sorted( java.util.Comparator.comparing( app -> app.name().toLowerCase() ) )
				.toList();
	}

	/**
	 * @return True if the instance currently being rendered is refusing new sessions
	 */
	public boolean currentInstanceRefusing() {
		return modulo().instanceRefusing( currentApplication.name(), currentInstance.id() );
	}

	/**
	 * @return True if the instance currently being rendered is in its dead cool-down
	 */
	public boolean currentInstanceDead() {
		return modulo().instanceDead( currentApplication.name(), currentInstance.id() );
	}

	/**
	 * @return True if the instance is one of wotaskd's unregistered entries —
	 *         a process alive without configuration, reported as a negative
	 *         instance number (-port)
	 */
	public boolean currentInstanceUnregistered() {
		return currentInstance.id() < 0;
	}

	public boolean currentInstanceHealthy() {
		return !currentInstanceDead() && !currentInstanceRefusing() && !currentInstanceUnregistered();
	}

	public String currentInstanceCount() {
		final int count = currentApplication.instances().size();
		return count == 1 ? "1 instance" : count + " instances";
	}
}