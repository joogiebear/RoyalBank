package com.mystipixel.royalbank.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every message key the code reads must ship in the bundled messages.yml, since the code has no text
// of its own to fall back on.
class MessageKeysTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    // call shape -> index of the argument that holds the key; file is null for calls checked everywhere
    private record KeyCall(String file, Pattern pattern, int keyArgument) {
    }

    private static final List<KeyCall> KEY_CALLS = List.of(
            new KeyCall(null, Pattern.compile("\\bmessages\\s*\\.\\s*(?:get|lines)\\s*\\("), 0),
            new KeyCall(null, Pattern.compile("\\bmessages\\s*\\.\\s*(?:send|sendLines)\\s*\\("), 1),
            new KeyCall(null, Pattern.compile("\\bOperationResult\\s*\\.\\s*(?:success|fail)\\s*\\("), 0),
            new KeyCall("BankCommand.java", Pattern.compile("(?<![\\w>]\\s)\\bparseAmount\\s*\\("), 2),
            new KeyCall("BankCommand.java", Pattern.compile("(?<![\\w>]\\s)\\bparseAdminAmount\\s*\\("), 3),
            new KeyCall("BankGui.java", Pattern.compile("(?<!boolean\\s)\\brequirePermission\\s*\\("), 3),
            new KeyCall("TextInput.java", Pattern.compile("(?<!String\\s)\\blabel\\s*\\("), 0));

    // parameters that only ever carry a key handed in by one of the calls above
    private static final Set<String> KEY_PARAMETERS = Set.of("usageKey", "messageKey", "key");

    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyKeyReadInCodeShipsInMessagesYml() throws IOException {
        YamlConfiguration shipped = new YamlConfiguration();
        try {
            shipped.loadFromString(TestMessages.resource("messages.yml"));
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IOException(e);
        }

        List<String> problems = new ArrayList<>();
        int keys = 0;
        for (Path file : sources()) {
            String source = Files.readString(file);
            for (KeyCall call : KEY_CALLS) {
                if (call.file() != null && !file.endsWith(call.file())) {
                    continue;
                }
                Matcher m = call.pattern().matcher(source);
                while (m.find()) {
                    String where = file.getFileName() + ":" + lineOf(source, m.start());
                    List<String> args = arguments(source, m.end());
                    if (args.size() <= call.keyArgument()) {
                        problems.add(where + ": no key argument");
                        continue;
                    }
                    String expression = args.get(call.keyArgument());
                    Matcher literal = STRING.matcher(expression);
                    boolean any = false;
                    while (literal.find()) {
                        any = true;
                        keys++;
                        String key = literal.group(1);
                        if (!shipped.contains(key)) {
                            problems.add(where + ": " + key + " is not in messages.yml");
                        }
                    }
                    if (!any && !KEY_PARAMETERS.contains(expression)) {
                        problems.add(where + ": key must be a string literal, not " + expression);
                    }
                }
            }
        }
        assertTrue(keys > 120, "found only " + keys + " message keys; is the scan still matching?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void noTextLeftInMessageCallsOrResults() throws IOException {
        // a colour code in a Java string literal is almost always player-facing text that belongs in config
        Pattern colour = Pattern.compile("\"[^\"\\n]*&[0-9a-fk-or][^\"\\n]*\"");
        List<String> problems = new ArrayList<>();
        for (Path file : sources()) {
            String source = Files.readString(file);
            Matcher m = colour.matcher(source);
            while (m.find()) {
                String line = source.substring(source.lastIndexOf('\n', m.start()) + 1, source.indexOf('\n', m.start()));
                if (line.strip().startsWith("*") || line.strip().startsWith("//")) {
                    continue;
                }
                problems.add(file.getFileName() + ":" + lineOf(source, m.start()) + ": " + m.group());
            }
        }
        assertEquals(List.of(), problems, "colour-coded literals in code");
    }

    @Test
    void bundledYamlHasNoDuplicateKeys() throws IOException {
        for (String file : List.of("messages.yml", "gui/main.yml", "gui/deposit.yml", "gui/withdraw.yml",
                "gui/transactions.yml", "gui/confirm-upgrade.yml")) {
            assertEquals(List.of(), duplicateKeys(TestMessages.resource(file)), "duplicate keys in " + file);
        }
    }

    // YAML keeps only the last of two equal keys, silently dropping the first
    static List<String> duplicateKeys(String yaml) {
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        int listItems = 0;
        for (String line : yaml.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (trimmed.startsWith("-")) {
                // each list entry is its own scope, so slots that all have a lore: key don't clash
                pop(path, indents, indent);
                path.add("[" + listItems++ + "]");
                indents.add(indent);
                trimmed = trimmed.substring(1).strip();
                indent += 2;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0 || trimmed.startsWith("\"")) {
                continue;
            }
            pop(path, indents, indent);
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        return duplicates;
    }

    private static void pop(List<String> path, List<Integer> indents, int indent) {
        while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
            indents.remove(indents.size() - 1);
            path.remove(path.size() - 1);
        }
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("MessageManager.java"))
                    .toList();
        }
    }

    // top-level arguments of the call whose '(' ends just before start
    private static List<String> arguments(String source, int start) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                current.append(source, i, end + 1);
                i = end;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    args.add(current.toString().strip());
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        throw new IllegalStateException("unclosed call");
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }
}
