package modulo.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The restart-required part of the root config file — everything outside
 * {@code [[sites]]}/{@code [acme]}/{@code include}: the {@code [frontend]},
 * {@code [admin]} and {@code [wotaskd]} tables. Sites and ACME hot-reload
 * via {@code POST /reload}; these values are read once at startup, and a
 * reload that changes them answers with an explicit "restart required"
 * notice instead of silently ignoring the edit.
 *
 * All components are nullable — absent tables/keys fall back to defaults
 * (ports 80/443) or to legacy {@code -D} system properties (wotaskd).
 */
public record BootstrapConfig(
		Integer httpPort,
		Integer httpsPort,
		Boolean http3,
		String accessLogDir,
		String acmeWebroot,
		String adminPassword,
		String wotaskdHost,
		Integer wotaskdPort,
		String wotaskdPassword ) {

	/**
	 * @return The names of the settings that differ between this (running)
	 *         config and [other] (freshly parsed) — the reload endpoint's
	 *         "changed but needs a restart" list. Empty = nothing to warn about.
	 */
	public List<String> changedSettings( final BootstrapConfig other ) {
		final List<String> changed = new ArrayList<>();
		addIfChanged( changed, "frontend.httpPort", httpPort, other.httpPort() );
		addIfChanged( changed, "frontend.httpsPort", httpsPort, other.httpsPort() );
		addIfChanged( changed, "frontend.http3", http3, other.http3() );
		addIfChanged( changed, "frontend.accessLogDir", accessLogDir, other.accessLogDir() );
		addIfChanged( changed, "frontend.acmeWebroot", acmeWebroot, other.acmeWebroot() );
		addIfChanged( changed, "admin.password", adminPassword, other.adminPassword() );
		addIfChanged( changed, "wotaskd.host", wotaskdHost, other.wotaskdHost() );
		addIfChanged( changed, "wotaskd.port", wotaskdPort, other.wotaskdPort() );
		addIfChanged( changed, "wotaskd.password", wotaskdPassword, other.wotaskdPassword() );
		return changed;
	}

	private static void addIfChanged( final List<String> changed, final String name, final Object current, final Object incoming ) {
		if( !Objects.equals( current, incoming ) ) {
			changed.add( name );
		}
	}
}
