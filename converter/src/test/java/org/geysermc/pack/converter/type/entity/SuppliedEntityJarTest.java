package org.geysermc.pack.converter.type.entity;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in exact user-supplied binaries. Never downloads or substitutes mods. */
@EnabledIfSystemProperty(named="entity.fixture.classpath", matches=".+")
class SuppliedEntityJarTest {
    private EntityConverter.Input input(String model) throws Exception {
        var base=EntityGeometryTest.input(EntityGeometryTest.Model.class,Duration.ofSeconds(20));
        var cp=Arrays.stream(Files.readString(Path.of(System.getProperty("entity.fixture.classpath"))).trim()
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(Path::of).toList();
        return new EntityConverter.Input(base.javaExecutable(),base.workerClasspath(),cp,model,"geometry.fixture.model",base.timeout());
    }

    private void verifyJar(EntityConverter.Input input,String file,String hash) throws Exception {
        Path jar=input.modelClasspath().stream().filter(p->p.getFileName().toString().equals(file)).findFirst().orElseThrow();
        assertEquals(hash,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
    }

    @Test void baldEagleExactJarPreservesTwentyPartsAndEighteenCubes() throws Exception {
        var input=input("com.github.alexthe666.alexsmobs.client.model.ModelBaldEagle");
        verifyJar(input,"alexsmobs-2.1.11-fabric+26.2.jar","a548655daf4b8336cbc43544d5eae6e0883217afbe7a5f62c0587c4b62f2484d");
        verifyJar(input,"codxlib-1.6.0-fabric+26.2.jar","d6887454c1b4cab58b66bd4c0433ea0a76452084535686e7b91c7865ae721282");
        var geometry=new EntityConverter().convert(input);
        var bones=geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        assertEquals(20,bones.size());
        int cubes=0,planes=0;
        for(var b:bones) if(b.getAsJsonObject().has("cubes")) {
            for(var c:b.getAsJsonObject().getAsJsonArray("cubes")) {
                cubes++;
                for(var n:c.getAsJsonObject().getAsJsonArray("size")) if(n.getAsDouble()==0) { planes++; break; }
            }
        }
        assertEquals(18,cubes);
        assertTrue(planes>0,"Real zero-thickness planes must survive");
        // Output is kept outside source control until deliberately reviewed as a golden.
        Path actual=Path.of("build/entity-fixtures/bald-eagle.actual.geo.json");
        Files.createDirectories(actual.getParent()); Files.writeString(actual,geometry.toString());
        try(var golden=getClass().getResourceAsStream("/entity/bald-eagle.geo.json")) {
            assertNotNull(golden,"Missing reviewed golden");
            assertEquals(JsonParser.parseString(new String(golden.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)),geometry);
        }
    }

    @Test void secondModIsRealButNotSilentlyMisreadAsCitadel() throws Exception {
        var input=input("fuzs.mutantmonsters.common.client.model.MutantCreeperModel");
        verifyJar(input,"MutantMonsters-v26.2.2-mc26.2.x-Fabric.jar","618b07e168abc83ebbdb548f5247eda0cabc90a40fa0e4ed3a587cbf2ce82e71");
        IOException failure=assertThrows(IOException.class,()->new EntityConverter().convert(input));
        assertTrue(failure.getMessage().contains("getAllParts"),failure.toString());
    }
}
