package com.tuserver.ultimatepops;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class UltimatePops extends JavaPlugin implements CommandExecutor, TabCompleter {

    public static final String[] MODOS = {"infinito", "full", "random"};
    public static final String CATEGORIA_GLOBAL = "global";

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();
    private final Map<UUID, String> selectedModo = new HashMap<>();

    // pops[categoria][uuid] = cantidad total historica
    private final Map<String, Map<UUID, Integer>> popCounts = new HashMap<>();
    // records[categoria][uuid] = mejor cantidad en una sola practica
    private final Map<String, Map<UUID, Integer>> personalRecords = new HashMap<>();

    private final Map<UUID, Integer> sessionPops = new HashMap<>();
    private final Map<UUID, String> sessionModo = new HashMap<>();
    private final Map<UUID, Integer> sessionTicks = new HashMap<>();
    private final Map<UUID, Long> sessionStart = new HashMap<>();

    // Inventario original del jugador antes de entrar a practicar, para devolverselo despues.
    private final Map<UUID, ItemStack[]> savedContents = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new HashMap<>();
    private final Map<UUID, ItemStack> savedOffhand = new HashMap<>();

    private File statsFile;
    private YamlConfiguration statsConfig;
    private File menuFile;
    private YamlConfiguration menuConfig;

    private UltimatePopsExpansion expansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMenuConfig();
        loadStats();

        for (String comando : new String[]{"totem", "up", "upops", "pop", "totempractice", "poppractice", "ultimapops"}) {
            if (getCommand(comando) != null) {
                getCommand(comando).setExecutor(this);
                getCommand(comando).setTabCompleter(this);
            }
        }

        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TotemPopListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PracticeDeathListener(this), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            expansion = new UltimatePopsExpansion(this);
            expansion.register();
            getLogger().info("Expansion de PlaceholderAPI registrada.");
        } else {
            getLogger().info("PlaceholderAPI no esta instalado: los placeholders %ultimatepops_...% no van a funcionar hasta que lo instales.");
        }

        getLogger().info("UltimatePops habilitado.");
    }

    @Override
    public void onDisable() {
        activeTasks.values().forEach(BukkitTask::cancel);
        activeTasks.clear();
    }

    public Map<UUID, String> getSelectedModo() {
        return selectedModo;
    }

    public Map<UUID, BukkitTask> getActiveTasks() {
        return activeTasks;
    }

    // ==================== Colores (soporta &#RRGGBB) ====================

    public String color(String texto) {
        if (texto == null) return "";
        Matcher matcher = HEX_PATTERN.matcher(texto);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            try {
                matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + hex).toString());
            } catch (IllegalArgumentException e) {
                matcher.appendReplacement(buffer, "");
            }
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    // ==================== Guardado / restauracion del inventario original ====================

    /** Guarda el inventario tal cual esta ahora, para poder devolverselo cuando termine la practica. */
    public void saveOriginalInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        UUID id = player.getUniqueId();
        savedContents.put(id, inv.getContents().clone());
        savedArmor.put(id, inv.getArmorContents().clone());
        savedOffhand.put(id, inv.getItemInOffHand().clone());
    }

    /** Devuelve exactamente lo que el jugador tenia antes de empezar a practicar. Los totems que
     *  dio el plugin (y cualquier cosa que haya quedado de la practica) desaparecen. */
    public void restoreOriginalInventory(Player player) {
        UUID id = player.getUniqueId();
        PlayerInventory inv = player.getInventory();

        inv.setContents(savedContents.getOrDefault(id, new ItemStack[36]));
        inv.setArmorContents(savedArmor.getOrDefault(id, new ItemStack[4]));
        inv.setItemInOffHand(savedOffhand.getOrDefault(id, null));

        savedContents.remove(id);
        savedArmor.remove(id);
        savedOffhand.remove(id);
    }

    // ==================== menu.yml ====================

    private void loadMenuConfig() {
        menuFile = new File(getDataFolder(), "menu.yml");
        if (!menuFile.exists()) {
            saveResource("menu.yml", false);
        }
        menuConfig = YamlConfiguration.loadConfiguration(menuFile);
    }

    public String getMainMenuTitle() {
        return color(menuConfig.getString("menu-principal.titulo", "Practica de Totems"));
    }

    public String getModoNombre(String modo) {
        return color(menuConfig.getString("menu-principal.modos." + modo + ".nombre", modo));
    }

    public List<String> getModoLore(String modo) {
        List<String> resultado = new ArrayList<>();
        for (String linea : menuConfig.getStringList("menu-principal.modos." + modo + ".lore")) {
            resultado.add(color(linea));
        }
        return resultado;
    }

    public String getTicksMenuTitlePrefix() {
        String plantilla = menuConfig.getString("menu-ticks.titulo", "Ticks (%modo%)");
        return color(plantilla.split("%modo%")[0]);
    }

    public String getTicksMenuTitle(String modo) {
        String plantilla = menuConfig.getString("menu-ticks.titulo", "Ticks (%modo%)");
        return color(plantilla.replace("%modo%", getModoNombre(modo)));
    }

    public String getTicksItemNombre(int ticks) {
        return color(menuConfig.getString("menu-ticks.item-nombre", "Cada %ticks% ticks").replace("%ticks%", String.valueOf(ticks)));
    }

    public List<String> getTicksItemLore() {
        List<String> resultado = new ArrayList<>();
        for (String linea : menuConfig.getStringList("menu-ticks.item-lore")) {
            resultado.add(color(linea));
        }
        return resultado;
    }

    public String getVolverTexto() {
        return color(menuConfig.getString("menu-ticks.volver", "« Volver"));
    }

    private Material getMaterialDecoracion(String path, Material fallback) {
        String nombre = menuConfig.getString(path, fallback.name());
        try {
            return Material.valueOf(nombre.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public Material getBordeMaterial() {
        return getMaterialDecoracion("decoracion.borde-material", Material.GRAY_STAINED_GLASS_PANE);
    }

    public Material getEsquinasMaterial() {
        return getMaterialDecoracion("decoracion.esquinas-material", Material.BLACK_STAINED_GLASS_PANE);
    }

    public String getInfoNombre() {
        return color(menuConfig.getString("decoracion.info-nombre", "&bTus estadisticas"));
    }

    public List<String> getInfoLore(Player player) {
        UUID id = player.getUniqueId();
        List<String> resultado = new ArrayList<>();
        for (String linea : menuConfig.getStringList("decoracion.info-lore")) {
            linea = linea.replace("%record_infinito%", String.valueOf(getRecord(id, "infinito")));
            linea = linea.replace("%record_full%", String.valueOf(getRecord(id, "full")));
            linea = linea.replace("%record_random%", String.valueOf(getRecord(id, "random")));
            linea = linea.replace("%pops_global%", String.valueOf(getPlayerPops(id, CATEGORIA_GLOBAL)));
            resultado.add(color(linea));
        }
        return resultado;
    }

    // ==================== config.yml (mensajes) ====================

    public String getMsg(String path, String... reemplazos) {
        String texto = getConfig().getString(path, "");
        for (int i = 0; i + 1 < reemplazos.length; i += 2) {
            texto = texto.replace("%" + reemplazos[i] + "%", reemplazos[i + 1]);
        }
        return color(texto);
    }

    public List<String> getMsgList(String path) {
        List<String> lista = new ArrayList<>();
        for (String linea : getConfig().getStringList(path)) {
            lista.add(color(linea));
        }
        return lista;
    }

    // ==================== Estadisticas (por categoria + global) ====================

    private void loadStats() {
        statsFile = new File(getDataFolder(), "popstats.yml");
        if (!statsFile.exists()) {
            getDataFolder().mkdirs();
            try {
                statsFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("No se pudo crear popstats.yml");
            }
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);

        popCounts.clear();
        personalRecords.clear();

        for (String categoria : categoriasConGlobal()) {
            popCounts.put(categoria, new HashMap<>());
            personalRecords.put(categoria, new HashMap<>());

            String basePops = "pops." + categoria;
            if (statsConfig.isConfigurationSection(basePops)) {
                for (String key : statsConfig.getConfigurationSection(basePops).getKeys(false)) {
                    try {
                        popCounts.get(categoria).put(UUID.fromString(key), statsConfig.getInt(basePops + "." + key));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }

            String baseRecords = "records." + categoria;
            if (statsConfig.isConfigurationSection(baseRecords)) {
                for (String key : statsConfig.getConfigurationSection(baseRecords).getKeys(false)) {
                    try {
                        personalRecords.get(categoria).put(UUID.fromString(key), statsConfig.getInt(baseRecords + "." + key));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    private List<String> categoriasConGlobal() {
        List<String> lista = new ArrayList<>(Arrays.asList(MODOS));
        lista.add(CATEGORIA_GLOBAL);
        return lista;
    }

    private void saveStats() {
        try {
            statsConfig.save(statsFile);
        } catch (IOException e) {
            getLogger().warning("No se pudo guardar popstats.yml");
        }
    }

    /** Suma 1 al contador de pops de un jugador, tanto en su categoria (modo) como en el global. */
    public void incrementPop(UUID uuid, String categoria) {
        for (String cat : new String[]{categoria, CATEGORIA_GLOBAL}) {
            Map<UUID, Integer> mapa = popCounts.computeIfAbsent(cat, k -> new HashMap<>());
            mapa.merge(uuid, 1, Integer::sum);
            statsConfig.set("pops." + cat + "." + uuid, mapa.get(uuid));
        }
        saveStats();
    }

    public int getPlayerPops(UUID uuid, String categoria) {
        return popCounts.getOrDefault(categoria, Collections.emptyMap()).getOrDefault(uuid, 0);
    }

    public List<Map.Entry<UUID, Integer>> getTopPoppers(String categoria, int n) {
        return popCounts.getOrDefault(categoria, Collections.emptyMap()).entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(n)
                .collect(Collectors.toList());
    }

    public boolean removePlayerStats(String nick) {
        boolean encontrado = false;
        for (String categoria : categoriasConGlobal()) {
            Map<UUID, Integer> mapa = popCounts.get(categoria);
            if (mapa == null) continue;
            for (UUID uuid : new ArrayList<>(mapa.keySet())) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(nick)) {
                    mapa.remove(uuid);
                    statsConfig.set("pops." + categoria + "." + uuid, null);
                    encontrado = true;
                }
            }
        }
        if (encontrado) saveStats();
        return encontrado;
    }

    public void addPlayerStats(String nick, int cantidad) {
        @SuppressWarnings("deprecation")
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(nick);
        UUID uuid = offlinePlayer.getUniqueId();
        popCounts.computeIfAbsent(CATEGORIA_GLOBAL, k -> new HashMap<>()).put(uuid, cantidad);
        statsConfig.set("pops." + CATEGORIA_GLOBAL + "." + uuid, cantidad);
        saveStats();
    }

    public void clearAllStats() {
        for (String categoria : categoriasConGlobal()) {
            popCounts.put(categoria, new HashMap<>());
            statsConfig.set("pops." + categoria, null);
        }
        saveStats();
    }

    public void reloadAll() {
        loadStats();
        loadMenuConfig();
        reloadConfig();
    }

    // ==================== Record personal ====================

    public int getRecord(UUID uuid, String categoria) {
        return personalRecords.getOrDefault(categoria, Collections.emptyMap()).getOrDefault(uuid, 0);
    }

    /** Si la cantidad supera el record de esa categoria (y del global), los actualiza. Devuelve true si fue record nuevo en su categoria. */
    public boolean updateRecordIfHigher(UUID uuid, String categoria, int pops) {
        boolean nuevo = false;
        for (String cat : new String[]{categoria, CATEGORIA_GLOBAL}) {
            Map<UUID, Integer> mapa = personalRecords.computeIfAbsent(cat, k -> new HashMap<>());
            int actual = mapa.getOrDefault(uuid, 0);
            if (pops > actual) {
                mapa.put(uuid, pops);
                statsConfig.set("records." + cat + "." + uuid, pops);
                if (cat.equals(categoria)) nuevo = true;
            }
        }
        saveStats();
        return nuevo;
    }

    // ==================== Sesion de practica actual ====================

    public void startSession(UUID uuid, String modo, int ticks) {
        sessionPops.put(uuid, 0);
        sessionModo.put(uuid, modo);
        sessionTicks.put(uuid, ticks);
        sessionStart.put(uuid, System.currentTimeMillis());
    }

    public void incrementSessionPop(UUID uuid) {
        sessionPops.merge(uuid, 1, Integer::sum);
    }

    public int getSessionPops(UUID uuid) {
        return sessionPops.getOrDefault(uuid, 0);
    }

    public String getSessionModo(UUID uuid) {
        return sessionModo.getOrDefault(uuid, "full");
    }

    public int getSessionTicks(UUID uuid) {
        return sessionTicks.getOrDefault(uuid, 20);
    }

    public double getSessionDurationSeconds(UUID uuid) {
        long inicio = sessionStart.getOrDefault(uuid, System.currentTimeMillis());
        return (System.currentTimeMillis() - inicio) / 1000.0;
    }

    public void clearSessionPops(UUID uuid) {
        sessionPops.remove(uuid);
        sessionModo.remove(uuid);
        sessionTicks.remove(uuid);
        sessionStart.remove(uuid);
    }

    // ==================== Fin de ronda (sin muerte) ====================

    /** Corta la practica: cancela la tarea, le devuelve al jugador el inventario que tenia antes
     *  (los totems que dio el plugin desaparecen), actualiza records y, si corresponde, manda el
     *  resumen de la ronda. No hay ninguna muerte real de por medio en ningun caso. */
    public void finalizarRonda(Player player, boolean conResumen) {
        UUID id = player.getUniqueId();

        BukkitTask task = activeTasks.remove(id);
        if (task != null) task.cancel();

        int pops = getSessionPops(id);
        String modo = getSessionModo(id);
        int ticks = getSessionTicks(id);
        double duracion = getSessionDurationSeconds(id);

        int recordAnterior = getRecord(id, modo);
        boolean esRecordNuevo = updateRecordIfHigher(id, modo, pops);

        restoreOriginalInventory(player);
        clearSessionPops(id);

        if (conResumen) {
            player.sendMessage(getMsg("resumen.encabezado"));
            player.sendMessage(getMsg("resumen.titulo"));
            player.sendMessage(getMsg("resumen.pops", "pops", String.valueOf(pops)));
            player.sendMessage(getMsg("resumen.modo", "modo", getModoNombre(modo)));
            player.sendMessage(getMsg("resumen.ticks", "ticks", String.valueOf(ticks)));
            player.sendMessage(getMsg("resumen.duracion", "duracion", String.format(Locale.US, "%.1f", duracion)));

            if (esRecordNuevo) {
                player.sendMessage(getMsg("resumen.record_nuevo"));
                player.sendMessage(getMsg("resumen.record_detalle",
                        "record_anterior", String.valueOf(recordAnterior),
                        "record_nuevo", String.valueOf(pops)));
            } else {
                player.sendMessage(getMsg("resumen.record_actual", "record", String.valueOf(recordAnterior)));
            }
        } else {
            player.sendMessage(getMsg("mensajes.practica_detenida"));
        }
    }

    // ==================== Comando ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMsg("mensajes.solo_jugadores"));
            return true;
        }
        Player player = (Player) sender;

        if (args.length > 0) {
            String sub = args[0].toLowerCase();
            boolean esSubcomandoAdmin = sub.equals("clear") || sub.equals("reload")
                    || sub.equals("removeplayer") || sub.equals("rmpl")
                    || sub.equals("addplayer") || sub.equals("addpl");

            if (esSubcomandoAdmin && !player.hasPermission("ultimatepops.admin")) {
                player.sendMessage(getMsg("mensajes.sin_permiso"));
                return true;
            }

            if (sub.equals("help")) {
                for (String linea : getMsgList("ayuda.lineas")) {
                    player.sendMessage(linea);
                }
                return true;
            }

            if (sub.equals("stop")) {
                if (activeTasks.containsKey(player.getUniqueId())) {
                    finalizarRonda(player, false);
                } else {
                    player.sendMessage(getMsg("mensajes.practica_no_activa"));
                }
                return true;
            }

            if (sub.equals("reload")) {
                reloadAll();
                player.sendMessage(getMsg("mensajes.reload_ok"));
                return true;
            }

            if (sub.equals("clear")) {
                clearAllStats();
                player.sendMessage(getMsg("mensajes.stats_clear_ok"));
                return true;
            }

            if (sub.equals("removeplayer") || sub.equals("rmpl")) {
                if (args.length < 2) {
                    player.sendMessage(getMsg("mensajes.stats_removeplayer_uso"));
                    return true;
                }
                boolean removed = removePlayerStats(args[1]);
                if (removed) {
                    player.sendMessage(getMsg("mensajes.stats_removeplayer_ok", "nick", args[1]));
                } else {
                    player.sendMessage(getMsg("mensajes.stats_removeplayer_no_existe"));
                }
                return true;
            }

            if (sub.equals("addplayer") || sub.equals("addpl")) {
                if (args.length < 3) {
                    player.sendMessage(getMsg("mensajes.stats_addplayer_uso"));
                    return true;
                }
                int cantidad;
                try {
                    cantidad = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage(getMsg("mensajes.stats_addplayer_numero_invalido"));
                    return true;
                }
                addPlayerStats(args[1], cantidad);
                player.sendMessage(getMsg("mensajes.stats_addplayer_ok", "nick", args[1], "cantidad", String.valueOf(cantidad)));
                return true;
            }
        }

        player.openInventory(createMainMenu(player));
        return true;
    }

    // ==================== Autocompletado ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opciones = new ArrayList<>(Arrays.asList("stop", "help"));
            if (sender.hasPermission("ultimatepops.admin")) {
                opciones.addAll(Arrays.asList("reload", "clear", "removeplayer", "rmpl", "addplayer", "addpl"));
            }

            String actual = args[0].toLowerCase();
            List<String> coincidencias = new ArrayList<>();
            for (String opcion : opciones) {
                if (opcion.startsWith(actual)) coincidencias.add(opcion);
            }
            return coincidencias;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("removeplayer") || args[0].equalsIgnoreCase("rmpl")
                || args[0].equalsIgnoreCase("addplayer") || args[0].equalsIgnoreCase("addpl"))) {
            List<String> nombres = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                nombres.add(online.getName());
            }
            return nombres;
        }

        return Collections.emptyList();
    }

    // ==================== Menus ====================

    /** Menu de 54 slots, borde de dos tonos (esquinas + bordes), 3 modos centrados (slots 20,22,24)
     *  y un item de estadisticas personales del jugador (slot 4). */
    public Inventory createMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, getMainMenuTitle());

        fillBorder(inv);

        inv.setItem(20, buildItem(Material.LIME_WOOL, getModoNombre("infinito"), getModoLore("infinito")));
        inv.setItem(22, buildItem(Material.YELLOW_WOOL, getModoNombre("full"), getModoLore("full")));
        inv.setItem(24, buildItem(Material.RED_WOOL, getModoNombre("random"), getModoLore("random")));

        ItemStack infoItem = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) infoItem.getItemMeta();
        meta.setOwningPlayer(player);
        meta.setDisplayName(getInfoNombre());
        meta.setLore(getInfoLore(player));
        infoItem.setItemMeta(meta);
        inv.setItem(4, infoItem);

        return inv;
    }

    /** Menu de 54 slots con borde de dos tonos y 5 opciones de ticks centradas (slots 19,21,22,23,25). */
    public Inventory createTicksMenu(String modo) {
        Inventory inv = Bukkit.createInventory(null, 54, getTicksMenuTitle(modo));

        fillBorder(inv);

        int[] ticks = {5, 10, 15, 20, 25};
        int[] slots = {19, 21, 22, 23, 25};

        for (int i = 0; i < ticks.length; i++) {
            inv.setItem(slots[i], buildItem(Material.TOTEM_OF_UNDYING, getTicksItemNombre(ticks[i]), getTicksItemLore()));
        }

        inv.setItem(45, buildItem(Material.ARROW, getVolverTexto(), null));

        return inv;
    }

    /** Borde de dos tonos: las 4 esquinas con un material, el resto del marco con otro (look
     *  tipo panel/dashboard, mas prolijo que un borde de un solo color). */
    private void fillBorder(Inventory inv) {
        ItemStack panelBorde = buildItem(getBordeMaterial(), " ", null);
        ItemStack panelEsquina = buildItem(getEsquinasMaterial(), " ", null);

        int size = inv.getSize();
        int rows = size / 9;

        Set<Integer> esquinas = new HashSet<>(Arrays.asList(0, 8, size - 9, size - 1));

        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            boolean isBorder = row == 0 || row == rows - 1 || col == 0 || col == 8;
            if (isBorder) {
                inv.setItem(slot, esquinas.contains(slot) ? panelEsquina : panelBorde);
            }
        }
    }

    public static ItemStack buildItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) {
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }
                    }
