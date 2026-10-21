package com.tuserver.ultimatepops;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class MenuListener implements Listener {

    // Mapeo fijo de slots -> modo / ticks. Los TEXTOS son 100% configurables desde menu.yml,
    // pero la logica interna se identifica por posicion, no por el nombre del item.
    private static final int SLOT_INFINITO = 20;
    private static final int SLOT_FULL = 22;
    private static final int SLOT_RANDOM = 24;
    private static final int SLOT_VOLVER = 45;

    private static final int[] SLOTS_TICKS = {19, 21, 22, 23, 25};
    private static final int[] VALORES_TICKS = {5, 10, 15, 20, 25};

    private final UltimatePops plugin;

    public MenuListener(UltimatePops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean isMainMenu = title.equals(plugin.getMainMenuTitle());
        boolean isTicksMenu = title.startsWith(plugin.getTicksMenuTitlePrefix());

        if (!isMainMenu && !isTicksMenu) return;

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (event.getCurrentItem() == null) return;

        int slot = event.getSlot();

        if (isMainMenu) {
            String modo;
            if (slot == SLOT_INFINITO) modo = "infinito";
            else if (slot == SLOT_FULL) modo = "full";
            else if (slot == SLOT_RANDOM) modo = "random";
            else return; // Panel de borde u otro slot sin funcion

            plugin.getSelectedModo().put(player.getUniqueId(), modo);
            player.openInventory(plugin.createTicksMenu(modo));
            return;
        }

        // Menu de ticks
        if (slot == SLOT_VOLVER) {
            player.openInventory(plugin.createMainMenu());
            return;
        }

        Integer ticks = ticksParaSlot(slot);
        if (ticks == null) return;

        String modo = plugin.getSelectedModo().getOrDefault(player.getUniqueId(), "full");
        startPractice(player, modo, ticks);
        player.closeInventory();
    }

    private Integer ticksParaSlot(int slot) {
        for (int i = 0; i < SLOTS_TICKS.length; i++) {
            if (SLOTS_TICKS[i] == slot) return VALORES_TICKS[i];
        }
        return null;
    }

    private void startPractice(Player player, String modo, int ticks) {
        UUID id = player.getUniqueId();

        BukkitTask previous = plugin.getActiveTasks().remove(id);
        if (previous != null) previous.cancel();

        prepararInventarioInicial(player, modo);
        plugin.startSession(id, modo, ticks);
        player.sendMessage(plugin.getMsg("mensajes.practica_iniciada", "modo", plugin.getModoNombre(modo), "ticks", String.valueOf(ticks)));
        player.sendMessage(plugin.getMsg("mensajes.practica_reponete"));

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                BukkitTask t = plugin.getActiveTasks().remove(id);
                if (t != null) t.cancel();
                return;
            }

            switch (modo) {
                case "infinito":
                    asegurarOffhandInfinito(player);
                    break;
                case "random":
                    agregarTotemAleatorio(player);
                    break;
                case "full":
                default:
                    // No se toca nada: el jugador se maneja solo con lo que ya tiene.
                    break;
            }

            player.damage(1000.0);
        }, ticks, ticks);

        plugin.getActiveTasks().put(id, task);
    }

    /** Vacia todo el inventario y lo prepara segun el modo elegido. */
    private void prepararInventarioInicial(Player player, String modo) {
        PlayerInventory inv = player.getInventory();

        inv.clear();
        inv.setHelmet(null);
        inv.setChestplate(null);
        inv.setLeggings(null);
        inv.setBoots(null);
        inv.setItemInOffHand(null);

        switch (modo) {
            case "full":
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, new ItemStack(Material.TOTEM_OF_UNDYING, 1));
                }
                inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
                break;

            case "infinito":
                inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
                break;

            case "random":
                // Arranca vacio: el primer totem le va a aparecer solo en el primer intervalo.
                break;
        }

        equiparArmaduraDeTotems(inv);
    }

    /** Modo Infinito: la offhand siempre tiene un totem, se repone sola sin limite. */
    private void asegurarOffhandInfinito(Player player) {
        PlayerInventory inv = player.getInventory();
        if (inv.getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING) {
            inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        }
    }

    /** Modo Random: cada intervalo, un totem aparece en una posicion al azar (offhand o alguno
     *  de los 36 slots del inventario principal). A veces te salva solo, a veces tenes que
     *  encontrarlo vos y equiparlo a tiempo. */
    private void agregarTotemAleatorio(Player player) {
        PlayerInventory inv = player.getInventory();

        List<Integer> candidatos = new ArrayList<>();
        if (inv.getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING) {
            candidatos.add(-1); // -1 representa la offhand
        }
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                candidatos.add(i);
            }
        }

        if (candidatos.isEmpty()) return; // No hay lugar, no pasa nada este intervalo

        int elegido = candidatos.get(ThreadLocalRandom.current().nextInt(candidatos.size()));
        if (elegido == -1) {
            inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        } else {
            inv.setItem(elegido, new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        }
    }

    private void equiparArmaduraDeTotems(PlayerInventory inv) {
        if (inv.getHelmet() == null || inv.getHelmet().getType() == Material.AIR) {
            inv.setHelmet(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        }
        if (inv.getChestplate() == null || inv.getChestplate().getType() == Material.AIR) {
            inv.setChestplate(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        }
        if (inv.getLeggings() == null || inv.getLeggings().getType() == Material.AIR) {
            inv.setLeggings(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        }
        if (inv.getBoots() == null || inv.getBoots().getType() == Material.AIR) {
            inv.setBoots(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        }
    }
          }
