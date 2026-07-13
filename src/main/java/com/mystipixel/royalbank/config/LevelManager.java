package com.mystipixel.royalbank.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LevelManager {
    private final JavaPlugin plugin;
    // Published as an immutable snapshot swapped atomically on reload, so async readers (e.g. the
    // PlaceholderAPI expansion) never observe a half-rebuilt map.
    private volatile Map<Integer, BankLevel> levels = Collections.emptyMap();

    public LevelManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        Map<Integer, BankLevel> newLevels = new LinkedHashMap<>();
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection("levels");
        if (section == null) {
            plugin.getLogger().warning("No levels section found in config.yml.");
            this.levels = Collections.emptyMap();
            return;
        }

        for (String key : section.getKeys(false)) {
            int levelNumber;
            try {
                levelNumber = Integer.parseInt(key);
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Skipping bank level with non-number key: " + key);
                continue;
            }

            ConfigurationSection levelSection = section.getConfigurationSection(key);
            if (levelSection == null) {
                continue;
            }

            List<ItemRequirement> requirements = loadRequirements(levelSection.getConfigurationSection("upgrade-cost"));
            double fallbackInterestPercent = levelSection.getDouble("daily-interest-percent", 0.0);
            double maxBalance = levelSection.getDouble("max-balance", 0.0);
            BankLevel bankLevel = new BankLevel(
                    levelNumber,
                    levelSection.getString("name", "Level " + levelNumber),
                    maxBalance,
                    fallbackInterestPercent,
                    levelSection.getDouble("max-interest", plugin.getConfig().getDouble("settings.max-daily-interest.amount", 50000.0)),
                    levelSection.getDouble("upgrade-cost.money", 0.0),
                    requirements,
                    loadInterestTranches(levelSection.getConfigurationSection("interest-tranches"), fallbackInterestPercent, maxBalance)
            );
            newLevels.put(levelNumber, bankLevel);
        }
        this.levels = Collections.unmodifiableMap(newLevels);
    }

    private List<InterestTranche> loadInterestTranches(ConfigurationSection section, double fallbackInterestPercent, double maxBalance) {
        if (section == null) {
            if (fallbackInterestPercent <= 0.0) {
                return Collections.emptyList();
            }
            // Bound the implicit single tranche by the level's max-balance so the fallback path behaves
            // consistently with explicit tranches (no interest computed on balance the account can't hold).
            double upper = maxBalance > 0.0 ? maxBalance : Double.MAX_VALUE;
            return List.of(new InterestTranche(0.0, upper, fallbackInterestPercent));
        }

        List<InterestTranche> tranches = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection trancheSection = section.getConfigurationSection(key);
            if (trancheSection == null) {
                continue;
            }
            double from = trancheSection.getDouble("from", 0.0);
            double to = trancheSection.getDouble("to", 0.0);
            double percent = trancheSection.getDouble("percent", 0.0);
            if (to <= from || percent <= 0.0) {
                plugin.getLogger().warning("Skipping invalid interest tranche " + key + ": from=" + from + ", to=" + to + ", percent=" + percent);
                continue;
            }
            tranches.add(new InterestTranche(from, to, percent));
        }
        return tranches.stream().sorted(Comparator.comparingDouble(InterestTranche::from)).toList();
    }

    private List<ItemRequirement> loadRequirements(ConfigurationSection upgradeSection) {
        if (upgradeSection == null || !upgradeSection.isList("items")) {
            return Collections.emptyList();
        }

        List<ItemRequirement> requirements = new ArrayList<>();
        List<?> rawItems = upgradeSection.getList("items", Collections.emptyList());
        for (Object rawItem : rawItems) {
            ItemRequirement requirement = parseRequirement(rawItem);
            if (requirement != null) {
                requirements.add(requirement);
            }
        }
        return requirements;
    }

    private ItemRequirement parseRequirement(Object rawItem) {
        if (rawItem instanceof String stringRequirement) {
            return parseNamespacedRequirement(stringRequirement);
        }
        if (!(rawItem instanceof Map<?, ?> itemMap)) {
            plugin.getLogger().warning("Skipping upgrade item with unsupported format: " + rawItem);
            return null;
        }

        Object shorthand = itemMap.containsKey("item") ? itemMap.get("item") : itemMap.get("requirement");
        if (shorthand != null) {
            return parseNamespacedRequirement(String.valueOf(shorthand));
        }

        Object rawType = itemMap.containsKey("type") ? itemMap.get("type") : "VANILLA";
        String typeName = String.valueOf(rawType).toUpperCase();
        int amount = parseAmount(itemMap.get("amount"));
        if (amount <= 0) {
            plugin.getLogger().warning("Skipping upgrade item with invalid amount: " + itemMap);
            return null;
        }

        RequirementType type;
        try {
            type = RequirementType.valueOf(typeName);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Skipping upgrade item with invalid type: " + typeName);
            return null;
        }

        if (type == RequirementType.VANILLA) {
            Material material = Material.matchMaterial(String.valueOf(itemMap.get("material")));
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("Skipping vanilla requirement with invalid material: " + itemMap.get("material"));
                return null;
            }
            return new ItemRequirement(type, material, null, amount);
        }

        Object rawId = itemMap.containsKey("id") ? itemMap.get("id") : "";
        String id = String.valueOf(rawId).trim();
        if (id.isEmpty()) {
            plugin.getLogger().warning("Skipping EcoItems requirement without id.");
            return null;
        }
        return new ItemRequirement(type, null, normalizeCustomItemId(id), amount);
    }

    private ItemRequirement parseNamespacedRequirement(String rawRequirement) {
        String[] pieces = rawRequirement.trim().split(":");
        if (pieces.length != 3) {
            plugin.getLogger().warning("Skipping upgrade item with invalid namespace format. Use <namespace>:<id>:<amount>, got: " + rawRequirement);
            return null;
        }

        String namespace = pieces[0].trim().toLowerCase();
        String id = pieces[1].trim();
        int amount = parseAmount(pieces[2].trim());
        if (namespace.isEmpty() || id.isEmpty() || amount <= 0) {
            plugin.getLogger().warning("Skipping upgrade item with invalid namespace/id/amount: " + rawRequirement);
            return null;
        }

        if (namespace.equals("minecraft") || namespace.equals("vanilla")) {
            Material material = Material.matchMaterial(id);
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("Skipping vanilla requirement with invalid material: " + id);
                return null;
            }
            return new ItemRequirement(RequirementType.VANILLA, material, null, amount);
        }

        return new ItemRequirement(RequirementType.ECOITEMS, null, namespace + ":" + id.toLowerCase(), amount);
    }

    private String normalizeCustomItemId(String id) {
        if (id.contains(":")) {
            return id.toLowerCase();
        }
        return "ecoitems:" + id.toLowerCase();
    }

    private int parseAmount(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    public Optional<BankLevel> getLevel(int level) {
        return Optional.ofNullable(levels.get(level));
    }

    public Optional<BankLevel> getNextLevel(int currentLevel) {
        return getLevel(currentLevel + 1);
    }

    public int getStartingLevel() {
        int configured = plugin.getConfig().getInt("settings.starting-level", 1);
        if (levels.containsKey(configured)) {
            return configured;
        }
        return levels.keySet().stream().min(Comparator.naturalOrder()).orElse(configured);
    }

    public Map<Integer, BankLevel> getLevels() {
        return Collections.unmodifiableMap(levels);
    }

    public List<Integer> getOrderedLevelNumbers() {
        return levels.keySet().stream().sorted(Comparator.naturalOrder()).toList();
    }
}
