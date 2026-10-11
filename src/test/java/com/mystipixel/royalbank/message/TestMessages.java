package com.mystipixel.royalbank.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// A MessageManager loaded the way the plugin loads it: the server's messages.yml in dataFolder (or none),
// backed by the bundled copy from the test classpath.
public final class TestMessages {

    private TestMessages() {
    }

    public static MessageManager load(Path dataFolder, YamlConfiguration config) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getResource(anyString())).thenAnswer(i -> TestMessages.class.getClassLoader()
                .getResourceAsStream(i.getArgument(0, String.class)));
        return new MessageManager(plugin);
    }

    // a server copy identical to the bundled file
    public static MessageManager bundled(Path dataFolder) {
        try {
            Files.writeString(dataFolder.resolve("messages.yml"), resource("messages.yml"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return load(dataFolder, bundledConfig());
    }

    public static YamlConfiguration bundledConfig() {
        try (InputStream in = TestMessages.class.getClassLoader().getResourceAsStream("config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static String resource(String path) throws IOException {
        try (InputStream in = TestMessages.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " is not bundled");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
