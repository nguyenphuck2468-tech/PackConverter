package org.geysermc.pack.converter.type.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.pack.converter.util.PackPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Explicit static geometry API. Does not discover models or register Geyser entities. */
public final class EntityConverter {
    static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

    /** Classpaths are supplied by the caller from the exact conversion runtime, never discovered globally. */
    public record Input(Path javaExecutable, List<Path> workerClasspath, List<Path> modelClasspath,
                        String modelClass, String geometryId, Duration timeout) {
        public Input {
            workerClasspath = List.copyOf(workerClasspath);
            modelClasspath = List.copyOf(modelClasspath);
            if (workerClasspath.isEmpty() || modelClasspath.isEmpty()) throw new IllegalArgumentException("Empty classpath");
            if (!modelClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) throw new IllegalArgumentException("Invalid model class");
            if (!geometryId.matches("geometry\\.[a-z0-9_.]+")) throw new IllegalArgumentException("Invalid geometry identifier");
            if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalArgumentException("Timeout must be between 1ms and 2 minutes");
            }
        }
    }

    public JsonObject convert(Input input) throws IOException, InterruptedException {
        return convert(input, "citadel");
    }

    /** Explicit opt-in to the separate LayerDefinition/ModelPart reader. */
    public JsonObject convertLayer(Input input) throws IOException, InterruptedException {
        return convert(input, "layer");
    }

    private JsonObject convert(Input input, String source) throws IOException, InterruptedException {
        Path arguments = Files.createTempFile("entity-worker-", ".args");
        Process process = null;
        try {
            // An argfile also works with the long client-runtime classpaths used on Windows.
            var args = List.of("-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-cp", classpath(input.workerClasspath()),
                    EntityWorker.class.getName(), classpath(input.modelClasspath()), input.modelClass(), input.geometryId(), source);
            Files.write(arguments, args.stream().map(EntityConverter::quoteArgument).toList(), StandardCharsets.UTF_8);
            process = new ProcessBuilder(input.javaExecutable().toString(), "@" + arguments).redirectErrorStream(true).start();
            Process worker = process;
            AtomicReference<IOException> readError = new AtomicReference<>();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (var stream = worker.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        if (bytes.size() + read > MAX_OUTPUT_BYTES) {
                            throw new IOException("Entity worker output exceeded " + MAX_OUTPUT_BYTES + " bytes");
                        }
                        bytes.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    readError.set(e);
                    kill(worker);
                }
            });
            if (!process.waitFor(input.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Entity model timed out after " + input.timeout().toMillis() + "ms (pid=" + process.pid() + ")");
            }
            // A child inheriting stdout must not keep the caller waiting after its parent exits.
            if (!drain.join(Duration.ofSeconds(2))) throw new IOException("Entity worker output did not close");
            if (readError.get() != null) throw readError.get();
            String output = bytes.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("Entity worker failed (exit " + process.exitValue() + "): " + diagnostic(output));
            try {
                JsonObject geometry = JsonParser.parseString(output).getAsJsonObject();
                EntityGeometry.validate(geometry);
                if (!geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                        .getAsJsonObject("description").get("identifier").getAsString().equals(input.geometryId())) {
                    throw new IllegalArgumentException("Worker returned a different geometry identifier");
                }
                return geometry;
            } catch (RuntimeException e) {
                throw new IOException("Invalid entity worker geometry: " + diagnostic(e.toString()), e);
            }
        } finally {
            try {
                if (process != null) {
                    kill(process);
                    process.getInputStream().close();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } finally {
                Files.deleteIfExists(arguments);
            }
        }
    }

    /** Publishes only a complete validated geometry; an existing file survives conversion failure. */
    public Path convertTo(Input input, Path entityDirectory, String relativePath) throws IOException, InterruptedException {
        Path target = PackPaths.resolveSafeRelative(entityDirectory, relativePath);
        JsonObject geometry = convert(input);
        Files.createDirectories(target.getParent());
        target = PackPaths.resolveSafeRelative(entityDirectory, relativePath);
        Path temporary = Files.createTempFile(target.getParent(), ".entity-", ".part");
        try {
            Files.writeString(temporary, geometry.toString());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String classpath(List<Path> paths) {
        return paths.stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(java.io.File.pathSeparator));
    }

    private static String quoteArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String diagnostic(String value) {
        return value.replaceAll("[\\r\\n\\p{Cntrl}]", " ").substring(0, Math.min(value.length(), 1000));
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
