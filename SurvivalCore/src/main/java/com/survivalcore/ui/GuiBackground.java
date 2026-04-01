package com.survivalcore.ui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * GuiBackground — inventory title helpers for Palladium-style GUIs.
 *
 * Each enum value maps to a bitmap glyph in the survivalcraft:gui font.
 * The title Component uses negative-space shift chars (\uF0FC\uF0F8 = -40px)
 * to position the background PNG at the left edge of the inventory, then
 * appends the label in the default Minecraft font on top.
 *
 * PNG file mapping (assets/survivalcraft/textures/gui/):
 *   MARKET      → market_bg.png      (\uEA00)
 *   MENU        → menu_bg.png        (\uEA01)
 *   QUEST       → quest_bg.png       (\uEA02)
 *   SHOP        → shop_bg.png        (\uEA03)
 *   AUCTION     → auction_bg.png     (\uEA04)
 *   SKILLS      → skills_bg.png      (\uEA05)
 *   LEADERBOARD → leaderboard_bg.png (\uEA06)
 */
public enum GuiBackground {

    MARKET('\uEA00'),
    MENU('\uEA01'),
    QUEST('\uEA02'),
    SHOP('\uEA03'),
    AUCTION('\uEA04'),
    SKILLS('\uEA05'),
    LEADERBOARD('\uEA06');

    /** Shift the cursor -40px left so the glyph starts at the inventory's left edge. */
    private static final String SHIFT = "\uF0FC\uF0F8";
    private static final Key GUI_FONT = Key.key("survivalcraft", "gui");

    private final char glyph;

    GuiBackground(char glyph) {
        this.glyph = glyph;
    }

    /**
     * Returns a title Component that renders the background PNG behind the given label.
     */
    public Component title(String label) {
        Component bg = Component.text(SHIFT + glyph)
                .font(GUI_FONT)
                .decoration(TextDecoration.ITALIC, false);
        Component labelComp = Component.text(label)
                .font(Key.key("minecraft", "default"))
                .decoration(TextDecoration.ITALIC, false);
        return bg.append(labelComp);
    }

    /**
     * Returns a title Component with only the background PNG, no label text.
     */
    public Component titleOnly() {
        return Component.text(SHIFT + glyph)
                .font(GUI_FONT)
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Plain styled Component — fallback if resource pack is not loaded.
     */
    public static Component plainTitle(String label) {
        return Component.text(label)
                .style(Style.style().decoration(TextDecoration.ITALIC, false).build());
    }
}
