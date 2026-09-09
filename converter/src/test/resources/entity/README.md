# Exact geometry golden provenance

`bald-eagle.geo.json` is static model data derived from the user-supplied
`alexsmobs-2.1.11-fabric+26.2.jar`, SHA-256
`a548655daf4b8336cbc43544d5eae6e0883217afbe7a5f62c0587c4b62f2484d`.
Source class: `com.github.alexthe666.alexsmobs.client.model.ModelBaldEagle`.
The JAR metadata credits Alexthe668, Carro1001, Paint_Ninja and Codx.io and
declares LGPL-3.0. Its included license is retained as `LGPL-3.0-only.txt`.
Upstream source: https://github.com/Codx-org/Alexs-Mobs-Updated-Ported.
The binary hash, rather than an inferred source tag, identifies this fixture.

The JSON is transformed data: 20 parts/18 cubes, original vertex UV islands,
hierarchical pivots, local hood scale, signed face UVs and zero-thickness planes.
It was checked numerically against constructor bytecode and the pinned codec
coordinate contract documented in `docs/entity-static-geometry.md`.
It is not a screenshot-approved or Bedrock-runtime-approved result.

Regeneration is opt-in through SuppliedEntityJarTest and writes an `.actual`
file under `converter/build/entity-fixtures`. The test never overwrites this
golden. Changes require deliberate diff review; do not bless failing output
automatically. No model binaries or replacement downloads are bundled here.
