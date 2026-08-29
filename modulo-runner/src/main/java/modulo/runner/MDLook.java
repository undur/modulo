package modulo.runner;

import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

/**
 * The shared page wrapper for modulo's admin pages: document scaffolding,
 * stylesheet and the navigation header. Page components wrap their content
 * in {@code <wo:MDLook>...</wo:MDLook>}.
 */
public class MDLook extends NGComponent {

	public MDLook( NGContext context ) {
		super( context );
	}
}
