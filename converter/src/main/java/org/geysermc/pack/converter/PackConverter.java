/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */
package org.geysermc.pack.converter;

import org.apache.commons.io.file.PathUtils;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.converter.pipeline.ConverterPipeline;
import org.geysermc.pack.converter.util.AnimatedTextureConverter;
import org.geysermc.pack.converter.util.ConversionCache;
import org.geysermc.pack.converter.util.ConversionDiagnostics;
import org.geysermc.pack.converter.util.ConversionReport;
import org.geysermc.pack.converter.util.DefaultLogListener;
import org.geysermc.pack.converter.util.GeckoLibAnimationConverter;
import org.geysermc.pack.converter.util.LogListener;
import org.geysermc.pack.converter.util.ModelInheritanceResolver;
import org.geysermc.pack.converter.util.ModelOverrideAnalyzer;
import org.geysermc.pack.converter.util.ModJarExtractor;
import org.geysermc.pack.converter.util.NioDirectoryFileTreeReader;
import org.geysermc.pack.converter.util.ResourceFingerprint;
import org.geysermc.pack.converter.util.ResourceInventory;
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

/** Handles conversion of resource packs and Minecraft mod resource sets. */
public final class PackConverter {
    private Path input;
    private Path output;
    private String packName;
    private Path vanillaPackPath = Paths.get("vanilla-pack.zip");
    private String textureSubdirectory;
    private boolean compressed;
    private boolean enforcePackCheck;
    private boolean useConversionCache = true;
    private BiConsumer<ResourcePack, BedrockResourcePack> postProcessor;
    private final List<ConverterPipeline<?, ?>> converters = new ArrayList<>();
    private Path tmpDir;
    private Path modInputDir;
    private PackageHandler packageHandler = PackageHandler.ZIP;
    private LogListener logListener = new DefaultLogListener();

    @Nullable public String textureSubdirectory() { return textureSubdirectory; }
    public PackConverter input(@NotNull Path input) { return input(input, true); }
    public PackConverter input(@NotNull Path input, boolean compressed) { this.input = input; this.compressed = compressed; return this; }
    public PackConverter output(@NotNull Path output) { this.output = output; return this; }
    public PackConverter packName(@NotNull String packName) { this.packName = packName; return this; }
    public @NotNull String packName() {
        if (packName == null || packName.isBlank()) return input.getFileName().toString().replaceFirst("[.][^.]+$", "");
        return packName;
    }
    public PackConverter vanillaPackPath(@NotNull Path vanillaPackPath) { this.vanillaPackPath = vanillaPackPath; return this; }
    public PackConverter textureSubdirectory(@NotNull String textureSubdirectory) { this.textureSubdirectory = textureSubdirectory; return this; }
    public PackConverter enforcePackCheck(boolean enforcePackCheck) { this.enforcePackCheck = enforcePackCheck; return this; }
    public PackConverter useConversionCache(boolean useConversionCache) { this.useConversionCache = useConversionCache; return this; }
    public PackConverter converter(@NotNull ConverterPipeline<?, ?> converter) { converters.add(converter); return this; }
    public PackConverter converters(@NotNull List<? extends ConverterPipeline<?, ?>> converters) { this.converters.addAll(converters); return this; }
    public PackConverter logListener(@NotNull LogListener logListener) { this.logListener = logListener; return this; }
    public PackConverter packageHandler(@NotNull PackageHandler packageHandler) { this.packageHandler = packageHandler; return this; }
    public PackConverter postProcessor(@NotNull BiConsumer<ResourcePack, BedrockResourcePack> postProcessor) { this.postProcessor = postProcessor; return this; }

    public PackConverter convert() throws IOException {
        if (input == null) throw new NullPointerException("Input cannot be null");
        if (output == null) throw new NullPointerException("Output cannot be null");
        if (vanillaPackPath == null) throw new NullPointerException("Vanilla Pack Path cannot be null");
        if (converters.isEmpty()) throw new IllegalStateException("No converters have been added");

        ImageIO.scanForPlugins();
        VanillaPackProvider.create(vanillaPackPath, logListener);
        Path source = input;
        boolean sourceCompressed = compressed;
        boolean modJar = ModJarExtractor.isModJar(input);
        boolean modDirectory = false;
        Path parent = output.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Output must have a parent directory: " + output);
        modInputDir = parent.resolve(output.getFileName() + "_mod_resources");

        if (modJar) {
            if (Files.exists(modInputDir)) PathUtils.delete(modInputDir);
            Files.createDirectories(modInputDir);
            ModJarExtractor.extract(input, modInputDir);
            source = modInputDir;
            sourceCompressed = false;
            logListener.info("Detected mod JAR; extracting client resources from assets/...");
        } else if (!compressed && ModJarExtractor.isModDirectory(input)) {
            if (Files.exists(modInputDir)) PathUtils.delete(modInputDir);
            Files.createDirectories(modInputDir);
            List<Path> jars = ModJarExtractor.extractAll(input, modInputDir);
            source = modInputDir;
            sourceCompressed = false;
            modDirectory = true;
            logListener.info("Detected mod directory; merged " + jars.size() + " mod JAR resource(s) in deterministic overlay order.");
        }

        final Path effectiveSource = source;
        final boolean effectiveCompressed = sourceCompressed;
        final boolean effectiveModInput = modJar || modDirectory;
        ZipUtils.openFileSystem(effectiveSource, effectiveCompressed, ignored -> {
            if (enforcePackCheck && !effectiveModInput && !Files.exists(effectiveSource.resolve("pack.mcmeta"))) {
                logListener.error("Invalid Java Edition resource pack. No pack.mcmeta found.");
                return;
            }
            tmpDir = parent.resolve(output.getFileName() + "_mcpack");
            Path cacheDir = parent.resolve(output.getFileName() + "_packconverter-cache");
            ResourcePack javaResourcePack = effectiveCompressed
                    ? MinecraftResourcePackReader.minecraft().readFromZipFile(effectiveSource)
                    : MinecraftResourcePackReader.minecraft().read(NioDirectoryFileTreeReader.read(effectiveSource));
            ResourcePack vanillaResourcePack = MinecraftResourcePackReader.minecraft().readFromZipFile(vanillaPackPath);
            BedrockResourcePack bedrockResourcePack = new BedrockResourcePack(tmpDir);
            ConversionDiagnostics diagnostics = new ConversionDiagnostics();

            try {
                String inputFingerprint = ResourceFingerprint.sha256(effectiveSource);
                String vanillaFingerprint = Files.isDirectory(vanillaPackPath)
                        ? ResourceFingerprint.sha256(vanillaPackPath)
                        : ResourceFingerprint.sha256(vanillaPackPath.getParent());
                String context = inputFingerprint + ":" + vanillaFingerprint + ":26.2";
                ConversionCache cache = new ConversionCache(cacheDir);
                boolean outputExists = Files.exists(output) && Files.size(output) > 0;
                Optional<String> cached = useConversionCache ? cache.read() : Optional.empty();
                if (outputExists && cached.isPresent() && cached.get().equals(inputFingerprint)) {
                    logListener.info("Conversion cache hit; input resources are unchanged.");
                    return;
                }
                logListener.info(cached.isPresent() ? "Conversion cache miss; source context changed." : "Conversion cache miss; no valid cache entry.");

                ResourceInventory inventory = ResourceInventory.scan(effectiveSource);
                for (ResourceInventory.Resource resource : inventory.resources()) {
                    diagnostics.warning(resource.relativePath(), "Discovered " + resource.kind().name().toLowerCase(java.util.Locale.ROOT) + " resource");
                }
                logListener.info("Indexed " + inventory.resources().size() + " resources: "
                        + inventory.of(ResourceInventory.Kind.TEXTURE).size() + " textures, "
                        + inventory.of(ResourceInventory.Kind.MODEL).size() + " models, "
                        + inventory.of(ResourceInventory.Kind.BLOCKSTATE).size() + " blockstates, "
                        + inventory.of(ResourceInventory.Kind.ANIMATION).size() + " animations.");

                ModelInheritanceResolver models = ModelInheritanceResolver.scan(effectiveSource);
                long inherited = 0;
                long cycles = 0;
                for (ResourceInventory.Resource resource : inventory.of(ResourceInventory.Kind.MODEL)) {
                    String path = resource.relativePath();
                    String asset = path.substring(path.indexOf("assets/") + 7);
                    int slash = asset.indexOf('/');
                    if (slash < 1 || !asset.contains("/models/")) continue;
                    String namespace = asset.substring(0, slash);
                    String modelPath = asset.substring(asset.indexOf("/models/") + 8, asset.length() - 5);
                    ModelInheritanceResolver.Resolution resolution = models.resolve(namespace + ":" + modelPath);
                    if (resolution.inherited()) inherited++;
                    if (resolution.cycle()) {
                        cycles++;
                        diagnostics.warning(path, "Model parent cycle detected; kept local data as fallback.");
                    } else if (resolution.inherited()) {
                        diagnostics.converted(path, "Resolved Java model parent inheritance.");
                    }
                }
                if (inherited > 0) logListener.info("Resolved parent inheritance for " + inherited + " model(s).");
                if (cycles > 0) logListener.warn("Detected " + cycles + " model parent cycle(s).");

                ModelOverrideAnalyzer.Result overrides = ModelOverrideAnalyzer.scan(effectiveSource, diagnostics);
                if (overrides.overrides() > 0) logListener.info("Indexed " + overrides.overrides() + " model override(s) with " + overrides.predicates() + " predicate value(s).");
                if (overrides.malformed() > 0) logListener.warn("Found " + overrides.malformed() + " malformed model override file(s).");

                int errors = converters.stream().mapToInt(converter -> converter.convert(javaResourcePack,
                        Optional.of(vanillaResourcePack), bedrockResourcePack, packName(), textureSubdirectory, logListener)).sum();
                try {
                    int animated = AnimatedTextureConverter.convert(effectiveSource, tmpDir, bedrockResourcePack, logListener, textureSubdirectory);
                    if (animated > 0) diagnostics.converted("assets/*/*.png.mcmeta", "Converted " + animated + " animated texture(s) to Bedrock flipbooks");
                } catch (IOException exception) {
                    diagnostics.warning("assets/*/*.png.mcmeta", exception.getMessage() == null ? "Animation conversion failed" : exception.getMessage());
                    logListener.error("Failed to process animated textures.", exception);
                    errors++;
                }
                try {
                    int boneAnimations = GeckoLibAnimationConverter.convert(effectiveSource, tmpDir, logListener);
                    if (boneAnimations > 0) diagnostics.converted("animations/*.json", "Converted " + boneAnimations + " bone animation file(s)");
                } catch (IOException exception) {
                    diagnostics.warning("animations/*.json", exception.getMessage() == null ? "Bone animation conversion failed" : exception.getMessage());
                    logListener.error("Failed to process GeckoLib animations.", exception);
                    errors++;
                }
                if (postProcessor != null) postProcessor.accept(javaResourcePack, bedrockResourcePack);
                bedrockResourcePack.export();
                try {
                    ConversionReport.write(tmpDir.resolve("packconverter-report.json"), diagnostics);
                    logListener.info("Wrote conversion diagnostics to packconverter-report.json.");
                } catch (IOException exception) {
                    logListener.warn("Could not write conversion diagnostics: " + exception.getMessage());
                }
                if (errors > 0) logListener.warn("Pack conversion completed with " + errors + " errors!");
                else {
                    try { new ConversionCache(cacheDir).write(context.substring(0, 64)); }
                    catch (IOException exception) { logListener.warn("Could not update conversion cache: " + exception.getMessage()); }
                    logListener.info("Pack conversion completed successfully!");
                }
            } catch (IOException exception) {
                logListener.error("Failed to prepare conversion cache or analyze source resources.", exception);
                throw new RuntimeException(exception);
            }
        });
        return this;
    }

    public PackConverter pack() throws IOException {
        if (tmpDir == null || !Files.exists(tmpDir)) return this;
        logListener.info("Packaging pack...");
        packageHandler.pack(this, tmpDir, output, logListener);
        logListener.info("Packaged pack! Cleaning up...");
        cleanup();
        logListener.info("Pack converted.");
        return this;
    }

    private void cleanup() {
        try {
            if (tmpDir != null) PathUtils.delete(tmpDir);
            if (modInputDir != null) PathUtils.delete(modInputDir);
        } catch (IOException ignored) { }
    }
}
