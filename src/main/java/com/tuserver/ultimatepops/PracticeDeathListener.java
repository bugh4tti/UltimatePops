package com.tuserver.ultimatepops;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.UUID;

public class PracticeDeathListener implements Listener {

    private final UltimatePops plugin;

    public PracticeDeathListener(UltimatePops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();

        BukkitTask task = plugin.getActiveTasks().remove(id);
        if (task == null) return; // No estaba en practica, no tocamos nada

        task.cancel();

        // Mientras estaba en practica, no dropea nada al morir...
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);

        // ...pero tampoco se queda con nada: se le vacia todo el inventario, como un /clear.
        limpiarInventario(player.getInventory());

        int pops = plugin.getSessionPops(id);
        String modo = plugin.getSessionModo(id);
        int ticks = plugin.getSessionTicks(id);
        double duracion = plugin.getSessionDurationSeconds(id);

        int recordAnterior = plugin.getRecord(id, modo);
        boolean esRecordNuevo = plugin.updateRecordIfHigher(id, modo, pops);

        plugin.clearSessionPops(id);

        player.sendMessage(plugin.getMsg("derrota.encabezado"));
        player.sendMessage(plugin.getMsg("derrota.titulo"));
        player.sendMessage(plugin.getMsg("derrota.pops", "pops", String.valueOf(pops)));
        player.sendMessage(plugin.getMsg("derrota.modo", "modo", plugin.getModoNombre(modo)));
        player.sendMessage(plugin.getMsg("derrota.ticks", "ticks", String.valueOf(ticks)));
        player.sendMessage(plugin.getMsg("derrota.duracion", "duracion", String.format(Locale.US, "%.1f", duracion)));

        if (esRecordNuevo) {
            player.sendMessage(plugin.getMsg("derrota.record_nuevo"));
            player.sendMessage(plugin.getMsg("derrota.record_detalle",
                    "record_anterior", String.valueOf(recordAnterior),
                    "record_nuevo", String.valueOf(pops)));
        } else {
            player.sendMessage(plugin.getMsg("derrota.record_actual", "record", String.valueOf(recordAnterior)));
        }
    }

    private void limpiarInventario(PlayerInventory inv) {
        inv.clear();
        inv.setHelmet(null);
        inv.setChestplate(null);
        inv.setLeggings(null);
        inv.setBoots(null);
        inv.setItemInOffHand(null);
    }
}
