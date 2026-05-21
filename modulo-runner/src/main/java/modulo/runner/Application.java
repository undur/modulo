package modulo.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modulo.Modulo;
import modulo.frontend.FrontendConfig;
import ng.appserver.NGApplication;
import ng.appserver.NGResponse;
import ng.plugins.Routes;

public class Application extends NGApplication {

	private static final Logger logger = LoggerFactory.getLogger( Application.class );

	/**
	 * Temporary location for modulo's runtime configuration file. This is a
	 * plain java.util.Properties file containing the front-end keys (see
	 * {@link #buildFrontendConfig}). The choice of /opt/webobjects/modulo.conf
	 * is interim — these properties will move into NGProperties once the
	 * framework supports the timing we need.
	 *
	 * Override via {@code -Dmodulo.config-file=...} (mainly for testing).
	 */
	private static final Path CONFIG_FILE = Path.of( System.getProperty( "modulo.config-file", "/opt/webobjects/modulo.conf" ) );

	private final Modulo _modulo;

	public static void main( String[] args ) {
		NGApplication.run( args, Application.class );
	}

	public Application() {
		final Properties config = loadConfig( CONFIG_FILE );
		_modulo = new Modulo( Config.MODULO_PROXY_PORT, buildFrontendConfig( config ) );
		_modulo.start();
	}

	/**
	 * Reads a properties file if present. Returns an empty Properties when
	 * the file is missing or unreadable (modulo then runs as a plain
	 * reverse proxy).
	 */
	static Properties loadConfig( final Path file ) {
		final Properties p = new Properties();
		if( !Files.isRegularFile( file ) ) {
			logger.info( "Config file {} not present — running without front-end", file );
			return p;
		}
		try( InputStream in = Files.newInputStream( file ) ) {
			p.load( in );
			logger.info( "Loaded {} entries from {}", p.size(), file );
		}
		catch( final IOException e ) {
			logger.warn( "Failed to read {}: {} — running without front-end", file, e.toString() );
		}
		return p;
	}

	/**
	 * Reads the front-end-related properties and assembles a
	 * {@link FrontendConfig}. Returns {@code null} if no manifest is
	 * configured — modulo then runs as a plain reverse proxy only.
	 */
	static FrontendConfig buildFrontendConfig( final Properties config ) {
		final String manifest = config.getProperty( "modulo.frontend.apache-config-file" );
		if( manifest == null ) {
			return null;
		}
		final int httpPort = parsePort( config, "modulo.frontend.http-port", 80 );
		final int httpsPort = parsePort( config, "modulo.frontend.https-port", 443 );
		final String acmeWebroot = config.getProperty( "modulo.frontend.acme-webroot" );
		return new FrontendConfig(
				Path.of( manifest ),
				httpPort,
				httpsPort,
				acmeWebroot == null ? null : Path.of( acmeWebroot ) );
	}

	private static int parsePort( final Properties config, final String key, final int fallback ) {
		final String raw = config.getProperty( key );
		return raw == null ? fallback : Integer.parseInt( raw.trim() );
	}

	/**
	 * @return Our modulo instance, for checking out configuration, status and statistics
	 */
	public Modulo modulo() {
		return _modulo;
	}

	@Override
	public Routes routes() {
		return Routes
				.create()
				.map( "/WOAdaptorInfo", request -> new NGResponse( _modulo.adaptorConfig().toString(), 200 ) )
				.map( "/", MDStartPage.class );
	}
}
