package org.geysermc.pack.converter.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.net.URI;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PackPathsTest {
    @TempDir Path root;

    @Test void rejectsPortableEscapesAndInvalidPaths() {
        for (String path : new String[]{"../evil", "..\\evil", "/evil", "\\\\host\\evil",
                "C:/evil", "C:evil", "good:stream", "", ".", "a/..", "a\u0000b"}) {
            assertThrows(IOException.class, () -> PackPaths.resolveSafeRelative(root, path), path);
        }
    }

    @Test void handlesWindowsSeparatorsOnEveryHost() throws Exception {
        assertEquals(root.resolve("models/entity/mob.geo.json"),
                PackPaths.resolveSafeRelative(root, "models\\entity\\mob.geo.json"));
    }

    @Test void alsoContainsZipEntries() throws Exception {
        try (var zip = FileSystems.newFileSystem(URI.create("jar:" + root.resolve("pack.zip").toUri()), Map.of("create", "true"))) {
            Path models = zip.getPath("/models/entity");
            assertEquals(models.resolve("mob.geo.json"), PackPaths.resolveSafeRelative(models, "mob.geo.json"));
            assertThrows(IOException.class, () -> PackPaths.resolveSafeRelative(models, "..\\..\\outside"));
        }
    }
}
