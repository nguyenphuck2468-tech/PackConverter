# Static entity geometry: explicit opt-in API

This document records the Citadel checkpoint at `6b088bf`. The subsequent, separate [ModelPart reader checkpoint](entity-modelpart-reader.md) adds an explicit layer API; it does not alter the Citadel reader or make its existing default API discover ModelPart inputs.

This change adds `EntityConverter.convert(Input)` and `convertTo(...)`. It is **not** wired into `AssetConverters`, Hydraulic's pack discovery/cache, or Geyser registration. It does not claim a complete entity pipeline. No mechanisms from Hydraulic PR #5 are restored. Block geometry PR #7 is unchanged; entity data never enters `BlockModelBounds`.

## Exact input and execution boundary

The caller supplies the Java executable, converter worker classpath, exact model/dependency/client-runtime classpath, fully qualified model class, geometry identifier and deadline (1 ms to 2 minutes). There is no global mod-directory search, namespace/name heuristic, substitute download, or production mod-name special case. Minecraft 26.2 model bytecode requires a Java 25 worker; the library and ordinary unit tests also support Java 21.

The supported source contract is a public no-argument constructor and `getAllParts()` yielding Citadel-style AdvancedModelBox objects. Fields are resolved through the actual superclass chain, subclass first, because AdvancedModelBox shadows BasicModelPart fields. `childModels` supplies hierarchy; identity maps detect repeated entries, missing children, multiple parents, and cycles. IDs are deterministic `bone_N` values in enumeration order. Source names never become paths or identifiers. Animation bindings to original names are not provided.

All mod class loading, constructors, iteration and reflection execute in a separate JVM. It has a 256 MiB heap cap, 128 MiB metaspace cap, an 8 MiB response cap and a wall-clock deadline. Timeout/output overflow forcibly terminates the OS process and its currently visible descendants. Worker shutdown uses `Runtime.halt` so model-created hooks/non-daemon threads cannot delay normal completion. A synthetic busy-spin constructor test checks the timed-out PID is dead and a simultaneous good conversion still completes. This process boundary contains ordinary hangs and exits; it is not an OS security sandbox for hostile binaries, detached children, native memory or filesystem/network side effects.

The worker returns geometry only. The parent validates the response and expected identifier. `convertTo` uses the same `PackPaths` guard as textures, writes a sibling temporary file, and requires atomic replacement. Unsupported atomic moves fail explicitly. Caller-owned output roots must not be concurrently replaced by an external actor. A failed conversion preserves an existing file and does not publish partial JSON. Exceptions include the reason; callers must report failures, not silently register an old/fallback geometry as success.

## Geometry and UV contract

Every cube is read through its own six quads, vertex positions, normalized vertex UVs and face normals. Part-level texture offsets are never used. UVs are expanded using the actual texture dimensions, with signed per-face `uv_size` preserving mirrored mapping. Rectangle corner checks reject skewed/rotated UVs that the emitted subset cannot represent. Degenerate side faces are omitted, while drawable zero-thickness planes retain a zero size axis. Inflated vertex bounds and positive local cube scale are preserved. Inherited scale, hidden parts, nonzero render offsets and unsupported field layouts fail explicitly.

Java part pivots accumulate through the unrotated hierarchy. The emitted Bedrock pivot is `(javaX, 24-javaY, javaZ)` and rotations are the source XYZ radians converted to degrees. Cubes use actual scaled local vertex extents plus their accumulated pivots. This contract was checked against composition of Blockbench's Java model import/export and Bedrock export at commit `47e633e4a1338f957ee7baa0acbcf54da11e77df`:

- [Java coordinates and rotations](https://github.com/JannisX11/blockbench/blob/47e633e4a1338f957ee7baa0acbcf54da11e77df/js/formats/java/modded_entity.js#L525)
- [Bedrock cube and bone export](https://github.com/JannisX11/blockbench/blob/47e633e4a1338f957ee7baa0acbcf54da11e77df/js/formats/bedrock/bedrock.js#L885)
- [Per-face UV corner convention](https://github.com/JannisX11/blockbench/blob/47e633e4a1338f957ee7baa0acbcf54da11e77df/js/outliner/types/cube.js#L76)

The two intermediate X flips cancel. Face names follow the codec convention (east/west use the reflected editor X normal). Top/bottom UV corners include the Bedrock exporter convention. This is a data/codec check, **not visual verification on a Bedrock client**.

The encoder emits the cube subset of [Bedrock geometry 1.16.0](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/schemasreference/schemas/minecraftschema_geometry_1.16.0?view=minecraft-bedrock-stable). Its built-in validator checks the emitted subset's vector shapes, identifiers, texture dimensions, non-negative sizes, face keys, finite numbers and parent graph. This is not a complete official-schema validator for arbitrary Bedrock geometry. Implementation resource caps are 1,024 parts, depth 128, 16,384 cubes, texture dimensions 16,384 and finite numeric magnitude 1,000,000. These are not claims about Bedrock's universal engine limits. There is no 30-pixel or base-block-overlap restriction; a 40-pixel cube regression covers that separation.

Scales below 0.0001 are explicitly rejected: Citadel clamps its reciprocal scale when descending to children, so treating those values as purely local would change the hierarchy's rendered pose.

## Actual supplied fixtures and remaining gates

**VERIFIED (2026-09-09, local Java 25):** `:converter:test -PtestJavaVersion=25 -PentityFixtureClasspath=... --offline` passed 32 tests, zero failures and zero skips. This includes 12 geometry/process tests, the exact Bald Eagle JAR/golden test, the second mod's explicit-unsupported test, and 18 existing/shared-path tests. Successful conversion coverage is one selected model in one mod, not all mobs in either mod.

The user supplied all five JARs; none is missing now. Their actual metadata identifies:

| JAR | SHA-256 | Relevant dependency metadata |
| --- | --- | --- |
| alexsmobs-2.1.11-fabric+26.2.jar | a548655daf4b8336cbc43544d5eae6e0883217afbe7a5f62c0587c4b62f2484d | MC 26.2, Java >=25, codxlib >=1.6.0, Fabric API >=0.155.2+26.2, Loader >=0.18.4 |
| codxlib-1.6.0-fabric+26.2.jar | d6887454c1b4cab58b66bd4c0433ea0a76452084535686e7b91c7865ae721282 | MC 26.2, Java >=25, Fabric API >=0.155.2+26.2 |
| MutantMonsters-v26.2.2-mc26.2.x-Fabric.jar | 618b07e168abc83ebbdb548f5247eda0cabc90a40fa0e4ed3a587cbf2ce82e71 | MC ~26.2-, PuzzlesLib >=26.2.1, ForgeConfigAPIPort, Fabric API >=0.156.0, Loader >=0.19.0 |
| PuzzlesLib-v26.2.3-mc26.2.x-Fabric.jar | 746d90c6723fccce4fca076e7e87753354abc4670016e1c461120a3306ee9af1 | MC ~26.2-, ForgeConfigAPIPort, Fabric API >=0.156.0, Loader >=0.19.0 |
| ForgeConfigAPIPort-v26.2.1-mc26.2.x-Fabric.jar | 1b4b17fc4150481447201152604688c74726d6631c9a626dd21f2c5e2b3df1ac | MC ~26.2-, Fabric API >=0.152.0, Loader >=0.19.0 |

The accepted Bald Eagle tree has 20 parts and 18 cubes, a 64x32 texture, root pivot `(0,24,0)`, body pivot `(0,-9.3,-2)`, and a constructor-set 1.1 local scale on the hood. The stored golden includes planes, real per-face UVs, positive local scale and hierarchy. Source constructor bytecode and Citadel's render scale/reverse-scale behavior were inspected before accepting it. Golden equality proves deterministic static output, not texture selection or visual fidelity.

MutantCreeperModel in the second supplied mod instead extends Minecraft EntityModel and exposes `createBodyLayer(CubeDeformation)` returning LayerDefinition; its constructor takes ModelPart. No GeckoLib geometry JSON was found in either supplied mod. The second-JAR test currently verifies **explicit rejection of an unsupported layout**, not successful two-mod conversion. A separate LayerDefinition reader is still required; absence of JARs is no longer the blocker.

Run ordinary unit/subset/golden tests with `./gradlew :converter:test`. To run the actual binary tests, use Java 25 test runtime and supply a text file containing the exact model/dependency/Minecraft-client classpath:

```
./gradlew :converter:test -PtestJavaVersion=25 -PentityFixtureClasspath=/absolute/path/classpath.txt
```

The real-JAR suite checks binary hashes, never fetches mods, and is explicitly skipped without the supplied classpath. Normal CI does not possess the user JARs. Its success must not be described as a fresh real-JAR conversion run.

**NOT VERIFIED:** successful conversion of two distinct mods; full official schema validation; headless Fabric server boot with all five JARs; Hydraulic/Geyser entity discovery, texture binding, proxy registration and cache/restart behavior; real Bedrock appearance/reconnect QA. Server startup without invoking this converter would not prove entity support. A success percentage over discovered server mobs is not available.

**NOT IMPLEMENTED:** Java procedural animation conversion, sound event delivery, render-controller generation, proxy selection/overrides, per-mod circuit breaker and the requested per-mob runtime log. No animation, sound or visual conversion claim is made. Manual Bedrock QA remains mandatory before release even if every automated gate later passes.
