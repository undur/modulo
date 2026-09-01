package modulo.frontend;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.RolloverFileOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.frontend.site.Site;

/**
 * Per-site access logging: one directory per Site (keyed by canonical
 * hostname — alias traffic folds into the canonical site's directory) with
 * one file per day inside it, daily rollover with bounded retention, in
 * combined log format with a leading virtual-host field plus request
 * duration:
 *
 * <pre>
 * www.example.com 1.2.3.4 - - [29/Aug/2026:11:22:33 +0000] "GET / HTTP/2" 200 5310 "-" "Mozilla/..." 12ms
 * </pre>
 *
 * The vhost field shows which alias a request actually used, and — in the
 * {@code _unmatched} file, where many hostnames aggregate — which unknown
 * host the traffic was for (a forgotten alias looks very different from
 * scanner spam once you can see the names).
 *
 * Requests for hostnames not in the site map (scanners hitting the bare IP,
 * requests during config gaps) go to {@code _unmatched.log} — visible, not
 * lost, and kept out of real sites' logs.
 */
public class SiteAccessLog implements RequestLog {

	private static final Logger logger = LoggerFactory.getLogger( SiteAccessLog.class );

	private static final DateTimeFormatter CLF_TIME = DateTimeFormatter.ofPattern( "dd/MMM/yyyy:HH:mm:ss Z", Locale.US ).withZone( ZoneOffset.UTC );

	private static final String UNMATCHED = "_unmatched";

	private final Path directory;
	private final int retainDays;
	private final Supplier<Map<String, Site>> sitesByHost;
	private final Map<String, PrintWriter> writers = new ConcurrentHashMap<>();

	public SiteAccessLog( final Path directory, final int retainDays, final Supplier<Map<String, Site>> sitesByHost ) throws IOException {
		this.directory = directory;
		this.retainDays = retainDays;
		this.sitesByHost = sitesByHost;
		Files.createDirectories( directory );
	}

	@Override
	public void log( final Request request, final Response response ) {
		try {
			final String host = Request.getServerName( request );
			final Site site = host == null ? null : sitesByHost.get().get( host.toLowerCase( Locale.ROOT ) );
			final String fileKey = site != null ? site.primaryHostname() : UNMATCHED;

			final long durationMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - request.getBeginNanoTime() );
			final String line = "%s %s - - [%s] \"%s %s %s\" %d %d \"%s\" \"%s\" %dms".formatted(
					host == null ? "-" : host,
					Request.getRemoteAddr( request ),
					CLF_TIME.format( Instant.ofEpochMilli( Request.getTimeStamp( request ) ) ),
					request.getMethod(),
					request.getHttpURI().getPathQuery(),
					request.getConnectionMetaData().getProtocol(),
					response.getStatus(),
					Response.getContentBytesWritten( response ),
					headerOrDash( request, HttpHeader.REFERER ),
					headerOrDash( request, HttpHeader.USER_AGENT ),
					durationMs );

			final PrintWriter writer = writers.computeIfAbsent( fileKey, this::openWriter );
			synchronized( writer ) {
				writer.println( line );
				writer.flush();
			}
		}
		catch( final Exception e ) {
			// Access logging must never take a request down with it
			logger.warn( "Access log write failed: {}", e.toString() );
		}
	}

	private static String headerOrDash( final Request request, final HttpHeader header ) {
		final String value = request.getHeaders().get( header );
		return value == null ? "-" : value;
	}

	private PrintWriter openWriter( final String fileKey ) {
		try {
			// A directory per site, a file per day inside it — tens of sites
			// times months of retention in one flat folder was unbrowsable.
			// yyyy_mm_dd in the pattern makes RolloverFileOutputStream roll
			// daily and prune beyond retainDays, per directory.
			final Path siteDirectory = directory.resolve( fileKey );
			Files.createDirectories( siteDirectory );
			final String pattern = siteDirectory.resolve( "yyyy_mm_dd.log" ).toString();
			return new PrintWriter( new OutputStreamWriter( new RolloverFileOutputStream( pattern, true, retainDays ), StandardCharsets.UTF_8 ) );
		}
		catch( final IOException e ) {
			throw new RuntimeException( "Failed to open access log for " + fileKey, e );
		}
	}

	public void close() {
		writers.values().forEach( PrintWriter::close );
		writers.clear();
	}
}
