package modulo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses wotaskd's adaptor config
 */

public class AdaptorConfigParser {

	/**
	 * FIXME: These should not be constants, and should both be configurable in a nice way // Hugi 2025-04-22
	 */
	// The password for wotaskd/JavaMonitor is provided as a system property. Note that the provided password must be the encoded password (as it appears in SiteConfig.xml)
	private static final String wotaskdPassword = System.getProperty( "wotaskdpassword" );
	private static final String wotaskdHost = "linode-4.rebbi.is";
	private static final Object wotaskdPort = 1085;

	public record Instance( int id, String host, int port ) {}

	public record Application( String name, List<Instance> instances ) {}

	public record AdaptorConfig( Map<String, Application> applications ) {

		public AdaptorConfig() {
			this( new HashMap<>() );
		}

		public Application application( String applicationName ) {
			return applications.get( applicationName );
		}
	}

	/**
	 * @return The deserialized adaptor configuration, obtained from wotaskd
	 */
	public static AdaptorConfig adaptorConfig() {
		final AdaptorConfig config = new AdaptorConfig();

		final Document siteConfig = readConfigDocument();

		final NodeList nodes = siteConfig
				.getElementsByTagName( "adaptor" ).item( 0 )
				.getChildNodes();

		for( int i = 0; i < nodes.getLength(); i++ ) {
			final Node applicationNode = nodes.item( i );

			if( applicationNode.getNodeType() == Node.ELEMENT_NODE ) {

				// Items to populate for each node
				String applicationName = applicationNode
						.getAttributes()
						.getNamedItem( "name" )
						.getNodeValue();

				final Application application = new Application( applicationName, new ArrayList<>() );
				config.applications().put( application.name(), application );

				final NodeList instanceNodes = applicationNode.getChildNodes();

				for( int j = 0; j < instanceNodes.getLength(); j++ ) {
					final Node instanceNode = instanceNodes.item( j );

					if( instanceNode.getNodeType() == Node.ELEMENT_NODE ) {
						final NamedNodeMap attributes = instanceNode.getAttributes();

						final String id = attributes.getNamedItem( "id" ).getNodeValue();
						final String host = attributes.getNamedItem( "host" ).getNodeValue();
						final String port = attributes.getNamedItem( "port" ).getNodeValue();

						final Instance instance = new Instance( Integer.valueOf( id ), host, Integer.valueOf( port ) );
						application.instances().add( instance );
					}
				}
			}
		}

		return config;
	}

	/**
	 * @return The woadaptor configuration from wotaskd as a Document
	 */
	private static Document readConfigDocument() {

		final HttpRequest request = HttpRequest
				.newBuilder( URI.create( "http://%s:%s/Apps/WebObjects/wotaskd.woa/wa/woconfig".formatted( wotaskdHost, wotaskdPort ) ) )
				.headers( "password", wotaskdPassword )
				.build();

		try {
			final byte[] adaptorConfigBytes = HttpClient
					.newHttpClient()
					.send( request, BodyHandlers.ofByteArray() )
					.body();

			return DocumentBuilderFactory
					.newInstance()
					.newDocumentBuilder()
					.parse( new ByteArrayInputStream( adaptorConfigBytes ) );
		}
		catch( IOException | InterruptedException | ParserConfigurationException | SAXException e ) {
			throw new RuntimeException( e );
		}
	}

	/**
	 * Just for some local testing...
	 */
	public static void main( String[] args ) {

		for( Entry<String, Application> entry : adaptorConfig().applications().entrySet() ) {
			var application = entry.getValue();
			System.out.println( "======= " + application.name() );
			for( Instance instance : application.instances() ) {
				System.out.println( " - " + instance );
			}
		}
	}
}