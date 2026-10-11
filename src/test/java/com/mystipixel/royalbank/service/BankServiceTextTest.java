package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.ItemRequirement;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.config.RequirementType;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankDatabase;
import com.mystipixel.royalbank.hooks.VaultHook;
import com.mystipixel.royalbank.message.MessageManager;
import com.mystipixel.royalbank.message.TestMessages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// BankService returns keys; rendered through the bundled messages.yml they must read as before.
class BankServiceTextTest {

    private static final BankLevel STARTER = new BankLevel(1, "Starter Account", 50_000_000.0, 2.0, 250_000.0,
            0.0, List.of(), List.of());
    private static final BankLevel GOLD = new BankLevel(2, "Gold Account", 100_000_000.0, 2.0, 300_000.0,
            5_000_000.0, List.of(new ItemRequirement(RequirementType.VANILLA, Material.GOLD_BLOCK, null, 64)), List.of());

    @TempDir
    Path dataFolder;

    private MockedStatic<Bukkit> bukkit;
    private MessageManager messages;
    private BankDatabase database;
    private Economy economy;
    private Player player;
    private BankService service;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        messages = TestMessages.bundled(dataFolder);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(TestMessages.bundledConfig());
        database = mock(BankDatabase.class);
        LevelManager levels = mock(LevelManager.class);
        when(levels.getLevels()).thenReturn(Map.of(1, STARTER, 2, GOLD));
        when(levels.getLevel(1)).thenReturn(Optional.of(STARTER));
        when(levels.getLevel(2)).thenReturn(Optional.of(GOLD));
        when(levels.getNextLevel(1)).thenReturn(Optional.of(GOLD));
        when(levels.getNextLevel(2)).thenReturn(Optional.empty());
        when(levels.getStartingLevel()).thenReturn(1);
        economy = mock(Economy.class);
        VaultHook vault = mock(VaultHook.class);
        when(vault.getEconomy()).thenReturn(economy);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        service = new BankService(plugin, database, levels, vault, messages);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private void account(double balance, int level) {
        BankAccount account = new BankAccount(player.getUniqueId(), "Steve", balance, level, 0L, false);
        when(database.getOrCreateAccount(player, 1)).thenReturn(account);
    }

    @Test
    void firstDepositReadsAsBeforeAndStoresTheConfiguredNotes() {
        account(0.0, 1);
        when(economy.has(player, 100.0)).thenReturn(true);
        when(economy.withdrawPlayer(player, 100.0))
                .thenReturn(new EconomyResponse(100.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(database.saveAccountWithTransaction(any(), eq("DEPOSIT"), anyDouble(), anyDouble(), eq("Purse to bank")))
                .thenReturn(true);

        OperationResult result = service.deposit(player, 100.0);

        assertTrue(result.success());
        assertEquals("&aDeposited $100.00 &aand received a first deposit bonus of &e$10.00&a!", messages.render(result));
        verify(database).addTransaction(player.getUniqueId(), "BONUS", 10.0, 110.0, "First deposit bonus");
    }

    @Test
    void failuresReadAsBefore() {
        account(50.0, 1);
        assertEquals("&cAmount must be at least $0.01.", messages.render(service.deposit(player, 0.0)));
        assertEquals("&cYour bank does not have that much money.", messages.render(service.withdraw(player, 500.0)));
        assertEquals("&cYou cannot transfer money to yourself.", messages.render(service.transfer(player, player, 5.0)));
        assertEquals("&cManual interest claiming is disabled.", render(false));
    }

    private String render(boolean interestCommandEnabled) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        var config = TestMessages.bundledConfig();
        config.set("settings.interest-command-enabled", interestCommandEnabled);
        when(plugin.getConfig()).thenReturn(config);
        BankService other = new BankService(plugin, database, mock(LevelManager.class), mock(VaultHook.class), messages);
        return messages.render(other.claimInterest(player, true));
    }

    @Test
    void autoClaimWithNothingToPayIsSilent() {
        account(0.0, 1);
        OperationResult result = service.claimInterest(player, false);
        assertTrue(result.success());
        assertNull(result.key());
        assertEquals("&eNo interest was earned because your bank balance is empty.",
                messages.render(service.claimInterest(player, true)));
    }

    @Test
    void sharedAccountApiStillReturnsColouredErrorsOrNull() {
        UUID coop = UUID.randomUUID();
        when(database.getAccount(coop)).thenReturn(Optional.of(
                new BankAccount(coop, "Coop", GOLD.maxBalance(), 2, 0L, false)));
        assertEquals("&cThe bank is full. Upgrade it to store more.", service.accountDeposit(player, coop, "Coop", 10.0));
        assertEquals("&eThe bank is already at the maximum level.", service.accountUpgrade(player, coop, "Coop"));

        when(database.getAccount(coop)).thenReturn(Optional.of(new BankAccount(coop, "Coop", 500.0, 1, 0L, false)));
        when(economy.depositPlayer(player, 100.0))
                .thenReturn(new EconomyResponse(100.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(database.saveAccountWithTransaction(any(), eq("COOP_WITHDRAW"), anyDouble(), anyDouble(), eq("Steve withdrew")))
                .thenReturn(true);
        assertNull(service.accountWithdraw(player, coop, "Coop", 100.0));
        assertEquals("64x Gold Block", service.getUpgradeView(coop, "Coop").itemsText());
    }

    @Test
    void infoLinesReadAsBefore() {
        account(1_000.0, 1);
        when(economy.getBalance(player)).thenReturn(20_000_000.0);

        String before = "&eNext Level: &f" + 2 + " - " + "Gold Account"
                + "\n&eMoney Cost: &f" + "$5,000,000.00"
                + "\n&eItem Cost: &f" + "64x Gold Block"
                + "\n&eMax Balance: &f" + "$100,000,000.00"
                + "\n&eMax Interest: &f" + "$300,000.00";
        assertEquals(List.of(before.split("\n")), service.describeNextUpgrade(player));

        String balance = "&eBank Level: &f" + 1 + " - " + "Starter Account"
                + "\n&eBalance: &f" + "$1,000.00" + " / " + "$50,000,000.00"
                + "\n&eMax Interest: &f" + "$250,000.00"
                + "\n&eNext Interest: &f" + "$0.00";
        assertEquals(List.of(balance.split("\n")), service.describeBalance(player));
        assertEquals("Ready now", service.getInterestTimeRemaining(player));
    }

    @Test
    void missingItemsListTheConfiguredFormat() {
        account(0.0, 1);
        when(economy.getBalance(player)).thenReturn(20_000_000.0);
        when(economy.has(player, 5_000_000.0)).thenReturn(true);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[0]);
        when(player.getInventory()).thenReturn(inventory);

        OperationResult result = service.upgrade(player);

        assertFalse(result.success());
        assertEquals("&cMissing upgrade items: &f" + "64x Gold Block" + " (have " + 0 + ")", messages.render(result));
    }
}
