plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.mystipixel"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // Optional shared anti-abuse core (soft-dependency). Build ../EconGuard with `mvn install` first.
    compileOnly("com.mystipixel:econguard:1.0.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.bstats:bstats-bukkit:3.2.1")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("RoyalBank")
        // Do NOT relocate org.sqlite: its native library binds JNI symbols to the org.sqlite package,
        // so relocating breaks native loading (UnsatisfiedLinkError). bStats is pure Java and safe to relocate.
        relocate("org.bstats", "com.mystipixel.royalbank.libs.bstats")
    }

    build {
        dependsOn(shadowJar)
    }
}
