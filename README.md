# RoyalBank

RoyalBank is a Paper 1.21+ Vault-backed bank plugin with upgradeable account tiers, configurable GUI menus, SQLite storage, daily interest, transaction history, optional EcoItems-style upgrade costs, optional PlaceholderAPI placeholders, and bStats.

## Requirements

- Paper 1.21+ server
- Java 21
- Vault
- A Vault-compatible economy plugin
- Optional: EcoItems or another PDC-tagged custom item plugin
- Optional: PlaceholderAPI

## Install

1. Build or download `RoyalBank.jar`.
2. Put the shaded jar in your server `plugins/` folder.
3. Do not install `original-RoyalBank.jar`.
4. Restart the server.
5. Edit generated files under `plugins/RoyalBank/`.
6. Run `/bank reload` after config/message/GUI edits.

## Commands

Player commands:

```text
/bank
/bank balance
/bank deposit <amount>
/bank withdraw <amount>
/bank upgrade
/bank interest
/bank info
```

Admin commands:

```text
/bank reload
/bank admin balance <player>
/bank admin setbalance <player> <amount>
/bank admin addbalance <player> <amount>
/bank admin removebalance <player> <amount>
/bank admin setlevel <player> <level>
/bank admin reset <player>
/bank admin transactions <player>
/bank admin backup
/bank admin flags [clear]
```

## Permissions

```text
royalbank.use       default: true
royalbank.deposit   default: true
royalbank.withdraw  default: true
royalbank.upgrade   default: true
royalbank.admin     default: op
royalbank.alerts    default: op
```

GUI actions enforce the same deposit/withdraw/upgrade permissions as commands.

## Configuration files

Generated files:

```text
plugins/RoyalBank/config.yml
plugins/RoyalBank/messages.yml
plugins/RoyalBank/gui/main.yml
plugins/RoyalBank/gui/deposit.yml
plugins/RoyalBank/gui/withdraw.yml
plugins/RoyalBank/gui/transactions.yml
plugins/RoyalBank/gui/confirm-upgrade.yml
```

GUI files support configurable title, rows, filler item, item slots, materials, names, lore, actions, and sounds.

## Upgrade item requirements

Preferred shorthand format:

```yaml
upgrade-cost:
  money: 25000000.0
  items:
    - "minecraft:GOLD_BLOCK:64"
    - "ecoitems:enchanted_gold_block:20"
    - "ecoitems:bank_upgrade_core:1"
```

Vanilla requirements reject known custom/PDC-tagged EcoItems so a custom gold-block item does not satisfy `minecraft:GOLD_BLOCK`.

## PlaceholderAPI

If PlaceholderAPI is installed, RoyalBank registers these placeholders:

```text
%royalbank_balance%
%royalbank_balance_raw%
%royalbank_level%
%royalbank_level_name%
%royalbank_max_balance%
%royalbank_max_balance_raw%
%royalbank_bank_space%
%royalbank_bank_space_raw%
%royalbank_next_interest%
%royalbank_next_interest_raw%
%royalbank_interest_time%
%royalbank_max_interest%
%royalbank_combined_balance%
```

## Interest & bonuses

- Interest is **claim-driven**, not paid by a background timer. Players receive it on join (if
  `settings.interest-on-join` is true) and via `/bank interest` or the GUI button, subject to
  `settings.interest-cooldown-hours`. The cooldown is only consumed when interest is actually paid,
  so an empty or full bank does not "waste" a claim.
- `settings.max-daily-interest.amount` caps each claim; the cooldown governs how often a claim can
  happen. Set the amount negative for no global cap. A per-level `max-interest` of `0` disables
  interest for that tier; a negative value means no per-level cap.
- The **first-deposit bonus is paid once per account**, tracked persistently. Emptying and
  re-depositing no longer re-grants it. (An admin `/bank admin reset` makes a fresh account eligible
  again.)

## Backups

Use:

```text
/bank admin backup
```

Backups are written to:

```text
plugins/RoyalBank/backups/
```

The backup command uses SQLite `VACUUM INTO` instead of copying the live database file directly, and
runs off the main thread so large databases do not stall the server.

## Anti-abuse / RMT detection

RoyalBank watches money moving in and out of the bank to surface laundering and real-money-trading
(RMT) — e.g. a brand-new account suddenly parking a fortune. It is a **detection layer**: it cannot
see purse-to-purse `/pay` transfers (limit those in your economy plugin), but large deposits and rapid
balance growth are strong signals. Configure under `anti-abuse` in `config.yml`.

- **Large-transaction alerts** — a warning fires when a single deposit/withdraw exceeds
  `transaction-threshold`, or when a balance first crosses `balance-threshold`.
- **Balance-velocity flagging** — accounts that gain more than `growth-threshold` within
  `window-minutes` are flagged for review.
- **Where alerts go** — the server console, online players with `royalbank.alerts`, and an optional
  `discord-webhook`. Staff with `royalbank.alerts` are also told how many flags are pending when they
  join.
- **Review flags** — `/bank admin flags` lists flagged accounts; `/bank admin flags clear` resets them.
  Combine with `/bank admin transactions <player>` to inspect a suspect's history before acting.

This limits the bank as a safe "vault" for laundered money and gives you an audit trail for TOS
enforcement (ban + rollback). To cap the damage of RMT outright, also set a maximum on your currency
and add friction (limits/cooldown/tax) to player-to-player transfers in your economy plugin.

## Reliability

- Deposits, withdrawals, upgrades, and interest move money through Vault and persist the balance in a
  single SQLite transaction. If the database write fails, the Vault side is rolled back so money is
  neither created nor destroyed.
- Online players' accounts are cached in memory (loaded on join, evicted on quit); GUI menus and
  PlaceholderAPI read from the cache instead of querying the database.
- The database uses WAL mode with a busy timeout. Transaction pruning and backups run asynchronously
  on dedicated connections.
- Existing databases are migrated automatically on startup (a `bonus_claimed` column is added).

## Production notes

- Keep `transactions.max-per-player` at a reasonable number to prevent unlimited transaction table growth.
- Use `/bank admin backup` before major config edits or plugin updates.
- Test EcoItems upgrade requirements on a staging server before using them on live economy tiers.
- Admin commands (`setbalance`, `setlevel`, etc.) only act on players who have joined the server
  before; a typo'd name is rejected rather than creating a ghost account.
- All player-facing strings are configurable in `messages.yml`.
- If a GUI item does nothing, check its `action:` value and server console config warnings.
