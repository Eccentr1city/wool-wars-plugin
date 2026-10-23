package com.woolwars.util;

import java.time.Duration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Msg {
    private static final Component PREFIX = Component.text("[Wool Wars] ", NamedTextColor.GOLD, TextDecoration.BOLD);

    private Msg() {
    }

    public static Component prefixed(Component body) {
        return Component.empty().append(PREFIX).append(body.colorIfAbsent(NamedTextColor.YELLOW));
    }

    public static void broadcast(Component body) {
        Bukkit.broadcast(prefixed(body));
    }

    public static void info(CommandSender to, String text) {
        to.sendMessage(prefixed(Component.text(text, NamedTextColor.YELLOW)));
    }

    public static void info(CommandSender to, Component body) {
        to.sendMessage(prefixed(body));
    }

    public static void ok(CommandSender to, String text) {
        to.sendMessage(prefixed(Component.text(text, NamedTextColor.GREEN)));
    }

    public static void error(CommandSender to, String text) {
        to.sendMessage(prefixed(Component.text(text, NamedTextColor.RED)));
    }

    public static void titleAll(Component title, Component subtitle, int staySeconds) {
        Title t = Title.title(title, subtitle,
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(staySeconds), Duration.ofMillis(700)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(t);
        }
    }

    public static void soundAll(Sound sound, float volume, float pitch) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    /** m:ss */
    public static String clock(long totalSeconds) {
        long s = Math.max(0, totalSeconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    public static String dimension(org.bukkit.World world) {
        if (world == null) {
            return "unloaded world";
        }
        return switch (world.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }
}
