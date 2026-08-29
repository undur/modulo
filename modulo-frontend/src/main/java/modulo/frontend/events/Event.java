package modulo.frontend.events;

import java.time.Instant;

/**
 * One noteworthy occurrence — a proxy failure, a certificate obtained or
 * failed, a config reload. Events are what the admin UI surfaces; the log
 * file gets them too, but events are structured and bounded.
 *
 * Scoping is by optional coordinates: {@code site} (a canonical hostname)
 * and {@code app} (an upstream application name) — null means the event is
 * server-scoped. Instance scope can be added as a third coordinate when
 * multi-instance work makes it meaningful.
 */
public record Event(
		Instant time,
		Severity severity,
		String kind,
		String site,
		String app,
		String message ) {

	public enum Severity {
		INFO, WARN, ERROR
	}
}
