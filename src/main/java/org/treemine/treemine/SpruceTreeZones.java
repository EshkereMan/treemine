package org.treemine.treemine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class SpruceTreeZones extends JavaPlugin implements Listener {

    private static final Material TOOL_MATERIAL = Material.SPRUCE_SAPLING;
    private static final Material LOG = Material.SPRUCE_LOG;

    private static final Component WAND_NAME = Component.text("Treewand")
            .color(NamedTextColor.YELLOW)
            .decorate(TextDecoration.BOLD);

    private static final List<Component> WAND_LORE = List.of(
            Component.text("LMB - first point", NamedTextColor.GRAY),
            Component.text("RMB - create zone", NamedTextColor.GRAY)
    );

    private static final int BAR_SEGMENTS = 20;
    private static final int BASE_HITS = 10;
    private static final long REGEN_DELAY_TICKS = 600;

    
    private final Map<String, Zone> zones = new HashMap<>();

    
    private final Map<UUID, Map<Location, Integer>> playerHits = new HashMap<>();

    
    private final Set<Location> brokenTrees = new HashSet<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        //saveDefaultConfig();
        //loadZones(); 
        getLogger().info("SpruceTreeZones Enabled!");
    }

    private void loadZones() {
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("treewand")) {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("sprucetreezones.admin")) {
                p.sendMessage(Component.text("No rights!", NamedTextColor.RED));
                return true;
            }
            p.getInventory().addItem(createWand());
            p.sendMessage(Component.text("Wand is given!", NamedTextColor.GREEN)
                    .append(Component.text("LMB - first point, RMB - second point", NamedTextColor.GRAY)));
            return true;
        }
        return false;
    }

    private ItemStack createWand() {
        ItemStack wand = new ItemStack(TOOL_MATERIAL);
        ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.displayName(WAND_NAME);
            meta.lore(WAND_LORE);
            wand.setItemMeta(meta);
        }
        return wand;
    }

    @EventHandler
    public void onWandUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item == null || item.getItemMeta() == null || !item.getItemMeta().displayName().equals(WAND_NAME)) return;

        Player p = e.getPlayer();
        e.setCancelled(true);

        if (e.getAction() == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Location loc = e.getClickedBlock().getLocation();
            getConfig().set("players." + p.getUniqueId() + ".pos1", loc);
            p.sendMessage(Component.text("First point: " + formatLoc(loc), NamedTextColor.YELLOW));
            saveConfig();
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Location pos2 = e.getClickedBlock().getLocation();
            Location pos1 = getConfig().getLocation("players." + p.getUniqueId() + ".pos1");

            if (pos1 == null) {
                p.sendMessage(Component.text("Place a first point first!", NamedTextColor.RED));
                return;
            }

            Location min = new Location(pos1.getWorld(),
                    Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ()));
            Location max = new Location(pos1.getWorld(),
                    Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()),
                    Math.max(pos1.getZ(), pos2.getZ()));

            String zoneName = "zone_" + min.getBlockX() + "_" + min.getBlockZ();
            if (zones.containsKey(zoneName)) {
                p.sendMessage(Component.text("Zone is already here!", NamedTextColor.RED));
                return;
            }

            zones.put(zoneName, new Zone(min, max));
            p.sendMessage(Component.text("Zone crated: " + zoneName, NamedTextColor.GREEN));

            getConfig().set("zones." + zoneName + ".min", min);
            getConfig().set("zones." + zoneName + ".max", max);
            saveConfig();

            getConfig().set("players." + p.getUniqueId() + ".pos1", null);
            saveConfig();
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (e.isCancelled()) return;
        Block b = e.getBlock();
        if (b.getType() != LOG) return;

        Player p = e.getPlayer();
        Location loc = b.getLocation();

        Zone zone = getZoneForLocation(loc);
        if (zone == null) return;

        e.setCancelled(true);

        List<Location> treeBlocks = getTreeBlocks(loc);
        if (treeBlocks.size() < 3) {
            p.sendActionBar(Component.text("Tree is to small", NamedTextColor.RED));
            return;
        }

        int hitsNeeded = BASE_HITS;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool != null && tool.getType().toString().endsWith("_AXE")) {
            int eff = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
            hitsNeeded += eff * 3;
            int unb = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
            hitsNeeded += unb * 2;
        }

        UUID uuid = p.getUniqueId();
        Map<Location, Integer> hitsMap = playerHits.computeIfAbsent(uuid, k -> new HashMap<>());
        int current = hitsMap.getOrDefault(loc, 0) + 1;
        hitsMap.put(loc, current);

        double progress = (double) current / hitsNeeded;
        int filled = (int) Math.round(progress * BAR_SEGMENTS);
        filled = Math.min(filled, BAR_SEGMENTS);

        String bar = "§a▮".repeat(filled) + "§7▯".repeat(BAR_SEGMENTS - filled);

        p.sendActionBar(Component.text("Tree: " + bar + " §7(" + current + "/" + hitsNeeded + ")")
                .color(NamedTextColor.YELLOW));

        p.playSound(loc, Sound.BLOCK_WOOD_HIT, 0.7f, 0.9f);

        if (current >= hitsNeeded) {
            for (Location treeLoc : treeBlocks) {
                treeLoc.getBlock().setType(Material.AIR);
            }
            p.sendActionBar(Component.text("Tree mined! Reset in 30 seconds...", NamedTextColor.GREEN));

            brokenTrees.addAll(treeBlocks);

            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Location treeLoc : treeBlocks) {
                        if (treeLoc.getBlock().getType() == Material.AIR) {
                            treeLoc.getBlock().setType(LOG);
                        }
                    }
                    brokenTrees.removeAll(treeBlocks);
                    p.playSound(loc, Sound.BLOCK_GRASS_PLACE, 0.8f, 1.1f);
                }
            }.runTaskLater(this, REGEN_DELAY_TICKS);

            hitsMap.remove(loc);
        } else {
            b.setType(LOG);
        }
    }

    @Nullable
    private Zone getZoneForLocation(Location loc) {
        for (Zone zone : zones.values()) {
            if (zone.contains(loc)) {
                return zone;
            }
        }
        return null;
    }

    private List<Location> getTreeBlocks(Location start) {
        List<Location> blocks = new ArrayList<>();
        Queue<Location> queue = new LinkedList<>();
        Set<Location> visited = new HashSet<>();

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            Location current = queue.poll();
            blocks.add(current);

            for (BlockFace face : BlockFace.values()) {
                if (face.getModX() == 0 && face.getModZ() == 0 && face.getModY() == 0) continue;
                Location next = current.clone().add(face.getDirection());
                if (!visited.contains(next) && next.getBlock().getType() == LOG) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return blocks;
    }

    private String formatLoc(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    private static class Zone {
        final Location min, max;

        Zone(Location min, Location max) {
            this.min = min;
            this.max = max;
        }

        boolean contains(Location loc) {
            return loc.getX() >= min.getX() && loc.getX() <= max.getX() &&
                    loc.getY() >= min.getY() && loc.getY() <= max.getY() &&
                    loc.getZ() >= min.getZ() && loc.getZ() <= max.getZ();
        }
    }
}
