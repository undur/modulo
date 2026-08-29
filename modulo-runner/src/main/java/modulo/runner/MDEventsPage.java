package modulo.runner;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import modulo.frontend.events.Event;
import ng.appserver.NGApplication;
import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

/**
 * The event log: recent noteworthy occurrences (proxy failures, certificates
 * obtained/failed, config reloads), newest first — the "what has been
 * happening?" page.
 */
public class MDEventsPage extends NGComponent {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern( "MMM dd HH:mm:ss" ).withZone( ZoneOffset.UTC );

	public EventRow currentEvent;

	public MDEventsPage( NGContext context ) {
		super( context );
	}

	public record EventRow( String time, String severity, boolean isError, boolean isWarn, String kind, String scope, String message ) {}

	public List<EventRow> events() {
		return ((Application)NGApplication.application()).modulo().events().recent().stream().map( MDEventsPage::toRow ).toList();
	}

	public boolean hasEvents() {
		return !events().isEmpty();
	}

	private static EventRow toRow( final Event event ) {
		final String scope = event.site() != null ? event.site() : (event.app() != null ? event.app() : "server");
		return new EventRow(
				TIME_FORMAT.format( event.time() ),
				event.severity().name(),
				event.severity() == Event.Severity.ERROR,
				event.severity() == Event.Severity.WARN,
				event.kind(),
				scope,
				event.message() == null ? "" : event.message() );
	}
}
