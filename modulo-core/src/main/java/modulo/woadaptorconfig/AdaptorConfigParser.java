package modulo.woadaptorconfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import modulo.woadaptorconfig.model.AdaptorConfig;
import modulo.woadaptorconfig.model.App;
import modulo.woadaptorconfig.model.Instance;

/**
 * Parses wotaskd's adaptor config
 */

public class AdaptorConfigParser {

	/**
	 * wotaskd host
	 */
	private final String _wotaskdHost;

	/**
	 * wotaskd port
	 */
	private final Integer _wotaskdPort;

	/**
	 * wotaskd password, encoded (as it appears in SiteConfig.xml)
	 */
	private final String _wotaskdPassword;

	public AdaptorConfigParser( final String host, final int port, final String password ) {
		_wotaskdHost = host;
		_wotaskdPort = port;
		_wotaskdPassword = password;
	}

	/**
	 * @return The deserialized adaptor configuration, obtained from wotaskd
	 */
	public AdaptorConfig fetchAdaptorConfig() {
		final AdaptorConfig config = new AdaptorConfig();

		final Document siteConfig = fetchAdaptorConfigDocument();

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

				final App application = new App( applicationName, new ArrayList<>() );
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
	private Document fetchAdaptorConfigDocument() {

		final HttpRequest request = HttpRequest
				.newBuilder( URI.create( "http://%s:%s/Apps/WebObjects/wotaskd.woa/wa/woconfig".formatted( _wotaskdHost, _wotaskdPort ) ) )
				.headers( "password", _wotaskdPassword )
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

		AdaptorConfigParser p = new AdaptorConfigParser( "linode-4.rebbi.is", 1085, System.getProperty( "wotaskdpassword" ) );

		for( Entry<String, App> entry : p.fetchAdaptorConfig().applications().entrySet() ) {
			var application = entry.getValue();
			System.out.println( "======= " + application.name() );
			for( Instance instance : application.instances() ) {
				System.out.println( " - " + instance );
			}
		}
	}
}