package org.geysermc.pack.converter.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Shared containment check for pack asset paths, on both host and ZIP filesystems. */
public final class PackPaths {
    private PackPaths() { }

    public static Path resolveSafeRelative(Path directory, String relative) throws IOException {
        if (relative == null || relative.isBlank()) throw new IOException("Refusing empty asset path");
        // Pack paths use '/', including when produced on Windows. Reject drive-relative paths too.
        String portable = relative.replace('\\', '/');
        if (portable.startsWith("/") || portable.indexOf(':') >= 0) {
            throw new IOException("Refusing absolute or drive-qualified path: " + relative);
        }
        try {
            Path root = directory.normalize();
            Path resolved = root.resolve(portable).normalize();
            if (resolved.equals(root) || !resolved.startsWith(root)) {
                throw new IOException("Refusing path " + relative + " that escapes " + root);
            }
            // Output roots are caller-owned. Do not follow pre-existing links below them.
            Path current = root;
            for (Path component : root.relativize(resolved)) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) throw new IOException("Refusing symbolic link: " + current);
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new IOException("Invalid asset path: " + relative, e);
        }
    }
}
