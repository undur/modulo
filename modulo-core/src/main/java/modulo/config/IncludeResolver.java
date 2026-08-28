package modulo.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the sites config's {@code include} entries to concrete files.
 *
 * A pattern is a path whose components may contain single-level glob
 * wildcards ({@code *}, {@code ?}, {@code [...]}, {@code {...}}) — e.g.
 * {@code /rebbi/*&#47;conf/site.json}. Deliberately no {@code **}: recursive
 * matching makes it too easy to pull in files nobody meant to be config.
 *
 * Semantics chosen for config-file sanity:
 * <ul>
 * <li>A pattern without any wildcard must name an existing file — a typo'd
 * explicit path should fail, not silently include nothing.</li>
 * <li>A wildcard pattern may match zero files (the caller logs a warning) —
 * "no sites of this kind yet" is a legitimate state.</li>
 * <li>Matches are sorted for a deterministic load order.</li>
 * </ul>
 */
class IncludeResolver {

	static List<Path> resolve( final Path baseDir, final String pattern ) throws IOException {
		if( pattern == null || pattern.isBlank() ) {
			throw new SitesConfigException( "Empty include pattern" );
		}
		if( pattern.contains( "**" ) ) {
			throw new SitesConfigException( "Include pattern \"%s\": recursive \"**\" globs are not supported — list the directory levels explicitly".formatted( pattern ) );
		}

		if( !containsWildcard( pattern ) ) {
			final Path file = baseDir.resolve( pattern ).normalize();
			if( !Files.isRegularFile( file ) ) {
				throw new SitesConfigException( "Included sites file %s does not exist".formatted( file ) );
			}
			return List.of( file );
		}

		final Path resolved = baseDir.resolve( pattern ).normalize();
		final Path root = resolved.getRoot() != null ? resolved.getRoot() : baseDir;
		final List<String> components = new ArrayList<>();
		for( final Path component : (resolved.getRoot() != null ? resolved.getRoot().relativize( resolved ) : resolved) ) {
			components.add( component.toString() );
		}

		final List<Path> matches = new ArrayList<>();
		expand( root, components, 0, matches );
		Collections.sort( matches );
		return matches;
	}

	private static void expand( final Path dir, final List<String> components, final int index, final List<Path> matches ) throws IOException {
		if( index == components.size() ) {
			if( Files.isRegularFile( dir ) ) {
				matches.add( dir );
			}
			return;
		}

		final String component = components.get( index );

		if( !containsWildcard( component ) ) {
			final Path child = dir.resolve( component );
			if( Files.exists( child ) ) {
				expand( child, components, index + 1, matches );
			}
			return;
		}

		if( !Files.isDirectory( dir ) ) {
			return;
		}
		try( DirectoryStream<Path> stream = Files.newDirectoryStream( dir, component ) ) {
			final List<Path> children = new ArrayList<>();
			stream.forEach( children::add );
			for( final Path child : children ) {
				expand( child, components, index + 1, matches );
			}
		}
	}

	private static boolean containsWildcard( final String s ) {
		return s.chars().anyMatch( c -> c == '*' || c == '?' || c == '[' || c == '{' );
	}
}
