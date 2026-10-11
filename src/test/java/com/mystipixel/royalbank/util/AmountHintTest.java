package com.mystipixel.royalbank.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The custom amount dialog, menu buttons and error message promise what Amounts.parse accepts.
class AmountHintTest {
    private static final Pattern EXAMPLES = Pattern.compile("\\(([^)]*)\\)");

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(plugin.getConfig()).thenReturn(config);
    }

    @Test
    void everyShorthandExampleInTheShippedTextParses() throws IOException {
        YamlConfiguration messages = bundled("messages.yml");
        List<String> texts = new ArrayList<>();
        texts.add(messages.getString("custom-invalid-amount"));
        texts.addAll(messages.getStringList("input.deposit"));
        texts.addAll(messages.getStringList("input.withdraw"));
        texts.addAll(resource("gui/deposit.yml").lines().toList());
        texts.addAll(resource("gui/withdraw.yml").lines().toList());

        int checked = 0;
        for (String text : texts) {
            if (!text.contains("5k")) {
                continue;
            }
            Matcher matcher = EXAMPLES.matcher(text);
            while (matcher.find()) {
                for (String example : matcher.group(1).split(",")) {
                    String amount = example.trim();
                    assertNotNull(Amounts.parse(plugin, amount), "shipped text promises '" + amount + "' works");
                    checked++;
                }
            }
        }
        assertEquals(15, checked, "five texts with three examples each");
    }

    @Test
    void theErrorMessageNoLongerSaysNormalNumbersOnly() throws IOException {
        String message = bundled("messages.yml").getString("custom-invalid-amount");
        assertFalse(message.contains("normal numbers only"), message);
    }

    @Test
    void parserAcceptsPlainNumbersAndShorthandButNotCommas() {
        assertEquals(2_500.0, Amounts.parse(plugin, "2500"));
        assertEquals(5_000.0, Amounts.parse(plugin, "5k"));
        assertEquals(5_000_000.0, Amounts.parse(plugin, "5m"));
        assertEquals(5_000_000_000.0, Amounts.parse(plugin, "5b"));
        assertEquals(1_500.0, Amounts.parse(plugin, "1.5K"));
        assertNull(Amounts.parse(plugin, "1,000"));
    }

    private static YamlConfiguration bundled(String path) throws IOException {
        return YamlConfiguration.loadConfiguration(new java.io.StringReader(resource(path)));
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = AmountHintTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
