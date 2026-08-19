/*
 * Copyright (c) 2019-2024 GeyserMC. http://geysermc.org
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
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.geysermc.pack.converter.type.model;

import net.kyori.adventure.key.Key;
import org.geysermc.pack.converter.util.DefaultLogListener;
import org.geysermc.pack.converter.util.LogListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Element;
import team.unnamed.creative.model.ItemOverride;
import team.unnamed.creative.model.ItemTransform;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;
import team.unnamed.creative.model.ModelTextures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModelStitcher {
    private final Provider provider;
    private final Model baseModel;
    private final LogListener log;

    private final boolean ambientOcclusion;
    private final Map<ItemTransform.Type, ItemTransform> display = new HashMap<>();

    private final List<ModelTexture> textureLayers = new ArrayList<>();
    private ModelTexture textureParticle;
    private final Map<String, ModelTexture> textureVariables = new HashMap<>();

    private Model.GuiLight guiLight;
    private final List<Element> elements = new ArrayList<>();
    private final List<ItemOverride> overrides = new ArrayList<>();
    private final Set<Key> visitedParents = new HashSet<>();

    public ModelStitcher(@NotNull Provider provider, @NotNull Model baseModel) {
        this(provider, baseModel, new DefaultLogListener());
    }

    public ModelStitcher(@NotNull Provider provider, @NotNull Model baseModel, @NotNull LogListener log) {
        this.provider = provider;
        this.baseModel = baseModel;
        this.log = log;

        this.ambientOcclusion = baseModel.ambientOcclusion();
        this.elements.addAll(baseModel.elements());
        this.overrides.addAll(baseModel.overrides());
        this.guiLight = baseModel.guiLight();

        ModelTextures textures = baseModel.textures();
        if (textures != null) {
            this.textureLayers.addAll(textures.layers());
            this.textureParticle = textures.particle();
            this.textureVariables.putAll(textures.variables());
        }
        this.display.putAll(baseModel.display());

        Key parentKey = baseModel.parent();
        if (parentKey != null) {
            this.visitedParents.add(baseModel.key());
            Model parentModel = provider.model(parentKey);
            if (parentModel == null) {
                log.error("Could not find parent model " + parentKey + " for model " + baseModel.key());
            } else {
                this.inheritTraits(parentModel);
            }
        }
    }

    private void inheritTraits(@NotNull Model model) {
        Key modelKey = model.key();
        if (!this.visitedParents.add(modelKey)) {
            log.warn("Detected circular model parent chain at " + modelKey + " for model " + baseModel.key());
            return;
        }

        // Elements are inherited as a whole. A child model that defines its
        // own elements replaces the parent's element list; only an element-less
        // child reaches this method with an empty list and inherits geometry.
        if (this.elements.isEmpty()) {
            List<Element> elements = model.elements();
            if (elements != null && !elements.isEmpty()) {
                this.elements.addAll(elements);
            }
        }

        List<ItemOverride> overrides = model.overrides();
        if (overrides != null && !overrides.isEmpty() && this.overrides.isEmpty()) {
            this.overrides.addAll(overrides);
        }

        Map<ItemTransform.Type, ItemTransform> display = model.display();
        if (display != null && !display.isEmpty()) {
            for (Map.Entry<ItemTransform.Type, ItemTransform> entry : display.entrySet()) {
                this.display.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        ModelTextures textures = model.textures();
        if (textures != null) {
            List<ModelTexture> layers = textures.layers();
            if (layers != null) {
                // Minecraft texture references are positional: layer0/layer1/etc.
                // The child keeps its own slot; a parent only fills missing slots.
                for (int i = 0; i < layers.size(); i++) {
                    ModelTexture texture = layers.get(i);
                    while (this.textureLayers.size() <= i) {
                        this.textureLayers.add(null);
                    }
                    if (this.textureLayers.get(i) == null) {
                        this.textureLayers.set(i, texture);
                    }
                }
            }

            ModelTexture particle = textures.particle();
            if (particle != null && this.textureParticle == null) {
                this.textureParticle = particle;
            }

            Map<String, ModelTexture> variables = textures.variables();
            if (variables != null) {
                for (Map.Entry<String, ModelTexture> entry : variables.entrySet()) {
                    this.textureVariables.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        Model.GuiLight guiLight = model.guiLight();
        if (guiLight != null && this.guiLight == null) {
            this.guiLight = guiLight;
        }

        Key parentKey = model.parent();
        if (parentKey != null) {
            Model parentModel = this.provider.model(parentKey);
            if (parentModel == null) {
                log.error("Could not find parent model " + parentKey + " for model " + model.key());
                return;
            }
            this.inheritTraits(parentModel);
        }
    }

    public Model stitch() {
        return Model.model()
                .key(this.baseModel.key())
                .ambientOcclusion(this.ambientOcclusion)
                .display(this.display)
                .elements(this.elements)
                .guiLight(this.guiLight)
                .overrides(this.overrides)
                .textures(ModelTextures.builder()
                        .layers(this.textureLayers)
                        .particle(this.textureParticle)
                        .variables(this.textureVariables)
                        .build())
                .build();
    }

    public interface Provider {
        @Nullable
        Model model(@NotNull Key key);
    }

    public static Provider baseProvider(@NotNull ResourcePack pack) {
        return pack::model;
    }

    public static Provider vanillaProvider(@NotNull ResourcePack pack, @NotNull ResourcePack vanillaPack) {
        return key -> {
            Model model = pack.model(key);
            if (model == null) {
                return vanillaPack.model(key);
            }
            return model;
        };
    }
}
