package com.mystipixel.royalbank.gui.menu;

import java.util.List;

/**
 * A fixed slot from a menu's {@code slots:} list. {@code index} is 0-based. {@code id} is an optional
 * tag (e.g. {@code upgrade}); {@code lockedItem}/{@code lockedLore} are null unless the slot has a
 * locked appearance.
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
