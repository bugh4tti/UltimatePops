package com.tuserver.ultimatepops;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;

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
