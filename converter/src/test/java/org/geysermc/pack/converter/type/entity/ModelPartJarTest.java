package org.geysermc.pack.converter.type.entity;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "entity.fixture.classpath", matches = ".+")
class ModelPartJarTest {
    @Test void exactMutantCreeperFactoryMatchesIndependentGolden() throws Exception {
        var base = EntityGeometryTest.input(EntityGeometryTest.Model.class, Duration.ofSeconds(20));
        var cp = Arrays.stream(Files.readString(Path.of(System.getProperty("entity.fixture.classpath"))).trim()
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(Path::of).toList();
        Path jar = cp.stream().filter(p -> p.getFileName().toString().equals("MutantMonsters-v26.2.2-mc26.2.x-Fabric.jar")).findFirst().orElseThrow();
        assertEquals("618b07e168abc83ebbdb548f5247eda0cabc90a40fa0e4ed3a587cbf2ce82e71",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        assertEquals(25, Runtime.version().feature(), "Real 26.2 fixture gate requires Java 25");
        var input = new EntityConverter.Input(base.javaExecutable(), base.workerClasspath(), cp,
                "fuzs.mutantmonsters.common.client.model.MutantCreeperModel", "geometry.fixture.mutant_creeper", base.timeout());
        var result = new EntityConverter().convertLayer(input);
        Path output = Path.of("build/entity-fixtures/mutant-creeper.actual.geo.json");
        Files.createDirectories(output.getParent()); Files.writeString(output, result.toString());
        var geometry = result.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        assertEquals(128, geometry.getAsJsonObject("description").get("texture_width").getAsInt());
        assertEquals(64, geometry.getAsJsonObject("description").get("texture_height").getAsInt());
        var bones = geometry.getAsJsonArray("bones");
        assertEquals(13, bones.size());
        assertEquals(12, bones.asList().stream().filter(b -> b.getAsJsonObject().has("cubes")).count());
        try (var golden = getClass().getResourceAsStream("/entity/mutant-creeper.geo.json")) {
            assertNotNull(golden, "Missing independently reviewed golden");
            assertEquals(JsonParser.parseString(new String(golden.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)), result);
        }
    }
}
