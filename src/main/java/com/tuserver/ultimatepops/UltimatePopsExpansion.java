package com.tuserver.ultimatepops;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Placeholders disponibles (uso en DecentHolograms / FancyHolograms / GHolograms,
 * que leen PlaceholderAPI de forma nativa):
 *
 *   %ultimatepops_pop_top_<puesto>_name_<categoria>%        -> nombre del jugador en ese puesto
 *   %ultimatepops_pop_top_<puesto>_countable_<categoria>%   -> cantidad de pops de ese puesto
 *   %ultimatepops_personal_record_<categoria>%              -> record personal del que mira el holograma
 *   %ultimatepops_personal_pops_<categoria>%                -> total historico del que mira el holograma
 *   %ultimatepops_session_pops%                             -> pops de la practica activa ahora mismo
 *   %ultimatepops_session_duration%                         -> duracion (segundos) de la practica activa
 *   %ultimatepops_session_modo%                              -> nombre del modo de la practica activa
 *
 *   <categoria> puede ser: infinito, full, random o global
 */
public class UltimatePopsExpansion extends PlaceholderExpansion {

    private final UltimatePops plugin;

    public UltimatePopsExpansion(UltimatePops plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ultimatepops";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Bughatti";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        String[] tokens = params.split("_");

        // pop_top_<puesto>_name_<categoria>  o  pop_top_<puesto>_countable_<categoria>
        if (tokens.length == 5 && tokens[0].equalsIgnoreCase("pop") && tokens[1].equalsIgnoreCase("top")) {
            int puesto;
            try {
                puesto = Integer.parseInt(tokens[2]);
            } catch (NumberFormatException e) {
                return "";
            }
            String campo = tokens[3];
            String categoria = tokens[4].toLowerCase(Locale.ROOT);

            List<Map.Entry<UUID, Integer>> top = plugin.getTopPoppers(categoria, puesto);

            if (top.size() < puesto) {
                if (campo.equalsIgnoreCase("name")) return "-";
                if (campo.equalsIgnoreCase("countable")) return "0";
                return "";
            }

            Map.Entry<UUID, Integer> entry = top.get(puesto - 1);

            if (campo.equalsIgnoreCase("name")) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(entry.getKey());
                return op.getName() != null ? op.getName() : "Desconocido";
            }
            if (campo.equalsIgnoreCase("countable")) {
                return String.valueOf(entry.getValue());
            }
            return "";
        }

        // personal_record_<categoria>
        if (tokens.length == 3 && tokens[0].equalsIgnoreCase("personal") && tokens[1].equalsIgnoreCase("record")) {
            if (player == null) return "0";
            return String.valueOf(plugin.getRecord(player.getUniqueId(), tokens[2].toLowerCase(Locale.ROOT)));
        }

        // personal_pops_<categoria>
        if (tokens.length == 3 && tokens[0].equalsIgnoreCase("personal") && tokens[1].equalsIgnoreCase("pops")) {
            if (player == null) return "0";
            return String.valueOf(plugin.getPlayerPops(player.getUniqueId(), tokens[2].toLowerCase(Locale.ROOT)));
        }

        // session_pops
        if (params.equalsIgnoreCase("session_pops")) {
            if (player == null) return "0";
            return String.valueOf(plugin.getSessionPops(player.getUniqueId()));
        }

        // session_duration
        if (params.equalsIgnoreCase("session_duration")) {
            if (player == null) return "0";
            return String.format(Locale.US, "%.1f", plugin.getSessionDurationSeconds(player.getUniqueId()));
        }

        // session_modo
        if (params.equalsIgnoreCase("session_modo")) {
            if (player == null) return "-";
            return plugin.getModoNombre(plugin.getSessionModo(player.getUniqueId()));
        }

        return null;
    }
}
