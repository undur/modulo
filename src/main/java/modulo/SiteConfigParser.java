package modulo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Experimental parsing of SiteConfig.xml
 *
 * Needs a SiteConfig.xml file in src/main/resources to function (one is currently not provided by default)
 */

public class SiteConfigParser {

	public record Config( List<Application> applications ) {

		public Application application( String applicationName ) {
			for( Application application : applications() ) {
				if( application.name().equals( applicationName ) ) {
					return application;
				}
			}

			return null;
		}
	}

	public record Application( String name, List<Instance> instances ) {}

	public record Instance( int id, String host, int port ) {}

	public static void main( String[] args ) {
		final Config config = new Config( new ArrayList<>() );
		importApplications( config );
		importInstances( config );

		for( Application application : config.applications() ) {
			System.out.println( application.name() );
			for( Instance instance : application.instances() ) {
				System.out.println( " - " + instance );
			}
		}
	}

	private static void importApplications( final Config config ) {
		final Document siteConfig = siteConfig();

		final NodeList nodes = siteConfig
				.getElementsByTagName( "applicationArray" ).item( 0 )
				.getChildNodes();

		for( int i = 0; i < nodes.getLength(); i++ ) {
			final Node node = nodes.item( i );
			final NodeList values = node.getChildNodes();

			// Items to populate for each node
			String applicationName = null;

			for( int j = 0; j < values.getLength(); j++ ) {
				final Node appNode = values.item( j );

				if( appNode.getNodeType() == Node.ELEMENT_NODE ) {
					final String nodeName = appNode.getNodeName();
					final String nodeValue = appNode.getTextContent();

					if( "name".equals( nodeName ) ) {
						applicationName = nodeValue;
					}
				}
			}

			// FIXME: OK, this absolutely sucks. Need some practice with the DOM API // Hugi 2025-04-22
			if( applicationName != null ) {
				config.applications().add( new Application( applicationName, new ArrayList<>() ) );
			}
		}
	}

	private static void importInstances( final Config config ) {
		final Document siteConfig = siteConfig();

		final NodeList nodes = siteConfig
				.getElementsByTagName( "instanceArray" ).item( 0 )
				.getChildNodes();

		for( int i = 0; i < nodes.getLength(); i++ ) {
			final Node node = nodes.item( i );
			final NodeList values = node.getChildNodes();

			String applicationName = null;
			Integer id = null;
			String host = null;
			Integer port = null;

			for( int j = 0; j < values.getLength(); j++ ) {
				final Node appNode = values.item( j );
				final String nodeName = appNode.getNodeName();
				final String nodeValue = appNode.getTextContent();

				switch( nodeName ) {
					case "applicationName" -> applicationName = nodeValue;
					case "id" -> id = Integer.valueOf( nodeValue );
					case "hostName" -> host = nodeValue;
					case "port" -> port = Integer.valueOf( nodeValue );
				}
			}

			if( id != null ) {
				config
						.application( applicationName )
						.instances()
						.add( new Instance( id, host, port ) );
			}
		}
	}

	private static Document siteConfig() {
		try( final InputStream xmlStream = SiteConfigParser.class.getClassLoader().getResourceAsStream( "SiteConfig.xml" )) {

			if( xmlStream == null ) {
				throw new IllegalStateException( "Missing a SiteConfig.xml test file from src/main/resources" );
			}

			final DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			final DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			final Document doc = dBuilder.parse( xmlStream );
			// doc.getDocumentElement().normalize();
			return doc;
		}
		catch( IOException | ParserConfigurationException | SAXException e ) {
			throw new RuntimeException( e );
		}
	}
}