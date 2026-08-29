package modulo.woadaptorconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import modulo.woadaptorconfig.model.AdaptorConfig;
import modulo.woadaptorconfig.model.App;

class AdaptorConfigParserTest {

	private static AdaptorConfig parse( final String xml ) throws Exception {
		final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse( new ByteArrayInputStream( xml.getBytes( StandardCharsets.UTF_8 ) ) );
		return AdaptorConfigParser.parse( document );
	}

	/**
	 * wotaskd emits registered instances and unknown-instance-registry
	 * entries as SEPARATE same-named application elements. Seen in the wild:
	 * the ghost element overwrote the healthy one, making a perfectly
	 * running instance invisible to routing. Elements must merge.
	 */
	@Test
	void duplicateApplicationElementsMergeInsteadOfLastWins() throws Exception {
		final AdaptorConfig config = parse( """
				<adaptor>
				  <application name="AjaxPlayground">
				    <instance id="1" port="2011" host="hz1.rebbi.is"/>
				  </application>
				  <application name="Other">
				    <instance id="1" port="2020" host="hz1.rebbi.is"/>
				  </application>
				  <application name="AjaxPlayground">
				    <instance id="-2014" port="2014" host="hz1"/>
				    <instance id="-2015" port="2015" host="hz1"/>
				  </application>
				</adaptor>
				""" );

		final App app = config.applications().get( "AjaxPlayground" );
		assertEquals( 3, app.instances().size() );
		assertTrue( app.instances().stream().anyMatch( i -> i.id() == 1 && i.port() == 2011 ) );
		assertTrue( app.instances().stream().anyMatch( i -> i.id() == -2014 ) );
		assertEquals( 1, config.applications().get( "Other" ).instances().size() );
	}

	@Test
	void refuseNewSessionsAttributeIsParsed() throws Exception {
		final AdaptorConfig config = parse( """
				<adaptor>
				  <application name="A">
				    <instance id="1" port="2001" host="h" refuseNewSessions="YES"/>
				    <instance id="2" port="2002" host="h"/>
				  </application>
				</adaptor>
				""" );

		assertTrue( config.applications().get( "A" ).instances().get( 0 ).refuseNewSessions() );
		assertEquals( false, config.applications().get( "A" ).instances().get( 1 ).refuseNewSessions() );
	}
}
