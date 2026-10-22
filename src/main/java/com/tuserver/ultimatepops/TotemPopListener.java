package com.tuserver.ultimatepops;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;

/**
 * Cuenta los pops de totem REALES (el evento vanilla de resurreccion), para los casos fuera
 * de la practica simulada de UltimatePops: por ejemplo, si un mob o otro jugador le pega de
 * verdad a alguien que tiene un totem equipado (este en practica o no). Los pops simulados
 * dentro de una ronda de practica se cuentan aparte, directamente en MenuListener.
 */
public class TotemPopListener implements Listener {

    private final UltimatePops plugin;

    public TotemPopListener(UltimatePops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        boolean enPractica = plugin.getActiveTasks().containsKey(player.getUniqueId());
        String categoria = enPractica ? plugin.getSessionModo(player.getUniqueId()) : UltimatePops.CATEGORIA_GLOBAL;

        plugin.incrementPop(player.getUniqueId(), categoria);

        if (enPractica) {
            plugin.incrementSessionPop(player.getUniqueId());
        }
    }
}
