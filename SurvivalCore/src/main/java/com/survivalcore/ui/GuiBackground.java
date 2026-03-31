package com.survivalcore.ui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * GuiBackground — Paladium-style custom inventory backgrounds.
 *
 * Uses the bitmap font technique:
 *   1. A PNG texture is registered as a glyph in survivalcraft:gui font (font/gui.json)
 *   2. Negative-space chars shift the glyph to cover the full inventory panel
 *   3. The glyph character is passed as the Adventure Component inventory title
 *
 * When the textures (PNG files) are in place in the resource pack, GUIs will
 * have a fully custom dark background — identical in style to Paladium's market.
 * Without the PNGs, the title shows as a "missing texture" character but the
 * GUI still functions normally (graceful degradation).
 *
 * Resource pack texture path: Faithless/assets/survivalcraft/textures/gui/
 * See TEXTURE_SPECS.txt in that folder for exact PNG dimensions and design guide.
 *
 * Usage:
 *   Component title = GuiBackground.MARKET.title("§6§l✦ MARCHÉ");
 *   Inventory inv = Bukkit.createInventory(null, 54, title);
 *   // OR with TriumphGUI:
 *   Gui gui = Gui.gui().title(GuiBackground.MARKET.title("§6§l✦ MARCHÉ")).rows(6).create();
 */
public enum GuiBackground {

    // ─── Background definitions ─────────────────────────────────
    // Each entry maps to a glyph char in survivalcraft:gui font (gui.json)
    // and the PNG file listed in TEXTURE_SPECS.txt

    MARKET      ("\uEA00"),   // market_bg.png      — /marché, /ah
    MENU        ("\uEA01"),   // menu_bg.png         — /menu
    QUEST       ("\uEA02"),   // quest_bg.png        — /quetes
    SHOP        ("\uEA03"),   // shop_bg.png         — /shop
    AUCTION     ("\uEA04"),   // auction_bg.png      — /ah sell
    SKILLS      ("\uEA05"),   // skills_bg.png       — /competences
    LEADERBOARD ("\uEA06");   // leaderboard_bg.png  — /classement

    // ─── NegativeSpaceFont shift chars ──────────────────────────
    // Shift = -32 + -8 = -40px: aligns the texture to the leftmost pixel of the GUI panel.
    // Adjust if texture appears offset (try -32 only, or -32-16 = -48).
    private static final String SHIFT = "\uF0FC\uF0F8"; // -32px + -8px = -40px total

    private static final Key FONT_KEY = Key.key("survivalcraft", "gui");

    private final String glyphChar;

    GuiBackground(String glyphChar) {
        this.glyphChar = glyphChar;
    }

    /**
     * Returns an Adventure Component suitable for use as an inventory title.
     * Renders the background PNG behind the inventory slots (requires resource pack).
     *
     * The label is appended AFTER the background so it appears on top.
     * It is rendered in the default Minecraft font (not the gui font).
     *
     * @param label  Display text shown on top of the background (§-codes supported)
     * @return       Component ready to pass to Bukkit.createInventory() or Gui.gui().title()
     */
    public Component title(String label) {
        // Background glyph in survivalcraft:gui font (renders the texture)
        Component background = Component.text(SHIFT + glyphChar)
                .style(Style.style()
                        .font(FONT_KEY)
                        .color(TextColor.color(0xFFFFFF))  // white = no tinting
                        .decoration(TextDecoration.ITALIC, false)
                        .build());

        // Label on top of background in normal Minecraft font
        // Reset font back to default so label text renders correctly
        Component labelComp = Component.text(label)
                .style(Style.style()
                        .font(Key.key("minecraft", "default"))
                        .decoration(TextDecoration.ITALIC, false)
                        .build());

        return background.append(labelComp);
    }

    /**
     * Returns a title with no label text — just the background texture.
     * Useful when the "title" is part of the background PNG itself.
     */
    public Component titleOnly() {
        return Component.text(SHIFT + glyphChar)
                .style(Style.style()
                        .font(FONT_KEY)
                        .color(TextColor.color(0xFFFFFF))
                        .decoration(TextDecoration.ITALIC, false)
                        .build());
    }

    /**
     * Fallback title with no custom background — safe to use before textures are ready.
     * Returns a plain styled Component using standard Minecraft formatting only.
     */
    public static Component plainTitle(String label) {
        return Component.text(label)
                .style(Style.style().decoration(TextDecoration.ITALIC, false).build());
    }
}
