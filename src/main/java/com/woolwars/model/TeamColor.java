package com.woolwars.model;

import java.util.Locale;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;

/**
 * The 16 wool colors. Each one is a potential team; a team exists in play once someone joins it.
 */
public enum TeamColor {
    WHITE("White", Material.WHITE_WOOL, Material.WHITE_STAINED_GLASS, NamedTextColor.WHITE, 0xF9FFFE, BossBar.Color.WHITE),
    ORANGE("Orange", Material.ORANGE_WOOL, Material.ORANGE_STAINED_GLASS, NamedTextColor.GOLD, 0xF9801D, BossBar.Color.YELLOW),
    MAGENTA("Magenta", Material.MAGENTA_WOOL, Material.MAGENTA_STAINED_GLASS, NamedTextColor.LIGHT_PURPLE, 0xC74EBD, BossBar.Color.PINK),
    LIGHT_BLUE("Light Blue", Material.LIGHT_BLUE_WOOL, Material.LIGHT_BLUE_STAINED_GLASS, NamedTextColor.AQUA, 0x3AB3DA, BossBar.Color.BLUE),
    YELLOW("Yellow", Material.YELLOW_WOOL, Material.YELLOW_STAINED_GLASS, NamedTextColor.YELLOW, 0xFED83D, BossBar.Color.YELLOW),
    LIME("Lime", Material.LIME_WOOL, Material.LIME_STAINED_GLASS, NamedTextColor.GREEN, 0x80C71F, BossBar.Color.GREEN),
    PINK("Pink", Material.PINK_WOOL, Material.PINK_STAINED_GLASS, NamedTextColor.LIGHT_PURPLE, 0xF38BAA, BossBar.Color.PINK),
    GRAY("Gray", Material.GRAY_WOOL, Material.GRAY_STAINED_GLASS, NamedTextColor.DARK_GRAY, 0x474F52, BossBar.Color.WHITE),
    LIGHT_GRAY("Light Gray", Material.LIGHT_GRAY_WOOL, Material.LIGHT_GRAY_STAINED_GLASS, NamedTextColor.GRAY, 0x9D9D97, BossBar.Color.WHITE),
    CYAN("Cyan", Material.CYAN_WOOL, Material.CYAN_STAINED_GLASS, NamedTextColor.DARK_AQUA, 0x169C9C, BossBar.Color.BLUE),
    PURPLE("Purple", Material.PURPLE_WOOL, Material.PURPLE_STAINED_GLASS, NamedTextColor.DARK_PURPLE, 0x8932B8, BossBar.Color.PURPLE),
    BLUE("Blue", Material.BLUE_WOOL, Material.BLUE_STAINED_GLASS, NamedTextColor.BLUE, 0x3C44AA, BossBar.Color.BLUE),
    BROWN("Brown", Material.BROWN_WOOL, Material.BROWN_STAINED_GLASS, NamedTextColor.GOLD, 0x835432, BossBar.Color.RED),
    GREEN("Green", Material.GREEN_WOOL, Material.GREEN_STAINED_GLASS, NamedTextColor.DARK_GREEN, 0x5E7C16, BossBar.Color.GREEN),
    RED("Red", Material.RED_WOOL, Material.RED_STAINED_GLASS, NamedTextColor.RED, 0xB02E26, BossBar.Color.RED),
    BLACK("Black", Material.BLACK_WOOL, Material.BLACK_STAINED_GLASS, NamedTextColor.BLACK, 0x1D1D21, BossBar.Color.PURPLE);

    private final String displayName;
    private final Material wool;
    private final Material glass;
    private final NamedTextColor textColor;
    private final Color rgb;
    private final BossBar.Color barColor;

    TeamColor(String displayName, Material wool, Material glass, NamedTextColor textColor, int rgb, BossBar.Color barColor) {
        this.displayName = displayName;
        this.wool = wool;
        this.glass = glass;
        this.textColor = textColor;
        this.rgb = Color.fromRGB(rgb);
        this.barColor = barColor;
    }

    /** Lowercase id used in commands and team names, e.g. "light_blue". */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Name of the vanilla scoreboard team, kept identical to the old datapack ("ww_red"). */
    public String teamName() {
        return "ww_" + id();
    }

    public String displayName() {
        return displayName;
    }

    public Material wool() {
        return wool;
    }

    public Material glass() {
        return glass;
    }

    public NamedTextColor textColor() {
        return textColor;
    }

    public Color rgb() {
        return rgb;
    }

    public BossBar.Color barColor() {
        return barColor;
    }

    /** Bold, colored team name for chat. Black text is unreadable on chat background, so it gets dark gray. */
    public Component label() {
        NamedTextColor color = this == BLACK ? NamedTextColor.DARK_GRAY : textColor;
        return Component.text(displayName, color).decorate(TextDecoration.BOLD);
    }

    public static TeamColor fromWool(Material material) {
        for (TeamColor color : values()) {
            if (color.wool == material) {
                return color;
            }
        }
        return null;
    }

    public static TeamColor fromId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.startsWith("ww_")) {
            normalized = normalized.substring(3);
        }
        for (TeamColor color : values()) {
            if (color.id().equals(normalized)) {
                return color;
            }
        }
        return null;
    }

    public static TeamColor fromTeamName(String teamName) {
        return teamName.startsWith("ww_") ? fromId(teamName) : null;
    }
}
