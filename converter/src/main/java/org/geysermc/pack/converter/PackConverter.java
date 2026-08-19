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
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 *  @author GeyserMC
 *  @link https://github.com/GeyserMC/PackConverter
 *
 */

package org.geysermc.pack.converter;

import org.apache.commons.io.file.PathUtils;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.converter.pipeline.ConverterPipeline;
import org.geysermc.pack.converter.util.AnimatedTextureConverter;
import org.geysermc.pack.converter.util.DefaultLogListener;
import org.geysermc.pack.converter.util.GeckoLibAnimationConverter;
import org.geysermc.pack.converter.util.LogListener;
import org.geysermc.pack.converter.util.ModJarExtractor;
import org.geysermc.pack.converter.util.NioDirectoryFileTreeReader;
import org.geysermc.pack.converter.util.VanillaPackProvider;
import org.geysermc.pack.converter.util.ZipUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.serialize.minecraft.MinecraftResourcePackReader;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Handles the conversion of a resource pack or Minecraft mod resource set. */
public final class PackConverter {
    private Path input;
    private Path output;
    private String packName;
    private Path vanillaPackPath = Paths.get("vanilla-pack.zip");
    private String textureSubdirectory;
    private boolean compressed;
    private boolean enforcePackCheck = false;
    private BiConsumer<ResourcePack, BedrockResourcePack> postProcessor;
    private final List<ConverterPipeline<?, ?>> converters = new ArrayList<>();
    private Path tmpDir;
    private Path modInputDir;
    private PackageHandler packageHandler = PackageHandler.ZIP;
    private LogListener logListener = new DefaultLogListener();

    @Nullable
    public String textureSubdirectory() { return this.textureSubdirectory; }
    public PackConverter input(@NotNull Path input) { return this.input(input, true); }
    public PackConverter input(@NotNull Path input, boolean compressed) {
        this.input = input;
        this.compressed = compressed;
        return this;
    }
    public PackConverter output(@NotNull Path output) { this.output = output; return this; }
    public PackConverter packName(@NotNull String packName) { this.packName = packName; return this; }
    public @NotNull String packName() {
        if (packName == null || packName.isBlank()) return input.getFileName().toString().replaceFirst("[.][^.]+$", "");
        return packName;
    }
    public PackConverter vanillaPackPath(@NotNull Path vanillaPackPath) { this.vanillaPackPath = vanillaPackPath; return this; }
    public PackConverter textureSubdirectory(@NotNull String textureSubdirectory) { this.textureSubdirectory = textureSubdirectory; return this; }
    public PackConverter enforcePackCheck(boolean enforcePackCheck) { this.enforcePackCheck = enforcePackCheck; return this; }
    public PackConverter converter(@NotNull ConverterPipeline<?, ?> converter) { this.converters.add(converter); return this; }
    public PackConverter converters(@NotNull List<? extends ConverterPipeline<?, ?>> converters) { this.converters.addAll(converters); return this; }
    public PackConverter logListener(@NotNull LogListener logListener) { this.logListener = logListener; return this; }
    public PackConverter packageHandler(@NotNull PackageHandler packageHandler) { this.packageHandler = packageHandler; return this; }
    public PackConverter postProcessor(@NotNull BiConsumer<ResourcePack, BedrockResourcePack> postProcessor) { this.postProcessor = postProcessor; return this; }

    public PackConverter convert() throws IOException {
        if (this.input == null) throw new NullPointerException("Input cannot be null");
        if (this.output == null) throw new NullPointerException("Output cannot be null");
        if (this.vanillaPackPath == null) throw new NullPointerException("Vanilla Pack Path cannot be null");
        if (this.converters.isEmpty()) throw new IllegalStateException("No converters have been added");

        ImageIO.scanForPlugins();
        VanillaPackProvider.create(this.vanillaPackPath, this.logListener);

        Path source = this.input;
        boolean sourceCompressed = this.compressed;
        boolean modJar = ModJarExtractor.isModJar(this.input);
        boolean modDirectory = false;

        Path parent = this.output.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Output must have a parent directory: " + this.output);
        this.modInputDir = parent.resolve(this.output.getFileName() + "_mod_resources");

        if (modJar) {
            if (Files.exists(this.modInputDir)) PathUtils.delete(this.modInputDir);
            Files.createDirectories(this.modInputDir);
            ModJarExtractor.extract(this.input, this.modInputDir);
            source = this.modInputDir;
            sourceCompressed = false;
            this.logListener.info("Detected mod JAR; extracting client resources from assets/...");
        } else if (!this.compressed && ModJarExtractor.isModDirectory(this.input)) {
            if (Files.exists(this.modInputDir)) PathUtils.delete(this.modInputDir);
            Files.createDirectories(this.modInputDir);
            List<Path> jars = ModJarExtractor.extractAll(this.input, this.modInputDir);
            source = this.modInputDir;
            sourceCompressed = false;
            modDirectory = true;
            this.logListener.info("Detected mod directory; merged " + jars.size() + " mod JAR resource(s) in deterministic overlay order.");
        }

        final Path effectiveSource = source;
        final boolean effectiveCompressed = sourceCompressed;
        final boolean effectiveModInput = modJar || modDirectory;

        ZipUtils.openFileSystem(effectiveSource, effectiveCompressed, input -> {
            if (this.enforcePackCheck && !effectiveModInput && !Files.exists(input.resolve("pack.mcmeta"))) {
                logListener.error("Invalid Java Edition resource pack. No pack.mcmeta found.");
                return;
            }

            this.tmpDir = parent.resolve(this.output.getFileName() + "_mcpack");
            ResourcePack javaResourcePack = effectiveCompressed
                    ? MinecraftResourcePackReader.minecraft().readFromZipFile(effectiveSource)
                    : MinecraftResourcePackReader.minecraft().read(NioDirectoryFileTreeReader.read(effectiveSource));
            ResourcePack vanillaResourcePack = MinecraftResourcePackReader.minecraft().readFromZipFile(vanillaPackPath);
            BedrockResourcePack bedrockResourcePack = new BedrockResourcePack(this.tmpDir);

            int errors = converters.stream()
                    .mapToInt(converter -> converter.convert(javaResourcePack, Optional.of(vanillaResourcePack),
                            bedrockResourcePack, packName(), textureSubdirectory, logListener))
                    .sum();

            try {
                int animated = AnimatedTextureConverter.convert(effectiveSource, this.tmpDir,
                        bedrockResourcePack, logListener, textureSubdirectory);
                if (animated > 0) logListener.info("Animated resource conversion added " + animated + " flipbook(s).");
            } catch (IOException exception) {
                logListener.error("Failed to process animated textures.", exception);
                errors++;
            }

            // GeckoLib keeps model animations in ordinary JSON files. Convert the
            // data-only bone channels into Bedrock actor animations without loading
            // the mod or GeckoLib itself.
            try {
                int boneAnimations = GeckoLibAnimationConverter.convert(effectiveSource, this.tmpDir, logListener);
                if (boneAnimations > 0) logListener.info("Bone animation conversion added " + boneAnimations + " Bedrock animation file(s).");
            } catch (IOException exception) {
                logListener.error("Failed to process GeckoLib animations.", exception);
                errors++;
            }

            if (this.postProcessor != null) this.postProcessor.accept(javaResourcePack, bedrockResourcePack);
            bedrockResourcePack.export();

            if (errors > 0) this.logListener.warn("Pack conversion completed with " + errors + " errors!");
            else this.logListener.info("Pack conversion completed successfully!");
        });
        return this;
    }

    public PackConverter pack() throws IOException {
        if (tmpDir == null || !Files.exists(tmpDir)) return this;
        this.logListener.info("Packaging pack...");
        this.packageHandler.pack(this, tmpDir, output, logListener);
        this.logListener.info("Packaged pack! Cleaning up...");
        cleanup();
        this.logListener.info("Pack converted.");
        return this;
    }

    private void cleanup() {
        try {
            if (tmpDir != null) PathUtils.delete(tmpDir);
            if (modInputDir != null) PathUtils.delete(modInputDir);
        } catch (IOException ignored) {
        }
    }
}
