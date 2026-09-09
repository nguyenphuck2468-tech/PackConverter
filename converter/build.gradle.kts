plugins {
    id("io.freefair.lombok") version "8.6"
}

sourceSets {
    main {
        resources {
            srcDir("src/main/java/resources")
        }
    }
}

dependencies {
    testImplementation(project(":bedrock-pack-schema"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    api(project(":pack-schema-api"))
    compileOnly(project(":bedrock-pack-schema")) // Is provided by pack-schema-api for consumers, but not for us during compile time
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("commons-io:commons-io:2.11.0")
    implementation("com.twelvemonkeys.imageio:imageio-tga:3.9.4")
    implementation("com.nukkitx.fastutil:fastutil-int-object-maps:8.5.3")
    api("net.kyori:adventure-api:4.14.0")
    api("net.kyori:adventure-text-serializer-gson:4.14.0")
    api("net.kyori:adventure-text-serializer-legacy:4.14.0")
    api("team.unnamed:creative-api:1.14.4")
    api("team.unnamed:creative-serializer-minecraft:1.14.4")

    compileOnly("com.google.auto.service:auto-service:1.0.1")
    annotationProcessor("com.google.auto.service:auto-service:1.0.1")
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("testJavaVersion").getOrElse("21")))
    })
    providers.gradleProperty("entityFixtureClasspath").orNull?.let {
        systemProperty("entity.fixture.classpath", it)
    }
    // Gradle test workers expose only their bootstrap JAR in java.class.path.
    systemProperty("entity.worker.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
