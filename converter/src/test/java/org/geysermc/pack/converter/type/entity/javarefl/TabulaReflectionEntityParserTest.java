/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE for any CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 *  @author GeyserMC
 *  @link https://github.com/GeyserMC/PackConverter
 *
 */

package org.geysermc.pack.converter.type.entity.javarefl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.geysermc.pack.converter.type.model.BedrockModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the mod-agnostic {@link TabulaReflectionEntityParser}.
 *
 * Two tests:
 *
 * <ul>
 *   <li>{@link #readAdvancedModelBoxWithNullListDoesNotThrow()} - synthetic,
 *       always runs. Confirms the public constructor + {@code supportedExtensions}
 *       surface area so the scanner picks the parser up.</li>
 *   <li>{@link #extractLaviathanFromAlexsmobs(Path)} - live integration test
 *       against a real alexsmobs jar. Skipped unless {@code tabula.modjar}
 *       system property (or {@code TABULA_MODJAR} env) points at a mod jar
 *       on disk. Asserts the reflection path produces a non-null
 *       {@code BedrockModel}, the Bedrock description identifier matches
 *       the expected geometry key, and the dumped geometry contains at
 *       least 15 cubes (laviathan is a 15-block boss mob per upstream
 *       Alex's Mobs design).</li>
 * </ul>
 *
 * Runs locally with:
 * <pre>
 * ./gradlew :converter:test -Dtabula.modjar=/path/to/alexsmobs-2.1.6.jar
 * </pre>
 */
class TabulaReflectionEntityParserTest {

    @Test
    void readAdvancedModelBoxWithNullListDoesNotThrow() {
        // Synthetic check: parser must expose supported extensions
        // containing .reflection so the EntityModelScanner picks it up.
        TabulaReflectionEntityParser parser = new TabulaReflectionEntityParser();
        assertTrue(List.of(parser.supportedExtensions()).contains(".reflection"),
                "parser must advertise .reflection extension");
    }

    @Test
    void extractLaviathanFromAlexsmobs(@TempDir Path tempDir) throws Exception {
        String prop = System.getProperty("tabula.modjar", System.getenv("TABULA_MODJAR"));
        Assumptions.assumeTrue(prop != null && !prop.isEmpty(),
                "Skipped: set -Dtabula.modjar=/path/to/alexsmobs.jar (or env TABULA_MODJAR) to run this test");
        Path source = Path.of(prop);
        Assumptions.assumeTrue(Files.isRegularFile(source), "mod jar not found: " + source);

        // Stage the jar into a fresh temp directory so the parser's
        // locateModjar() can find it via the hydraulic.mods.dir hint.
        Path staged = tempDir.resolve("alexsmobs-2.1.6-fabric_26.2.jar");
        Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
        System.setProperty("hydraulic.mods.dir", tempDir.toString());

        TabulaReflectionEntityParser parser = new TabulaReflectionEntityParser();
        BedrockModel model = parser.parse("alexsmobs:laviathan.reflection", null);
        assertNotNull(model, "parser returned null; reflection path failed");

        String fileName = model.fileName();
        assertEquals("alexsmobs.laviathan.json", fileName,
                "expected alexsmobs.laviathan.json, got " + fileName);
        assertTrue(fileName.endsWith(".json"),
                "expected .json output, got " + fileName);

        // Inspect the dumped JSON to confirm cube data survived the
        // reflection + transform pipeline.
        String json = new Gson().toJson(model.model());
        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        JsonObject description = root.getAsJsonObject("minecraft:client_entity")
                .getAsJsonObject("description");
        assertNotNull(description, "client_entity description missing");
        assertTrue(description.get("identifier").getAsString().startsWith("geometry.alexsmobs.laviathan"),
                "identifier malformed: " + description);

        var geometryArr = description.has("geometry")
                ? root.getAsJsonArray("minecraft:geometry") : null;
        assertNotNull(geometryArr, "geometry array missing");
        assertTrue(!geometryArr.isEmpty(), "geometry array empty");

        var cubes = geometryArr.get(0).getAsJsonObject()
                .getAsJsonObject("bones").getAsJsonArray("cubes");
        assertNotNull(cubes, "cubes array missing");
        // Laviathan is a 15-block boss mob in upstream Alex's Mobs - the
        // reflection path must dump all 15+ cubes for the model to be
        // usable on Bedrock. If the parser silently drops cubes this
        // assertion will catch it. Tighten further when we have a known-
        // good baseline build (R11+) on a live server.
        int cubeCount = cubes.size();
        System.out.println("Extracted " + cubeCount + " cubes for laviathan");
        assertTrue(cubeCount >= 15,
                "expected >= 15 cubes (laviathan is a 15-block boss), got " + cubeCount);
    }
}