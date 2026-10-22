package com.tuserver.ultimatepops;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

/**
 * El plugin ya no provoca ninguna muerte real durante la practica (la ronda termina sola,
 * sin dano, cuando el jugador se queda sin un totem listo en la offhand). Este listener es
 * solo una red de seguridad por si el jugador muere por una causa externa (un mob, una caida,
 * otro jugador) mientras estaba practicando: evita que pierda items y corta la practica igual.
 */
public class PracticeDeathListener implements Listener {

    private final UltimatePops plugin;

    public PracticeDeathListener(UltimatePops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();

        if (!plugin.getActiveTasks().containsKey(id)) return; // No estaba en practica

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);

        plugin.finalizarRonda(player, true);
    }
}
