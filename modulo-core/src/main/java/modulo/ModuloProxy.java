package modulo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.error.ErrorCondition;
import modulo.error.ErrorHandling;
import modulo.error.ProxyRoutingException;
import modulo.frontend.events.Event;
import modulo.frontend.events.EventLog;

/**
 * Subclass of Jetty's proxy handler, allows us to make required modifications to the proxied request before forwarding it to the instance
 */
class ModuloProxy extends ProxyHandler.Reverse {

	private static final Logger logger = LoggerFactory.getLogger( ModuloProxy.class );

	/** Request attribute carrying the ErrorCondition of a failed proxy attempt, read by ModuloErrorHandler. */
	static final String ERROR_CONDITION_ATTRIBUTE = "modulo.error-condition";

	/** Request attributes recording which app/instance a request was routed to — set by the URI rewriter, read by response observers. */
	static final String TARGET_APP_ATTRIBUTE = "modulo.target-app";
	static final String TARGET_INSTANCE_ATTRIBUTE = "modulo.target-instance";

	/**
	 * The response header a WO instance uses to announce it is refusing new
	 * sessions, sent on normal (session-drain) responses. Its value is the
	 * number of seconds the adaptor should consider the instance refusing.
	 */
	static final String REFUSING_HEADER = "x-webobjects-refusenewsessions";

	/**
	 * The refusal announcement on *bounce* responses: a refusing instance
	 * that receives a session-less request answers 302 (meant for the
	 * adaptor to rebalance) flagged with this header instead of the one
	 * above. Observed empirically — the two-header split isn't documented
	 * anywhere friendly.
	 */
	static final String REFUSING_REDIRECTION_HEADER = "x-webobjects-refusing-redirection";

	/** Refusal validity when the announcement carries no timeout (the redirection flag). */
	static final int DEFAULT_REFUSAL_SECONDS = 60;

	/** Notified about the refusing-new-sessions state observed on upstream responses. */
	interface RefusalObserver {
		void refusing( String applicationName, int instanceId, int timeoutSeconds );

		/** A response with no refusal announcement — the instance is (back to) accepting. */
		void accepting( String applicationName, int instanceId );
	}

	private final ErrorHandling _errorHandling;
	private final EventLog _eventLog;
	private final RefusalObserver _refusalObserver;

	public ModuloProxy( Function<Request, HttpURI> httpURIRewriter, ErrorHandling errorHandling, EventLog eventLog, RefusalObserver refusalObserver ) {
		super( httpURIRewriter );
		_errorHandling = errorHandling;
		_eventLog = eventLog;
		_refusalObserver = refusalObserver;
	}

	/**
	 * Routing failures (unknown host, app not in adaptor config, no
	 * instances) surface here as ProxyRoutingException from the URI rewriter
	 * and are answered directly with the condition's assigned response.
	 */
	@Override
	public boolean handle( Request clientToProxyRequest, org.eclipse.jetty.server.Response proxyToClientResponse, Callback proxyToClientCallback ) {
		try {
			return super.handle( clientToProxyRequest, proxyToClientResponse, proxyToClientCallback );
		}
		catch( final ProxyRoutingException e ) {
			logger.info( "{} for {}{}: {}", e.condition(), clientToProxyRequest.getHttpURI().getHost(), clientToProxyRequest.getHttpURI().getPath(), e.getMessage() );
			_eventLog.add( Event.Severity.WARN, e.condition().name(), clientToProxyRequest.getHttpURI().getHost(), null, e.getMessage() );
			_errorHandling.respond( e.condition(), clientToProxyRequest, proxyToClientResponse, proxyToClientCallback );
			return true;
		}
	}

	@Override
	protected void addProxyHeaders( Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest ) {
		super.addProxyHeaders( clientToProxyRequest, proxyToServerRequest );

		// Watch upstream responses for the refusing-new-sessions announcements
		// (both dialects), attributing them to the instance the rewriter
		// routed this request to. A response with neither header clears the
		// instance's refusing state — that's how "re-allowed" takes effect
		// before the previous announcement's timeout runs out.
		if( _refusalObserver != null ) {
			proxyToServerRequest.onResponseHeaders( serverResponse -> {
				final String applicationName = (String)clientToProxyRequest.getAttribute( TARGET_APP_ATTRIBUTE );
				final Integer instanceId = (Integer)clientToProxyRequest.getAttribute( TARGET_INSTANCE_ATTRIBUTE );
				if( applicationName == null || instanceId == null ) {
					return;
				}
				final String refusingValue = serverResponse.getHeaders().get( REFUSING_HEADER );
				if( refusingValue != null ) {
					_refusalObserver.refusing( applicationName, instanceId, parseRefusalTimeout( refusingValue ) );
				}
				else if( serverResponse.getHeaders().get( REFUSING_REDIRECTION_HEADER ) != null ) {
					_refusalObserver.refusing( applicationName, instanceId, DEFAULT_REFUSAL_SECONDS );
				}
				else {
					_refusalObserver.accepting( applicationName, instanceId );
				}
			} );
		}
		proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-adaptor-version", "Modulo" ) ); // mod_WebObjects sends "Apache" here. I have no idea if that's significant, let's assume not
		proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-request-id", UUID.randomUUID().toString() ) ); // Our unique ID does not match the format of the id generated by mod_WebObjects. Doesn't seem to hurt (yet) though. We'll handle it if it turns out to be a problem
		proxyToServerRequest.headers( headers -> headers.add( "x-webobjects-request-method", clientToProxyRequest.getMethod() ) ); // Why mod_WebObjects sends the request method, already an explicit part of the request, as a header as well, I have no idea. But it can't hurt emulating it

		// Determine the originating client IP. When modulo is behind another
		// reverse proxy, that proxy will have set 'x-forwarded-for' and we
		// honour it. When modulo is the front-facing server (the now-common
		// case), there is no upstream proxy, so we use the actual remote
		// address of the connection. Previously we only read x-forwarded-for,
		// which produced a null IP — and therefore no remote_addr/remote_host
		// headers at all — once modulo became the front door. // Hugi 2026-06-19
		final String forwardedFor = clientToProxyRequest.getHeaders().get( "x-forwarded-for" );
		final String clientIPAddress = forwardedFor != null ? forwardedFor : Request.getRemoteAddr( clientToProxyRequest );

		// Add the remote_addr / remote_host headers WO apps (and wonder's
		// ERXHTTPUtilities.ipAddressFromRequest) read to identify the client.
		// Both are set from the same source (the client IP), so they share a
		// single guard. They are semantically distinct — remote_addr is the IP,
		// remote_host is the reverse-DNS hostname (falling back to the IP) — but
		// we don't do reverse-DNS lookups (Apache's "HostnameLookups Off"
		// default), so both carry the IP today. If reverse-DNS is ever added,
		// remote_host gets its own derivation and would need its own guard,
		// since the lookup can fail independently of having an IP. // Hugi 2026-06-19
		if( clientIPAddress != null ) {
			proxyToServerRequest.headers( headers -> headers.add( "remote_addr", clientIPAddress ) );
			proxyToServerRequest.headers( headers -> headers.add( "remote_host", clientIPAddress ) );
		}

		// FIXME: Forward the original host authority to the upstream, emulating
		// Apache's `ProxyPreserveHost On`. WO apps (and wonder in particular)
		// look at the `host` header to derive request.serverName() and to
		// generate absolute URLs. Without this they see the upstream's
		// host:port (e.g. hz1.rebbi.is:2008) instead of what the browser asked
		// for (e.g. www.undirskriftasofnun.is). Under Apache→modulo this
		// happened to work because Apache forwarded the original Host with
		// `ProxyPreserveHost On` and that survived our second-hop rewriting.
		// We source the original authority from the request URI rather than
		// the Host header, because HTTP/2 carries it as the :authority
		// pseudo-header (no Host header) — and Jetty exposes that as the URI
		// authority. As modulo's front-facing role matures we should migrate
		// WO apps off reading `host` directly and onto the standard
		// X-Forwarded-Host / RFC 7239 Forwarded headers, and stop overriding
		// Jetty's behavior here. See issue #7. // Hugi 2026-05-22
		final String originalAuthority = clientToProxyRequest.getHttpURI().getAuthority();
		if( originalAuthority != null ) {
			proxyToServerRequest.headers( headers -> headers.put( org.eclipse.jetty.http.HttpHeader.HOST, originalAuthority ) );
		}

		// By default, Jetty will add itself to the list of user agents on the forwarded request (meaning the user-agent header contains multiple values which may confuse some apps)
		// CHECKME: This really just corrects a mistake made by ourselves so this feels hacky. Ideally, we'd instruct Jetty to never modify the user-agent header in the first place // Hugi 2025-05-07
		final String clientUserAgent = clientToProxyRequest.getHeaders().get( "user-agent" );
		proxyToServerRequest.headers( headers -> headers.put( "user-agent", clientUserAgent ) );
	}

	/**
	 * Adjusts session-stickiness cookies on the way out: apps behind modulo
	 * are never told their instance number (mod_WebObjects transported it in
	 * adaptor URLs, which the clean-URL world doesn't have), so they emit
	 * {@code woinst=-1} — useless for stickiness. Modulo knows exactly which
	 * instance served the request, so it corrects the cookie itself: a
	 * {@code woinst} of -1 (or junk) is rewritten to the truth, and a
	 * session-creating response ({@code wosid} cookie) missing {@code woinst}
	 * entirely gets one added. A real instance number from the app is left
	 * alone. Zero app-side changes required.
	 */
	@Override
	protected org.eclipse.jetty.client.Response.CompleteListener newServerToProxyResponseListener( Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest, org.eclipse.jetty.server.Response proxyToClientResponse, Callback proxyToClientCallback ) {
		return new ProxyResponseListener( clientToProxyRequest, proxyToServerRequest, proxyToClientResponse, proxyToClientCallback ) {
			@Override
			public void onHeaders( final org.eclipse.jetty.client.Response serverToProxyResponse ) {
				super.onHeaders( serverToProxyResponse );
				ensureTruthfulWoinst( (Integer)clientToProxyRequest.getAttribute( TARGET_INSTANCE_ATTRIBUTE ), proxyToClientResponse.getHeaders() );
			}
		};
	}

	static void ensureTruthfulWoinst( final Integer instanceId, final HttpFields.Mutable headers ) {
		if( instanceId == null ) {
			return;
		}

		final List<String> cookies = new ArrayList<>();
		boolean sessionPresent = false;
		boolean woinstPresent = false;
		boolean adjusted = false;

		for( final HttpField field : headers ) {
			if( field.getHeader() != HttpHeader.SET_COOKIE ) {
				continue;
			}
			String value = field.getValue();
			if( value.startsWith( "woinst=" ) ) {
				woinstPresent = true;
				final String corrected = correctedWoinst( value, instanceId );
				adjusted |= !corrected.equals( value );
				value = corrected;
			}
			else if( value.startsWith( "wosid=" ) ) {
				sessionPresent = true;
			}
			cookies.add( value );
		}

		if( !woinstPresent && sessionPresent ) {
			cookies.add( "woinst=%d; path=/".formatted( instanceId ) );
			adjusted = true;
		}

		if( adjusted ) {
			headers.remove( HttpHeader.SET_COOKIE );
			cookies.forEach( cookie -> headers.add( HttpHeader.SET_COOKIE, cookie ) );
		}
	}

	/**
	 * @return The woinst Set-Cookie value with its instance token replaced by
	 *         [instanceId] — unconditionally. The app is never the authority
	 *         on which instance it is: a WO app "learns" its number by echoing
	 *         the request's woinst cookie, so after a failover its confident
	 *         positive value is just the stale client cookie reflected back
	 *         (observed in production: dead instance 3's ghost re-asserted by
	 *         its replacement). The routing decision modulo just made is the
	 *         only truth.
	 */
	static String correctedWoinst( final String cookieValue, final int instanceId ) {
		final int separator = cookieValue.indexOf( ';' );
		final String attributes = separator == -1 ? "" : cookieValue.substring( separator );
		return "woinst=" + instanceId + attributes;
	}

	/**
	 * @return The refusal header's timeout in seconds; the header value is
	 *         seconds by convention, but tolerate flag-style values ("YES")
	 *         with a sensible default
	 */
	private static int parseRefusalTimeout( final String headerValue ) {
		try {
			return Math.max( 1, Integer.parseInt( headerValue.trim() ) );
		}
		catch( final NumberFormatException e ) {
			return 60;
		}
	}

	/**
	 * Failures on the proxy → app hop. We tag the request with the matching
	 * ErrorCondition, then let Jetty's normal error flow run (which handles
	 * aborting the upstream exchange correctly) — ModuloErrorHandler picks
	 * the condition back up and renders its assigned response.
	 */
	@Override
	protected void onServerToProxyResponseFailure( Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest, Response serverToProxyResponse, org.eclipse.jetty.server.Response proxyToClientResponse, Callback proxyToClientCallback, Throwable failure ) {
		final ErrorCondition condition = failure instanceof TimeoutException ? ErrorCondition.UPSTREAM_TIMEOUT : ErrorCondition.UPSTREAM_UNREACHABLE;
		logger.warn( "{} proxying {}{} -> {}: {}", condition, clientToProxyRequest.getHttpURI().getHost(), clientToProxyRequest.getHttpURI().getPath(), proxyToServerRequest.getURI(), failure.toString() );
		_eventLog.add( Event.Severity.ERROR, condition.name(), clientToProxyRequest.getHttpURI().getHost(), null, "upstream %s: %s".formatted( proxyToServerRequest.getURI(), failure.toString() ) );
		clientToProxyRequest.setAttribute( ERROR_CONDITION_ATTRIBUTE, condition );
		super.onServerToProxyResponseFailure( clientToProxyRequest, proxyToServerRequest, serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, failure );
	}

	/**
	 * The server-level error handler: renders the tagged ErrorCondition's
	 * assigned response when a proxy failure brought us here, and the
	 * INTERNAL condition's response for everything else.
	 */
	public static class ModuloErrorHandler extends ErrorHandler {

		private final ErrorHandling _errorHandling;

		public ModuloErrorHandler( final ErrorHandling errorHandling ) {
			_errorHandling = errorHandling;
		}

		@Override
		public boolean handle( Request request, org.eclipse.jetty.server.Response response, Callback callback ) throws Exception {
			ErrorCondition condition = (ErrorCondition)request.getAttribute( ERROR_CONDITION_ATTRIBUTE );

			if( condition == null ) {
				condition = ErrorCondition.INTERNAL;
			}

			_errorHandling.respond( condition, request, response, callback );
			return true;
		}
	}
}