package com.mystipixel.royalbank.gui.menu;

import java.util.List;

/**
 * A fixed, hand-placed slot from a menu's {@code slots:} list — its resolved 0-based index, the item
 * to render, its lore, and the left/right-click effect lists.
 *
 * <p>{@code id} is an optional semantic tag (e.g. {@code upgrade}) the renderer can key state off.
 * {@code lockedItem}/{@code lockedLore} are an optional alternate appearance shown when a stateful
 * slot is "locked" (the upgrade button before it is unlocked); null when the slot has no locked state.
 */
public record MenuSlot(int index,
                       String id,
                       ItemSpec item,
                       List<String> lore,
                       ItemSpec lockedItem,
                       List<String> lockedLore,
                       List<MenuEffect> leftClick,
                       List<MenuEffect> rightClick) {
}
