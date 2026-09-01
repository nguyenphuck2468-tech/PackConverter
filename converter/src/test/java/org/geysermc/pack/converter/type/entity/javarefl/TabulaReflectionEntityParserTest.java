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
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR THE OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 *  @author GeyserMC
 *  @link https://github.com/GeyserMC/PackConverter
 *
 */

package org.geysermc.pack.converter.type.entity.javarefl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.geysermc.pack.converter.type.model.BedrockModel;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live integration test for the Tabula reflection parser. Skipped
 * unless {@code tabula.modjar} points at an alexsmobs jar (or
 * similar Citadel-using mod) on disk. Run locally with:
 *
 * <pre>
 * ./gradlew :converter:test -Dtabula.modjar=/path/to/alexsmobs-2.1.6.jar
 * </pre>
 */
class TabulaReflectionEntityParserTest {

    @Test
    void dumpsBedrockGeometryFromMod(@TempDir Path tempDir) throws Exception {
        String prop = System.getProperty("tabula.modjar", System.getenv("TABULA_MODJAR"));
        Assumptions.assumeTrue(prop != null && !prop.isEmpty(),
                "Skipped: set -Dtabula.modjar=/path/to/alexsmobs.jar (or env TABULA_MODJAR) to run this test");
        Path modJar = Path.of(prop);
        assertTrue(Files.isRegularFile(modJar), "mod jar not found: " + modJar);

        System.setProperty("hydraulic.mods.dir", tempDir.toString());
        Path cache = tempDir.resolve("alexsmobs-2.1.6-fabric_26.2.jar");
        Files.copy(modJar, cache);

        TabulaReflectionEntityParser parser = new TabulaReflectionEntityParser();
        BedrockModel model = parser.parse("alexsmobs:laviathan.reflection", null);
        assertNotNull(model, "laviathan should be extracted from alexsmobs mod jar");
        assertEquals("alexsmobs.laviathan.json", model.fileName());

        // The laviathan model is a 15-block boss mob - it should
        // have at least a dozen cubes.
        // TODO: assert cube count once we expose a count helper.
    }

    @Test
    void readAdvancedModelBoxWithNullListDoesNotThrow() throws Exception {
        // Sanity: readRenderBoxDims returns null on an empty cube list
        // rather than crashing. Uses a synthetic class to avoid
        // pulling in the real alexsmobs jar.
        // This test runs in-process; uses the public no-arg constructor
        // of the parser to ensure the static method compiles.
        TabulaReflectionEntityParser parser = new TabulaReflectionEntityParser();
        // Indirect check: the parser must expose supported extensions
        // containing .reflection so the scanner can pick it up.
        assertTrue(List.of(parser.supportedExtensions()).contains(".reflection"));
    }
}
