package org.geysermc.pack.converter.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModJarExtractorTest {
    @TempDir
    Path temp;

    @Test
    void extractsOnlyResourcePackEntries() throws Exception {
        Path mods = Files.createDirectory(temp.resolve("mods"));
        Path jar = mods.resolve("example.jar");
        writeJar(jar, List.of(
                entry("assets/example/blockstates/test.json", "{}"),
                entry("pack.mcmeta", "{}"),
                entry("META-INF/fabric.mod.json", "{}")));

        Path output = temp.resolve("out");
        ModJarExtractor.ExtractionReport report = ModJarExtractor.extractAll(mods, output);

        assertEquals(2, report.filesExtracted());
        assertEquals("{}", Files.readString(output.resolve("assets/example/blockstates/test.json")));
        assertEquals("{}", Files.readString(output.resolve("pack.mcmeta")));
        assertFalse(Files.exists(output.resolve("META-INF/fabric.mod.json")));
    }

    @Test
    void sortsModsAndReportsOverrides() throws Exception {
        Path mods = Files.createDirectory(temp.resolve("mods"));
        writeJar(mods.resolve("b.jar"), List.of(entry("assets/test/value.txt", "B")));
        writeJar(mods.resolve("a.jar"), List.of(entry("assets/test/value.txt", "A")));

        Path output = temp.resolve("out");
        ModJarExtractor.ExtractionReport report = ModJarExtractor.extractAll(mods, output);

        assertEquals("B", Files.readString(output.resolve("assets/test/value.txt")));
        assertEquals(1, report.collisions().size());
        assertTrue(report.collisions().get(0).contains("a.jar"));
        assertTrue(report.collisions().get(0).contains("b.jar"));
    }

    @Test
    void rejectsParentTraversal() throws Exception {
        Path jar = temp.resolve("evil.jar");
        writeJar(jar, List.of(entry("assets/../escape.txt", "bad")));

        IOException error = assertThrows(IOException.class,
                () -> ModJarExtractor.extract(jar, temp.resolve("out")));
        assertTrue(error.getMessage().contains("parent traversal"));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
    }

    @Test
    void rejectsAbsoluteEntries() throws Exception {
        Path jar = temp.resolve("evil.jar");
        writeJar(jar, List.of(entry("/absolute.txt", "bad")));

        IOException error = assertThrows(IOException.class,
                () -> ModJarExtractor.extract(jar, temp.resolve("out")));
        assertTrue(error.getMessage().contains("absolute"));
    }

    @Test
    void rejectsExistingSymlinkDestination() throws Exception {
        Path jar = temp.resolve("evil.jar");
        writeJar(jar, List.of(entry("assets/test/value.txt", "bad")));
        Path output = Files.createDirectory(temp.resolve("out"));
        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "safe");
        Path assets = Files.createDirectory(output.resolve("assets"));
        Path test = Files.createDirectory(assets.resolve("test"));
        try {
            Files.createSymbolicLink(test.resolve("value.txt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            return;
        }

        assertThrows(IOException.class, () -> ModJarExtractor.extract(jar, output));
        assertEquals("safe", Files.readString(outside));
    }

    private static ZipEntryData entry(String name, String content) {
        return new ZipEntryData(name, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void writeJar(Path path, List<ZipEntryData> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (ZipEntryData data : entries) {
                zip.putNextEntry(new ZipEntry(data.name()));
                zip.write(data.bytes());
                zip.closeEntry();
            }
        }
    }

    private record ZipEntryData(String name, byte[] bytes) {
    }
}
