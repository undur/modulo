package modulo;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WOA compatibility: serves classic WebObjects WebServerResources for sites
 * that declare a {@code woa} — the path to the app's .woa bundle (or a
 * split-install directory of the same name) on modulo's own disk.
 *
 * Classic WO apps emit resource URLs under {@code /WebObjects/<App>.woa/…}
 * and rely on the web server to serve them from disk; deployment-mode WO has
 * no request handler for named resources (only Wonder apps using
 * ERXResourceRequestHandler self-serve). For opted-in sites this handler owns
 * that URL space, mapping it onto exactly two subtrees of the bundle —
 * {@code WebServerResources/} and {@code Frameworks/<any>.framework/WebServerResources/}
 * — and nothing else. Notably never {@code Resources/}: that privacy boundary
 * is what the classic split install enforced physically, kept here by path
 * rule. Anything inside the owned URL space that fails the rules answers 404
 * rather than falling through to the app.
 *
 * Deliberately self-contained and easy to remove: this file, the {@code woa}
 * site config field, and one wiring line per pipeline in {@link Modulo} are
 * the entire feature.
 */
public class WebServerResourcesHandler extends Handler.Wrapper {

	private static final Logger logger = LoggerFactory.getLogger( WebServerResourcesHandler.class );

	private static final String URL_PREFIX = "/WebObjects/";

	/** Classic resource URLs carry no version fingerprint, so cache moderately */
	private static final String CACHE_CONTROL = "public, max-age=3600";

	/** Deliberately our own little map — this feature avoids reaching into the rest of the stack */
	private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
			Map.entry( "css", "text/css" ),
			Map.entry( "js", "text/javascript" ),
			Map.entry( "map", "application/json" ),
			Map.entry( "json", "application/json" ),
			Map.entry( "png", "image/png" ),
			Map.entry( "gif", "image/gif" ),
			Map.entry( "jpg", "image/jpeg" ),
			Map.entry( "jpeg", "image/jpeg" ),
			Map.entry( "svg", "image/svg+xml" ),
			Map.entry( "ico", "image/x-icon" ),
			Map.entry( "woff", "font/woff" ),
			Map.entry( "woff2", "font/woff2" ),
			Map.entry( "ttf", "font/ttf" ),
			Map.entry( "otf", "font/otf" ),
			Map.entry( "eot", "application/vnd.ms-fontobject" ),
			Map.entry( "html", "text/html" ),
			Map.entry( "txt", "text/plain" ) );

	/**
	 * hostname (lowercase) → the site's woa path. Invoked per request, so a
	 * function reading live config keeps the handler hot-reload-aware.
	 */
	private final Function<String, Path> _woaForHost;

	public WebServerResourcesHandler( final Function<String, Path> woaForHost, final Handler next ) {
		super( next );
		_woaForHost = woaForHost;
	}

	@Override
	public boolean handle( final Request request, final Response response, final Callback callback ) throws Exception {

		final String path = request.getHttpURI().getCanonicalPath();

		if( path == null || !path.startsWith( URL_PREFIX ) ) {
			return super.handle( request, response, callback );
		}

		final String host = Request.getServerName( request );
		final Path woa = host == null ? null : _woaForHost.apply( host.toLowerCase( Locale.ROOT ) );

		if( woa == null ) {
			return super.handle( request, response, callback );
		}

		// The URL's first segment must name the configured bundle — other
		// /WebObjects/ URLs aren't ours and proceed to normal routing
		final String afterPrefix = path.substring( URL_PREFIX.length() );

		if( !afterPrefix.startsWith( woa.getFileName().toString() + "/" ) ) {
			return super.handle( request, response, callback );
		}

		// From here on the request is ours: every failure is a response, never fall-through

		final String method = request.getMethod();

		if( !"GET".equals( method ) && !"HEAD".equals( method ) ) {
			Response.writeError( request, response, callback, HttpStatus.METHOD_NOT_ALLOWED_405 );
			return true;
		}

		final Path file = resolveResource( woa, afterPrefix.substring( woa.getFileName().toString().length() + 1 ) );

		if( file == null ) {
			Response.writeError( request, response, callback, HttpStatus.NOT_FOUND_404 );
			return true;
		}

		final long lastModified = Files.getLastModifiedTime( file ).toMillis();
		final long size = Files.size( file );
		final String etag = "\"%x-%x\"".formatted( size, lastModified );

		final String ifNoneMatch = request.getHeaders().get( HttpHeader.IF_NONE_MATCH );
		final long ifModifiedSince = request.getHeaders().getDateField( HttpHeader.IF_MODIFIED_SINCE );

		if( etag.equals( ifNoneMatch ) || (ifNoneMatch == null && ifModifiedSince != -1 && lastModified / 1000 <= ifModifiedSince / 1000) ) {
			response.setStatus( HttpStatus.NOT_MODIFIED_304 );
			response.getHeaders().put( HttpHeader.ETAG, etag );
			response.write( true, null, callback );
			return true;
		}

		final String extension = file.getFileName().toString().toLowerCase( Locale.ROOT );
		final String contentType = CONTENT_TYPES.get( extension.substring( extension.lastIndexOf( '.' ) + 1 ) );

		response.setStatus( HttpStatus.OK_200 );
		if( contentType != null ) {
			response.getHeaders().put( HttpHeader.CONTENT_TYPE, contentType );
		}
		response.getHeaders().put( HttpHeader.CACHE_CONTROL, CACHE_CONTROL );
		response.getHeaders().put( HttpHeader.ETAG, etag );
		response.getHeaders().putDate( HttpHeader.LAST_MODIFIED, lastModified );
		response.getHeaders().put( HttpHeader.CONTENT_LENGTH, size );

		// Resources are small (css/js/images); reading whole is fine at this scale.
		// The connection layer suppresses the body for HEAD on its own.
		response.write( true, ByteBuffer.wrap( Files.readAllBytes( file ) ), callback );
		return true;
	}

	/**
	 * Resolves a URL path (already stripped of {@code /WebObjects/<Bundle>.woa/})
	 * to a file inside the bundle, or null when the path falls outside the two
	 * allowed subtrees, escapes the bundle, or names no regular file.
	 *
	 * Allowed shapes, resolved against {@code Contents/} when the bundle has
	 * one (a full .woa) and the directory itself otherwise (a split install):
	 *
	 *   WebServerResources/…
	 *   Frameworks/<one segment>/WebServerResources/…
	 */
	static Path resolveResource( final Path woa, final String urlPath ) {

		final List<String> segments = List.of( urlPath.split( "/" ) );

		for( final String segment : segments ) {
			if( segment.isEmpty() || segment.equals( "." ) || segment.equals( ".." ) || segment.contains( "\\" ) ) {
				return null;
			}
		}

		final boolean allowed =
				(segments.size() >= 2 && segments.get( 0 ).equals( "WebServerResources" )) ||
				(segments.size() >= 4 && segments.get( 0 ).equals( "Frameworks" ) && segments.get( 2 ).equals( "WebServerResources" ));

		if( !allowed ) {
			return null;
		}

		final Path contents = woa.resolve( "Contents" );
		final Path root = Files.isDirectory( contents ) ? contents : woa;

		Path file = root;
		for( final String segment : segments ) {
			file = file.resolve( segment );
		}

		try {
			// Symlinks may not lead outside the bundle
			if( !file.toRealPath().startsWith( root.toRealPath() ) ) {
				logger.warn( "Refusing WebServerResources path escaping its bundle: {}", file );
				return null;
			}
		}
		catch( final java.io.IOException e ) {
			return null; // toRealPath fails for nonexistent files — a plain 404
		}

		return Files.isRegularFile( file ) ? file : null;
	}
}
