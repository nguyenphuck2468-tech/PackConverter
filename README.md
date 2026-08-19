# PackConverter

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Discord](https://img.shields.io/discord/613163671870242838.svg?color=%237289da&label=discord)](http://discord.geysermc.org/)

PackConverter is a library for converting Java Edition resource packs to Bedrock Edition.

This is based on the Node.js module ConvertJavaTextureToBedrockApi by ozelot379.

**Please note, this project is still a work in progress and should not be used on production. Expect bugs!**

**This project also does not convert custom items fully, it will only convert the textures, but does not create any Geyser mappings.**

If you are looking for a program capable of creating such custom item mappings, take a look at [Rainbow](https://github.com/GeyserMC/Rainbow/).

## Usage
- Ensure Java is installed, you can use [PaperMC's guide](https://docs.papermc.io/misc/java-install/) on installing Java if you do not have Java installed.
- Download Thunder, the PackConverter GUI, from the Actions tab on GitHub.
- Double-click on the JAR file to open up the UI, then select your Java pack and hit convert!
- **Mod JARs are supported directly:** select a Minecraft mod `.jar` as the input and PackConverter will automatically extract its client resources from `assets/`. Mod code, signatures, and unrelated metadata are ignored.

## CLI Usage
You can also use PackConverter in a CLI, by downloading Thunder (See `Usage`) then running the jar file with some parameters, an example can be seen below:

```bash
java -jar Thunder.jar nogui --input "C:\path\to\pack.zip"
```

A mod JAR can be supplied in the same way:

```bash
java -jar Thunder.jar nogui --input "C:\path\to\mod.jar"
```

You can also enable debug mode by adding `debug` as an additional argument, this also works for the GUI.

## Compiling
1. Clone the repo to your computer
2. Run gradlew build and locate to bootstrap/build folder.
