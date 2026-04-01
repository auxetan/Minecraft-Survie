package com.survivalcore.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * GuiBackground — inventory title helpers for Palladium-style GUIs.
 *
 * Currently uses plain styled titles (no custom font dependency).
 * When custom PNG textures are designed and added to the resource pack
 * (Faithless/assets/survivalcraft/textures/gui/), the bitmap-font background
 * technique can be re-enabled:
 *   - Register PNGs as glyphs in assets/survivalcraft/font/gui.json
 *   - Use Key.key("survivalcraft","gui") font + negative-space shift chars
 *   - See gui.json for the space-font definitions (uF001–uF0FF)
 *
 * Usage:
 *   Gui gui = Gui.gui().title(GuiBackground.MENU.title("§8✦ §bSurvival Menu §8✦")).rows(6).create();
 */
public enum GuiBackground {

    MARKET,
    MENU,
    QUEST,
    SHOP,
    AUCTION,
    SKILLS,
    LEADERBOARD;

    /**
     * Returns an Adventure Component suitable for use as an inventory title.
     *
     * @param label  Display text (§-codes supported)
     * @return       Component ready to pass to Gui.gui().title()
     */
    public Component title(String label) {
        return plainTitle(label);
    }

    /**
     * Returns a title Component with no label text.
     */
    public Component titleOnly() {
        return plainTitle("");
    }

    /**
     * Plain styled Component using standard Minecraft formatting.
     * Safe to use regardless of resource pack state.
     */
    public static Component plainTitle(String label) {
        return Component.text(label)
                .style(Style.style().decoration(TextDecoration.ITALIC, false).build());
    }
}
