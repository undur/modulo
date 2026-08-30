package modulo;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.error.ErrorCondition;
import modulo.error.ErrorHandling;
import modulo.error.ProxyRoutingException;

/**
 * WebSocket proxying via a raw tunnel (roadmap iteration 6): Jetty's
 * ProxyHandler has no notion of Upgrade, so WebSocket handshakes are
 * intercepted BEFORE the proxy, forwarded to the routed instance on a
 * dedicated connection, and — on the instance answering 101 — both sides
 * switch to plain byte-pumping. No frame parsing or re-encoding: whatever
 * the browser and the app negotiate (subprotocols, extensions) passes
 * through untouched.
 *
 * The client-side switch uses Jetty core's own upgrade mechanism (the same
 * one jetty-websocket-core-server uses): set the request's
 * {@link HttpStream#UPGRADE_CONNECTION_ATTRIBUTE} to the tunnel connection
 * and answer 101 — after flushing the response, Jetty swaps the EndPoint's
 * connection to ours, handing over any already-buffered client bytes via
 * {@link Connection.UpgradeTo}.
 *
 * Routing is the proxy's own URI-rewrite function, so hostname→app mapping,
 * instance pinning, round-robin, refusal steering and the error conditions
 * all behave identically for WebSocket and plain HTTP. Non-upgrade requests
 * pass through to the wrapped handler untouched.
 *
 * Upgrades only arrive on HTTP/1.1 — browsers use it for wss:// unless the
 * server advertises extended CONNECT (we don't), so the h2 front-end doesn't
 * complicate this.
 */
public class WebSocketTunnelHandler extends Handler.Wrapper {

	private static final Logger logger = LoggerFactory.getLogger( WebSocketTunnelHandler.class );

	/** How long to wait for the TCP connect to the app instance. */
	public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds( 5 );

	/**
	 * Idle timeout applied to the client connection once tunneling — long,
	 * since idle-but-open is a WebSocket's natural state (apps typically
	 * ping/pong well within this).
	 */
	public static final Duration TUNNEL_IDLE_TIMEOUT = Duration.ofMinutes( 30 );

	/** Cap on the upstream handshake response head — anything bigger is broken. */
	private static final int MAX_RESPONSE_HEAD = 16 * 1024;

	private final Function<Request, HttpURI> _targetFunction;
	private final ErrorHandling _errorHandling;

	public WebSocketTunnelHandler( final Handler proxyHandler, final Function<Request, HttpURI> targetFunction, final ErrorHandling errorHandling ) {
		super( proxyHandler );
		_targetFunction = targetFunction;
		_errorHandling = errorHandling;
	}

	@Override
	public boolean handle( final Request request, final Response response, final Callback callback ) throws Exception {

		if( !isWebSocketUpgrade( request ) ) {
			return super.handle( request, response, callback );
		}

		// Route exactly like the proxy would. Routing failures (unknown host,
		// no instances, ...) are answered by delegating to the proxy handler,
		// whose error handling already does the right thing.
		final HttpURI target;
		try {
			target = _targetFunction.apply( request );
		}
		catch( final Exception e ) {
			return super.handle( request, response, callback );
		}

		final Socket upstream = new Socket();
		try {
			upstream.connect( new InetSocketAddress( target.getHost(), target.getPort() ), (int)CONNECT_TIMEOUT.toMillis() );
			upstream.setTcpNoDelay( true );
			upstream.setSoTimeout( 0 );
		}
		catch( final IOException e ) {
			logger.warn( "WebSocket tunnel: connect to {}:{} failed: {}", target.getHost(), target.getPort(), e.toString() );
			closeQuietly( upstream );
			_errorHandling.respond( ErrorCondition.UPSTREAM_UNREACHABLE, request, response, callback );
			return true;
		}

		try {
			final OutputStream out = upstream.getOutputStream();
			out.write( handshakeBytes( request, target ) );
			out.flush();

			final BufferedInputStream in = new BufferedInputStream( upstream.getInputStream() );
			final UpstreamResponseHead head = readResponseHead( in );

			if( head.status() != 101 ) {
				logger.info( "WebSocket upgrade of {}{} refused by upstream with {}", request.getHttpURI().getHost(), request.getHttpURI().getPath(), head.status() );
				closeQuietly( upstream );
				response.setStatus( head.status() );
				response.getHeaders().put( HttpHeader.CONTENT_TYPE, "text/plain" );
				response.write( true, ByteBuffer.wrap( "The application did not accept the WebSocket upgrade.\n".getBytes( StandardCharsets.UTF_8 ) ), callback );
				return true;
			}

			// 101 from the app — mirror it to the client and arm the switch
			response.setStatus( 101 );
			response.getHeaders().put( HttpHeader.UPGRADE, "websocket" );
			response.getHeaders().put( HttpHeader.CONNECTION, "Upgrade" );
			for( final HttpField field : head.fields() ) {
				if( field.getName().toLowerCase( Locale.ROOT ).startsWith( "sec-websocket-" ) ) {
					response.getHeaders().add( field );
				}
			}

			final EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
			final TunnelConnection tunnel = new TunnelConnection( endPoint, upstream, in, describe( request, target ) );
			request.setAttribute( HttpStream.UPGRADE_CONNECTION_ATTRIBUTE, tunnel );

			logger.info( "WebSocket tunnel opened: {}", tunnel );
			callback.succeeded();
			return true;
		}
		catch( final Exception e ) {
			logger.warn( "WebSocket tunnel handshake with {}:{} failed: {}", target.getHost(), target.getPort(), e.toString() );
			closeQuietly( upstream );
			_errorHandling.respond( ErrorCondition.UPSTREAM_UNREACHABLE, request, response, callback );
			return true;
		}
	}

	static boolean isWebSocketUpgrade( final Request request ) {
		if( !"GET".equalsIgnoreCase( request.getMethod() ) ) {
			return false;
		}
		final String upgrade = request.getHeaders().get( HttpHeader.UPGRADE );
		return upgrade != null
				&& "websocket".equalsIgnoreCase( upgrade )
				&& request.getHeaders().contains( HttpHeader.CONNECTION, "upgrade" );
	}

	/**
	 * The handshake request forwarded to the instance: the original request
	 * line/headers minus hop-by-hop, with the upgrade pair restored and —
	 * matching the proxy's ProxyPreserveHost behavior — the client's original
	 * authority as Host.
	 */
	private static byte[] handshakeBytes( final Request request, final HttpURI target ) {
		final StringBuilder handshake = new StringBuilder( 512 );
		handshake.append( "GET " ).append( target.getPathQuery() ).append( " HTTP/1.1\r\n" );

		final String authority = request.getHttpURI().getAuthority();
		handshake.append( "Host: " ).append( authority != null ? authority : target.getAuthority() ).append( "\r\n" );

		for( final HttpField field : request.getHeaders() ) {
			final String name = field.getName().toLowerCase( Locale.ROOT );
			switch( name ) {
				case "host", "connection", "upgrade", "keep-alive", "te", "transfer-encoding", "proxy-connection", "proxy-authorization" -> {
					// dropped — hop-by-hop, or replaced below
				}
				default -> handshake.append( field.getName() ).append( ": " ).append( field.getValue() ).append( "\r\n" );
			}
		}

		handshake.append( "Connection: Upgrade\r\n" );
		handshake.append( "Upgrade: websocket\r\n" );
		handshake.append( "\r\n" );
		return handshake.toString().getBytes( StandardCharsets.ISO_8859_1 );
	}

	record UpstreamResponseHead( int status, List<HttpField> fields ) {}

	/**
	 * Reads the upstream's handshake response (status line + headers).
	 * Byte-by-byte against the BufferedInputStream, so nothing beyond the
	 * head is consumed — WS frames the app sends immediately after its 101
	 * stay in the stream for the pump.
	 */
	static UpstreamResponseHead readResponseHead( final InputStream in ) throws IOException {
		final ByteArrayOutputStream head = new ByteArrayOutputStream( 512 );
		int state = 0; // consecutive \r\n\r\n progress

		while( state < 4 ) {
			final int b = in.read();
			if( b == -1 ) {
				throw new IOException( "Upstream closed during WebSocket handshake" );
			}
			if( head.size() > MAX_RESPONSE_HEAD ) {
				throw new IOException( "Upstream WebSocket handshake response too large" );
			}
			head.write( b );
			state = switch( state ) {
				case 0, 2 -> b == '\r' ? state + 1 : 0;
				default -> b == '\n' ? state + 1 : 0;
			};
		}

		final String[] lines = head.toString( StandardCharsets.ISO_8859_1 ).split( "\r\n" );
		final String[] statusLine = lines[0].split( " ", 3 );
		if( statusLine.length < 2 || !statusLine[0].startsWith( "HTTP/1" ) ) {
			throw new IOException( "Upstream sent a malformed WebSocket handshake response: " + lines[0] );
		}
		final int status = Integer.parseInt( statusLine[1] );

		final List<HttpField> fields = new ArrayList<>();
		for( int i = 1; i < lines.length; i++ ) {
			final int colon = lines[i].indexOf( ':' );
			if( colon > 0 ) {
				fields.add( new HttpField( lines[i].substring( 0, colon ).trim(), lines[i].substring( colon + 1 ).trim() ) );
			}
		}
		return new UpstreamResponseHead( status, fields );
	}

	private static String describe( final Request request, final HttpURI target ) {
		return "%s%s -> %s (app %s, instance %s)".formatted(
				request.getHttpURI().getHost(),
				request.getHttpURI().getPath(),
				target.getAuthority(),
				request.getAttribute( ModuloProxy.TARGET_APP_ATTRIBUTE ),
				request.getAttribute( ModuloProxy.TARGET_INSTANCE_ATTRIBUTE ) );
	}

	private static void closeQuietly( final Socket socket ) {
		try {
			socket.close();
		}
		catch( final IOException ignored ) {}
	}

	/**
	 * The post-upgrade byte pump. Client→upstream runs on Jetty's fill
	 * callbacks (upstream writes go to a local/LAN app socket — cheap);
	 * upstream→client runs on a dedicated virtual thread doing blocking
	 * reads. Either side closing or failing tears down both.
	 */
	static class TunnelConnection extends AbstractConnection implements Connection.UpgradeTo {

		private final Socket _upstream;
		private final InputStream _upstreamIn;
		private final OutputStream _upstreamOut;
		private final String _description;
		private final long _openedAt = System.nanoTime();
		private final ByteBuffer _clientBuffer = BufferUtil.allocate( 16 * 1024 );

		/** Client bytes Jetty had already read before the switch — first thing forwarded upstream. */
		private ByteBuffer _prefill;

		TunnelConnection( final EndPoint endPoint, final Socket upstream, final InputStream upstreamIn, final String description ) throws IOException {
			super( endPoint, runnable -> Thread.ofVirtual().start( runnable ) );
			_upstream = upstream;
			_upstreamIn = upstreamIn;
			_upstreamOut = upstream.getOutputStream();
			_description = description;
		}

		@Override
		public void onUpgradeTo( final ByteBuffer buffer ) {
			if( BufferUtil.hasContent( buffer ) ) {
				_prefill = BufferUtil.copy( buffer );
			}
		}

		@Override
		public void onOpen() {
			super.onOpen();
			getEndPoint().setIdleTimeout( TUNNEL_IDLE_TIMEOUT.toMillis() );

			Thread.ofVirtual().name( "ws-tunnel-downstream" ).start( this::pumpUpstreamToClient );

			try {
				if( _prefill != null ) {
					_upstreamOut.write( BufferUtil.toArray( _prefill ) );
					_upstreamOut.flush();
					_prefill = null;
				}
			}
			catch( final IOException e ) {
				close();
				return;
			}

			fillInterested();
		}

		@Override
		public void onFillable() {
			try {
				while( true ) {
					BufferUtil.clear( _clientBuffer );
					final int filled = getEndPoint().fill( _clientBuffer );

					if( filled == 0 ) {
						fillInterested();
						return;
					}
					if( filled < 0 ) {
						close();
						return;
					}
					_upstreamOut.write( BufferUtil.toArray( _clientBuffer ) );
					_upstreamOut.flush();
				}
			}
			catch( final IOException e ) {
				close();
			}
		}

		private void pumpUpstreamToClient() {
			final byte[] buffer = new byte[16 * 1024];
			try {
				while( true ) {
					final int read = _upstreamIn.read( buffer );
					if( read < 0 ) {
						break;
					}
					final FutureCallback written = new FutureCallback();
					getEndPoint().write( written, ByteBuffer.wrap( buffer, 0, read ) );
					written.get();
				}
			}
			catch( final Exception ignored ) {
				// either side going away ends the tunnel; close() below tells the other side
			}
			close();
		}

		@Override
		public void onClose( final Throwable cause ) {
			closeQuietly( _upstream );
			logger.info( "WebSocket tunnel closed after {}s: {}", Duration.ofNanos( System.nanoTime() - _openedAt ).toSeconds(), _description );
			super.onClose( cause );
		}

		@Override
		public void close() {
			closeQuietly( _upstream );
			getEndPoint().close();
		}

		@Override
		public String toConnectionString() {
			return _description;
		}
	}
}
