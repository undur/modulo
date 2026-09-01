package modulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WebServerResourcesHandlerTest {

	@TempDir
	static Path dir;

	static Path woa;
	static Path splitDir;

	@BeforeAll
	static void createBundles() throws IOException {
		// A full .woa: resources live under Contents/
		woa = dir.resolve( "SW.woa" );
		Files.createDirectories( woa.resolve( "Contents/WebServerResources" ) );
		Files.createDirectories( woa.resolve( "Contents/Frameworks/SoloWeb.framework/WebServerResources/bootstrap/css" ) );
		Files.createDirectories( woa.resolve( "Contents/Resources" ) );
		Files.writeString( woa.resolve( "Contents/WebServerResources/app.css" ), "app" );
		Files.writeString( woa.resolve( "Contents/Frameworks/SoloWeb.framework/WebServerResources/admin.js" ), "admin" );
		Files.writeString( woa.resolve( "Contents/Frameworks/SoloWeb.framework/WebServerResources/bootstrap/css/bootstrap.min.css" ), "bootstrap" );
		Files.writeString( woa.resolve( "Contents/Resources/Properties" ), "secret" );

		// A split-install dir: same shape, no Contents/
		splitDir = dir.resolve( "split/SW.woa" );
		Files.createDirectories( splitDir.resolve( "WebServerResources" ) );
		Files.writeString( splitDir.resolve( "WebServerResources/app.css" ), "split-app" );
	}

	@Test
	void servesTheTwoAllowedSubtrees() {
		assertEquals( woa.resolve( "Contents/WebServerResources/app.css" ), WebServerResourcesHandler.resolveResource( woa, "WebServerResources/app.css" ) );
		assertEquals( woa.resolve( "Contents/Frameworks/SoloWeb.framework/WebServerResources/admin.js" ), WebServerResourcesHandler.resolveResource( woa, "Frameworks/SoloWeb.framework/WebServerResources/admin.js" ) );
		assertEquals( woa.resolve( "Contents/Frameworks/SoloWeb.framework/WebServerResources/bootstrap/css/bootstrap.min.css" ), WebServerResourcesHandler.resolveResource( woa, "Frameworks/SoloWeb.framework/WebServerResources/bootstrap/css/bootstrap.min.css" ) );
	}

	@Test
	void splitInstallLayoutWorksToo() {
		assertEquals( splitDir.resolve( "WebServerResources/app.css" ), WebServerResourcesHandler.resolveResource( splitDir, "WebServerResources/app.css" ) );
	}

	@Test
	void resourcesStaysPrivate() {
		assertNull( WebServerResourcesHandler.resolveResource( woa, "Resources/Properties" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "Contents/Resources/Properties" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "Frameworks/SoloWeb.framework/Resources/Properties" ) );
	}

	@Test
	void traversalIsRefused() {
		assertNull( WebServerResourcesHandler.resolveResource( woa, "WebServerResources/../Resources/Properties" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "WebServerResources/../../../../etc/passwd" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "Frameworks/../WebServerResources/app.css" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "WebServerResources/./app.css" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "WebServerResources//app.css" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "WebServerResources/a\\b.css" ) );
	}

	@Test
	void nonsenseAndMissingFilesAreNull() {
		assertNull( WebServerResourcesHandler.resolveResource( woa, "" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "WebServerResources" ) ); // the directory itself, no file
		assertNull( WebServerResourcesHandler.resolveResource( woa, "WebServerResources/missing.css" ) );
		assertNull( WebServerResourcesHandler.resolveResource( woa, "Frameworks/SoloWeb.framework/admin.js" ) ); // framework root, not its WebServerResources
		assertNull( WebServerResourcesHandler.resolveResource( woa, "SW" ) ); // the launcher
	}

	@Test
	void symlinkEscapeIsRefused() throws IOException {
		final Path outside = dir.resolve( "outside.txt" );
		Files.writeString( outside, "outside" );
		final Path link = woa.resolve( "Contents/WebServerResources/link.css" );
		try {
			Files.createSymbolicLink( link, outside );
		}
		catch( final UnsupportedOperationException | IOException e ) {
			return; // filesystem without symlink support — nothing to test
		}
		assertNull( WebServerResourcesHandler.resolveResource( woa, "WebServerResources/link.css" ) );
	}
}
