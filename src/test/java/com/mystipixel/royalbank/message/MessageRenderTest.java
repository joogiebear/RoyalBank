package com.mystipixel.royalbank.message;

import com.mystipixel.royalbank.service.OperationResult;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// The bundled text must render exactly what the code used to build by concatenation. The expected
// values below are the old Java expressions with sample values filled in.
class MessageRenderTest {

    private static final String MONEY = "$1,234.50";
    private static final String OTHER = "$10.00";

    @TempDir
    Path dataFolder;

    @Test
    void resultMessagesMatchThePreviousHardCodedText() {
        MessageManager messages = TestMessages.bundled(dataFolder);

        assertEquals("&cAmount must be at least " + MONEY + ".",
                messages.get("amount.too-small", "minimum", MONEY));
        assertEquals("&aDeposited " + MONEY + " &aand received a first deposit bonus of &e" + OTHER + "&a!",
                messages.get("deposit.success-bonus", "amount", MONEY, "bonus", OTHER));
        assertEquals("&cDeposit failed: Insufficient funds",
                messages.get("deposit.failed", "reason", "Insufficient funds"));
        assertEquals("&aWithdrew " + MONEY + " &afrom your bank.",
                messages.get("withdraw.success", "amount", MONEY));
        assertEquals("&cBank transfers unlock after &f" + "5" + "h&c of playtime.",
                messages.get("transfer.sender-playtime", "hours", "5"));
        assertEquals("&c" + "That player" + " hasn't played &f" + "5" + "h&c yet, so they can't receive bank transfers.",
                messages.get("transfer.recipient-playtime", "player", messages.get("transfer.unknown-recipient"), "hours", "5"));
        assertEquals("&c" + "Steve" + "'s bank cannot hold that much - they only have " + MONEY + " of space.",
                messages.get("transfer.recipient-full", "player", "Steve", "space", MONEY));
        assertEquals("&aTransferred " + MONEY + " &ato " + "Steve" + "&a's bank.",
                messages.get("transfer.success", "amount", MONEY, "player", "Steve"));
        assertEquals("&cBank upgrades unlock once your purse and bank have at least " + MONEY + " &ccombined.",
                messages.get("upgrade.locked", "required", MONEY));
        assertEquals("&cYou need " + MONEY + " &cto upgrade.",
                messages.get("upgrade.not-enough-money", "cost", MONEY));
        assertEquals("&cYou need " + MONEY + " to upgrade.",
                messages.get("account.upgrade.not-enough-money", "cost", MONEY));
        assertEquals("&aYour bank was upgraded to &e" + "Gold Account" + "&a!",
                messages.get("upgrade.success", "level_name", "Gold Account"));
        assertEquals("&aDaily interest paid to your wallet: &e" + MONEY,
                messages.get("interest.paid-to-purse", "amount", MONEY));
        assertEquals("&aSet " + "Steve" + "'s bank level to &e" + 2 + " - " + "Gold Account" + "&a."
                        + " &7(Balance reduced by " + OTHER + " to fit the new level cap.)",
                messages.get("admin.result.set-level-capped", "player", "Steve", "level", "2",
                        "level_name", "Gold Account", "discarded", OTHER));
        assertEquals("&aAdded &e" + MONEY + " &ato " + "Steve" + "'s bank."
                        + " &7(" + OTHER + " could not fit under the level cap and was discarded.)",
                messages.get("admin.result.add-balance-capped", "player", "Steve", "amount", MONEY, "discarded", OTHER));
    }

    @Test
    void formatsMatchThePreviousHardCodedText() {
        MessageManager messages = TestMessages.bundled(dataFolder);

        assertEquals("&eYou can claim interest again in " + 3 + "h " + 7 + "m" + ".",
                messages.get("interest.cooldown", "time",
                        messages.get("format.duration-hours", "hours", "3", "minutes", "7")));
        assertEquals(42 + "m", messages.get("format.duration-minutes", "minutes", "42"));
        String item = messages.get("format.item-cost", "amount", "64", "item", "Gold Block");
        assertEquals(64 + "x " + "Gold Block", item);
        assertEquals("&cMissing upgrade items: &f" + String.join(", ",
                        List.of("64x Gold Block" + " (have " + 3 + ")", "1x Core" + " (have " + 0 + ")")),
                messages.get("upgrade.missing-items", "items", String.join(messages.get("format.item-separator"),
                        messages.get("format.item-have", "item", item, "count", "3"),
                        messages.get("format.item-have", "item", "1x Core", "count", "0"))));
    }

    @Test
    void multiLineInfoMatchesThePreviousHardCodedText() {
        MessageManager messages = TestMessages.bundled(dataFolder);

        String before = "&eNext Level: &f" + 2 + " - " + "Gold Account"
                + "\n&eMoney Cost: &f" + MONEY
                + "\n&eItem Cost: &f" + "None"
                + "\n&eMax Balance: &f" + OTHER
                + "\n&eMax Interest: &f" + OTHER;
        assertEquals(List.of(before.split("\n")), messages.lines("info.next-upgrade",
                "level", "2", "level_name", "Gold Account", "money_cost", MONEY,
                "item_cost", messages.get("format.no-items"), "max_balance", OTHER, "max_interest", OTHER));

        String locked = "&cBank Upgrades Locked"
                + "\n&7Requires bank + purse total: &f" + MONEY
                + "\n&7Current combined total: &f" + OTHER;
        assertEquals(List.of(locked.split("\n")),
                messages.lines("info.upgrades-locked", "required", MONEY, "combined", OTHER));
    }

    @Test
    void adminAndAlertLinesMatchThePreviousHardCodedText() {
        MessageManager messages = TestMessages.bundled(dataFolder);

        assertEquals("&7#" + 12 + " &f" + "DEPOSIT" + " &e" + MONEY + " &7after &f" + OTHER + " &8- &7" + "Purse to bank",
                messages.get("admin.transactions-line", "id", "12", "type", "DEPOSIT", "amount", MONEY,
                        "balance_after", OTHER, "note", messages.get("notes.deposit")));
        String reason = messages.get("alerts.growth-reason", "amount", MONEY, "minutes", "10");
        assertEquals("Gained " + MONEY + " in bank within " + 10 + "m", reason);
        assertEquals("&4[Anti-Abuse] FLAGGED &e" + "Steve" + " &7" + reason + ". Review with &f/bank admin flags",
                messages.get("alerts.flagged", "player", "Steve", "reason", reason));
        assertEquals("&c[RoyalBank] &e" + 2 + " anti-abuse flag(s) pending review. &7Use &f/bank admin flags",
                messages.get("alerts.pending-flags", "count", "2"));
        assertEquals("Admin set level to " + "Gold Account" + " (balance capped, discarded " + OTHER + ")",
                messages.get("notes.admin-set-level-capped", "level_name", "Gold Account", "discarded", OTHER));
    }

    // a server that installed RoyalBank before this change has a messages.yml with only the old keys
    @Test
    void oldServerFileFallsBackToTheBundledText() throws IOException {
        Files.writeString(dataFolder.resolve("messages.yml"), """
                prefix: "&6&lRoyalBank &8» &r"
                custom-cancelled: "&eCancelled, my wording."
                """);
        MessageManager messages = TestMessages.load(dataFolder, TestMessages.bundledConfig());

        assertEquals("&eCancelled, my wording.", messages.get("custom-cancelled"));
        assertEquals("&aDeposited " + MONEY + " &ainto your bank.", messages.get("deposit.success", "amount", MONEY));
        assertEquals("&cYou do not have permission to transfer money.", messages.get("permission.transfer"));
        assertEquals(9, messages.lines("admin.usage-lines").size());
    }

    // before the bundled defaults, a missing prefix came from config.yml's settings.messages-prefix
    @Test
    void prefixMissingFromServerFileStillComesFromConfig() throws IOException {
        Files.writeString(dataFolder.resolve("messages.yml"), "no-permission: \"&cNo.\"\n");
        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.messages-prefix", "&b[Bank] ");
        assertEquals("&b[Bank] ", TestMessages.load(dataFolder, config).prefix());
        assertEquals("&6&lRoyalBank &8» &r", TestMessages.bundled(dataFolder).prefix());
    }

    @Test
    void sendResultPrefixesAndColours() {
        MessageManager messages = TestMessages.bundled(dataFolder);
        CommandSender sender = mock(CommandSender.class);
        messages.sendResult(sender, OperationResult.fail("withdraw.not-enough-money"));
        verify(sender).sendMessage("§6§lRoyalBank §8» §r§cYour bank does not have that much money.");

        CommandSender quiet = mock(CommandSender.class);
        messages.sendResult(quiet, OperationResult.silent());
        verifyNoInteractions(quiet);
    }
}
