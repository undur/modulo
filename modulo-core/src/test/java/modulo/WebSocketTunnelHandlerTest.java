package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

public class WebSocketTunnelHandlerTest {

	private static InputStream stream( final String s ) {
		return new ByteArrayInputStream( s.getBytes( StandardCharsets.ISO_8859_1 ) );
	}

	@Test
	public void parsesSwitchingProtocolsHead() throws IOException {
		final InputStream in = stream( """
				HTTP/1.1 101 Switching Protocols\r
				Upgrade: websocket\r
				Connection: Upgrade\r
				Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r
				\r
				hello""" );

		final WebSocketTunnelHandler.UpstreamResponseHead head = WebSocketTunnelHandler.readResponseHead( in );
		assertEquals( 101, head.status() );
		assertEquals( "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", head.fields().stream()
				.filter( f -> f.getName().equalsIgnoreCase( "sec-websocket-accept" ) )
				.findFirst().orElseThrow().getValue() );

		// bytes after the head (an eager first WS frame) stay unconsumed for the pump
		final byte[] remaining = in.readAllBytes();
		assertEquals( 7, remaining.length );
		assertEquals( (byte)0x81, remaining[0] );
	}

	@Test
	public void parsesRefusalHead() throws IOException {
		final WebSocketTunnelHandler.UpstreamResponseHead head = WebSocketTunnelHandler.readResponseHead( stream( """
				HTTP/1.1 404 Not Found\r
				Content-Length: 0\r
				\r
				""" ) );
		assertEquals( 404, head.status() );
	}

	@Test
	public void rejectsTruncatedHead() {
		assertThrows( IOException.class, () -> WebSocketTunnelHandler.readResponseHead( stream( "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket" ) ) );
	}

	@Test
	public void rejectsMalformedStatusLine() {
		assertThrows( IOException.class, () -> WebSocketTunnelHandler.readResponseHead( stream( "SPEAK FRIEND AND ENTER\r\n\r\n" ) ) );
	}
}
