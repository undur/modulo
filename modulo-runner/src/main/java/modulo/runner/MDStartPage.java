package modulo.runner;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import modulo.Modulo;
import modulo.stats.RequestStats;
import ng.appserver.NGApplication;
import ng.appserver.NGContext;
import ng.appserver.templating.NGComponent;

/**
 * The admin start page: a glance at what this modulo is and is doing —
 * basics plus the traffic dashboard (request load by application and
 * response times, last hour). Details live on the other pages.
 */
public class MDStartPage extends NGComponent {

	private static final DateTimeFormatter STARTED_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm 'UTC'" ).withZone( ZoneOffset.UTC );

	public RequestStats.AppTotals currentTotal;

	public MDStartPage( NGContext context ) {
		super( context );
	}

	private Modulo modulo() {
		return ((Application)NGApplication.application()).modulo();
	}

	public String started() {
		final Instant startedAt = Application.startedAt();
		return "%s (up %s)".formatted( STARTED_FORMAT.format( startedAt ), humanDuration( Duration.between( startedAt, Instant.now() ) ) );
	}

	public String proxyPort() {
		return String.valueOf( Config.MODULO_PROXY_PORT );
	}

	public String frontendStatus() {
		if( modulo().sitesConfig() == null ) {
			return "not active — plain reverse proxy only";
		}
		return "active — %d site(s), %d ACME-managed".formatted( modulo().sitesConfig().sites().size(), modulo().sitesConfig().acmeManagedSites().size() );
	}

	public String applicationCount() {
		return "%d app(s) known to wotaskd".formatted( modulo().adaptorConfig().applications().size() );
	}

	/**
	 * @return Per-application totals over the last hour, busiest first —
	 *         the response-time table next to the traffic chart
	 */
	public List<RequestStats.AppTotals> appTotals() {
		return modulo().requestStats().snapshot().totals();
	}

	public boolean hasTraffic() {
		return !appTotals().isEmpty();
	}

	static String humanDuration( final Duration duration ) {
		final long days = duration.toDays();
		if( days > 0 ) {
			return "%dd %dh".formatted( days, duration.toHoursPart() );
		}
		if( duration.toHours() > 0 ) {
			return "%dh %dm".formatted( duration.toHours(), duration.toMinutesPart() );
		}
		if( duration.toMinutes() > 0 ) {
			return "%dm".formatted( duration.toMinutes() );
		}
		return "%ds".formatted( duration.toSeconds() );
	}
}
