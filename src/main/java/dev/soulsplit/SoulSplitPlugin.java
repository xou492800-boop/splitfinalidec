package dev.soulsplit;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SoulSplit - "Soul Splitter" item (shift-hit = soul split, shift-right-click = voodoo).
 * Only the names listed in config.yml can obtain, hold or use the item.
 */
public final class SoulSplitPlugin extends JavaPlugin implements Listener {

    private static final String TRIGGER = "-split-";
    private static final int VOODOO_TICKS = 200; // 10 seconds

    private final Set<String> authorized = new HashSet<>();
    private NamespacedKey itemKey;

    private final Map<UUID, Session> sessions = new HashMap<>();   // victim uuid -> session
    private final Map<UUID, UUID> bodies = new HashMap<>();        // body uuid -> victim uuid
    private final Map<UUID, Voodoo> voodoos = new HashMap<>();     // user uuid -> voodoo
    private final Map<UUID, Voodoo> voodooStands = new HashMap<>();// stand uuid -> voodoo

    /** true while the plugin itself is dealing damage, so our own handlers ignore it */
    private boolean internal = false;

    /** true only while this plugin is spawning a split body */
    private boolean spawningBody = false;

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodySpawn(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (spawningBody && e.isCancelled()) {
            getLogger().info("Un-cancelling body spawn (" + e.getEntityType() + ") that another plugin blocked.");
            e.setCancelled(false);
        }
    }

    // ------------------------------------------------------------------ state classes

    private static final class Session {
        UUID victim;
        UUID body;
        LivingEntity bodyEntity;
        GameMode oldMode;
        int food;
        float saturation;
        int ticks;
        double lastY;
        Location bodyLoc;
        BukkitTask task;
    }

    private static final class Voodoo {
        UUID user;
        UUID target;
        BossBar bar;
        boolean active;
        ArmorStand stand;
        int remaining;
        BukkitTask task;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "soul_splitter");
        for (String n : getConfig().getStringList("authorized")) {
            authorized.add(n.toLowerCase(Locale.ROOT));
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        // strip the item from anyone who is not authorized, no matter how they got it
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isAuthorized(p)) continue;
                ItemStack[] contents = p.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    if (isItem(contents[i])) p.getInventory().setItem(i, null);
                }
                if (isItem(p.getItemOnCursor())) p.setItemOnCursor(null);
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (Session s : new HashSet<>(sessions.values())) finish(s, true);
        for (UUID u : new HashSet<>(voodoos.keySet())) clearVoodoo(u);
    }

    private boolean isAuthorized(Player p) {
        return authorized.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ item

    private ItemStack createItem() {
        ItemStack it = new ItemStack(Material.TINTED_GLASS);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Soul Splitter", NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Shift + Hit: split a soul from its body", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Shift + Right-click: voodoo", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isItem(ItemStack s) {
        if (s == null || s.getType() != Material.TINTED_GLASS || !s.hasItemMeta()) return false;
        return s.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (!msg.equalsIgnoreCase(TRIGGER)) return;
        Player p = e.getPlayer();
        if (!isAuthorized(p)) return; // everyone else just sends a normal chat message
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            for (ItemStack c : p.getInventory().getContents()) {
                if (isItem(c)) {
                    p.sendMessage(Component.text("You already have a Soul Splitter.", NamedTextColor.GRAY));
                    return;
                }
            }
            p.getInventory().addItem(createItem()).values()
                    .forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
            p.sendMessage(Component.text("Soul Splitter given.", NamedTextColor.DARK_PURPLE));
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceBlock(BlockPlaceEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId()) || isItem(e.getItemInHand())) {
            e.setCancelled(true); // unplaceable
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreakBlock(BlockBreakEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (sessions.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (isItem(e.getItem())) e.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (sessions.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (isItem(e.getItem().getItemStack()) && !isAuthorized(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (isItem(e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (sessions.containsKey(p.getUniqueId())) return;
        // clean up leftovers if the server crashed mid-split
        PotionEffect mf = p.getPotionEffect(PotionEffectType.MINING_FATIGUE);
        if (mf != null && mf.getDuration() == PotionEffect.INFINITE_DURATION && mf.getAmplifier() == 2) {
            p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
            p.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Session s = sessions.get(id);
        if (s != null) finish(s, true);
        clearVoodoo(id);
        // anyone targeting this player loses the voodoo
        for (Voodoo v : new HashSet<>(voodoos.values())) {
            if (v.target.equals(id)) clearVoodoo(v.user);
        }
    }

    // ------------------------------------------------------------------ combat

    private Player resolvePlayer(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (internal) return;
        Entity hit = e.getEntity();
        Player damager = resolvePlayer(e.getDamager());

        // ---- the split body on the floor
        UUID owner = bodies.get(hit.getUniqueId());
        if (owner != null) {
            handleBodyHit(e, owner, damager);
            return;
        }

        // ---- voodoo armor stand
        Voodoo vd = voodooStands.get(hit.getUniqueId());
        if (vd != null) {
            double dmg = e.getFinalDamage();
            boolean crit = e.isCritical();
            e.setCancelled(true);
            if (damager != null && damager.getUniqueId().equals(vd.user) && crit) {
                Player target = Bukkit.getPlayer(vd.target);
                Location at = hit.getLocation();
                // the stand stays (invulnerable) so it can be crit again until the 10s are up
                if (target != null && !sessions.containsKey(target.getUniqueId())) {
                    internal = true;
                    try {
                        target.damage(dmg, damager);
                    } finally {
                        internal = false;
                    }
                    target.getWorld().spawnParticle(Particle.SOUL, target.getLocation().add(0, 1, 0), 20, .3, .5, .3, .02);
                }
                at.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
            }
            return;
        }

        // ---- soul-state players can't hurt, and can't be hurt
        if (damager != null && sessions.containsKey(damager.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (hit instanceof Player vp && sessions.containsKey(vp.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        // ---- ability 1: shift + hit a player
        if (e.getDamager() instanceof Player user && hit instanceof Player target
                && isAuthorized(user) && user.isSneaking()
                && isItem(user.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            split(target);
        }
    }

    /** Environmental damage etc. never touches bodies, souls or voodoo stands. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAnyDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent || internal) return;
        UUID id = e.getEntity().getUniqueId();
        if (bodies.containsKey(id) || voodooStands.containsKey(id)
                || (e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId()))) {
            e.setCancelled(true);
        }
    }

    private void handleBodyHit(EntityDamageByEntityEvent e, UUID ownerId, Player damager) {
        Session s = sessions.get(ownerId);
        if (s == null) return;
        if (damager != null && damager.getUniqueId().equals(ownerId)) {
            e.setCancelled(true);
            finish(s, true); // owner punched their own body -> reconnect
            return;
        }
        if (damager == null || !isAuthorized(damager)) {
            e.setCancelled(true);
            return;
        }
        LivingEntity body = (LivingEntity) e.getEntity();
        if (e.getFinalDamage() >= body.getHealth()) {
            e.setCancelled(true);
            killBody(s, damager);
        }
        // otherwise the damage goes through normally and shows on the body's health
    }

    // ------------------------------------------------------------------ ability 1: soul split

    private void split(Player target) {
        if (sessions.containsKey(target.getUniqueId()) || target.isDead()) return;

        Session s = new Session();
        s.victim = target.getUniqueId();
        s.oldMode = target.getGameMode();
        s.food = target.getFoodLevel();
        s.saturation = target.getSaturation();
        s.lastY = target.getLocation().getY();
        s.bodyLoc = target.getLocation().clone();

        AttributeInstance maxAttr = target.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr != null ? maxAttr.getValue() : 20.0;
        double health = target.getHealth();

        LivingEntity body;
        spawningBody = true; // lets us un-cancel spawn blockers (WorldGuard, anti-mob plugins...)
        try {
            body = spawnBody(target, max, health);
        } finally {
            spawningBody = false;
        }
        if (body == null) {
            notifyAuthorized("Soul split failed: could not spawn a body. Check the console for the error.");
            return;
        }
        s.bodyEntity = body;
        s.body = body.getUniqueId();

        sessions.put(s.victim, s);
        bodies.put(s.body, s.victim);

        // survival so nothing can be abused
        if (target.getGameMode() != GameMode.SURVIVAL) target.setGameMode(GameMode.SURVIVAL);
        target.setFlying(false);
        applyEffects(target);
        target.setFallDistance(0f);
        target.setVelocity(new org.bukkit.util.Vector(0, 1.2, 0));
        // re-apply next tick in case the first push was swallowed by the cancelled hit
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayer(s.victim);
            if (p != null && sessions.containsKey(s.victim) && s.ticks <= 3) {
                p.setVelocity(new org.bukkit.util.Vector(0, 1.2, 0));
            }
        }, 1L);
        target.getWorld().playSound(target.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.8f);

        s.task = new BukkitRunnable() {
            @Override
            public void run() {
                tickSession(s);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void notifyAuthorized(String msg) {
        getLogger().warning(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isAuthorized(p)) p.sendMessage(Component.text(msg, NamedTextColor.RED));
        }
    }

    private void guard(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Body setup step failed (continuing)", t);
        }
    }

    /** Tries a skinned Mannequin first; falls back to an armor stand wearing the player's head. */
    private LivingEntity spawnBody(Player target, double max, double health) {
        Location loc = target.getLocation();
        try {
            Entity spawned = loc.getWorld().spawn(loc, Mannequin.class, m -> {
                guard(() -> m.setPersistent(false));
                guard(() -> m.setRemoveWhenFarAway(false));
                guard(() -> m.setImmovable(true));
                guard(() -> m.setDescription(Component.text("Body of " + target.getName(), NamedTextColor.GRAY)));
                guard(() -> m.setProfile(ResolvableProfile.resolvableProfile()
                        .uuid(target.getUniqueId())
                        .name(target.getName())
                        .addProperties(target.getPlayerProfile().getProperties())
                        .build()));
                guard(() -> {
                    AttributeInstance a = m.getAttribute(Attribute.MAX_HEALTH);
                    if (a != null) a.setBaseValue(max);
                });
                guard(() -> m.setHealth(Math.min(health, max)));
            });
            if (spawned instanceof LivingEntity le && le.isValid() && !le.isDead()) return le;
            getLogger().warning("Mannequin body was removed right after spawning (a plugin is blocking the spawn?), trying armor stand");
            spawned.remove();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Mannequin body failed, falling back to armor stand", t);
        }
        try {
            ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class, a -> {
                a.setPersistent(false);
                a.setArms(true);
                a.setBasePlate(false);
                a.customName(Component.text("Body of " + target.getName(), NamedTextColor.GRAY));
                a.setCustomNameVisible(true);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                sm.setOwningPlayer(target);
                head.setItemMeta(sm);
                a.getEquipment().setHelmet(head);
                guard(() -> {
                    AttributeInstance at = a.getAttribute(Attribute.MAX_HEALTH);
                    if (at != null) at.setBaseValue(max);
                });
                guard(() -> a.setHealth(Math.min(health, max)));
            });
            if (stand.isValid() && !stand.isDead()) return stand;
            getLogger().severe("Armor stand body was also removed right after spawning - something is cancelling entity spawns.");
            stand.remove();
            return null;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Armor stand body failed too", t);
            return null;
        }
    }

    private void applyEffects(Player p) {
        PotionEffect mf = p.getPotionEffect(PotionEffectType.MINING_FATIGUE);
        if (mf == null || mf.getAmplifier() < 2) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,
                    PotionEffect.INFINITE_DURATION, 2, false, false, true)); // level III
        }
        if (!p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                    PotionEffect.INFINITE_DURATION, 0, false, false, true)); // slow float down
        }
    }

    private void tickSession(Session s) {
        Player p = Bukkit.getPlayer(s.victim);
        Entity b = s.bodyEntity;
        boolean bad = p == null || !p.isOnline() || b == null || b.isDead();
        if (!bad && s.ticks > 5 && !b.isValid()) bad = true; // grace period for fresh spawns
        if (bad) {
            getLogger().warning("Soul split ended early at tick " + s.ticks
                    + " (player=" + (p != null) + ", body=" + (b != null)
                    + (b != null ? ", dead=" + b.isDead() + ", valid=" + b.isValid() : "") + ")");
            finish(s, true);
            return;
        }
        s.ticks++;
        s.bodyLoc = b.getLocation();
        applyEffects(p);

        // particle trail at their feet while they are flung upward
        double y = p.getLocation().getY();
        if (s.ticks <= 100 && y > s.lastY + 0.01) {
            Location feet = p.getLocation();
            p.getWorld().spawnParticle(Particle.SOUL, feet, 4, .2, 0, .2, 0.01);
            p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, feet, 2, .15, 0, .15, 0.0);
        }
        s.lastY = y;

        // real health + saturation of the body
        if (s.ticks % 2 == 0 && b instanceof LivingEntity le) {
            AttributeInstance a = le.getAttribute(Attribute.MAX_HEALTH);
            double max = a != null ? a.getValue() : 20.0;
            p.sendActionBar(Component.text(String.format("Health %.1f/%.1f", le.getHealth(), max), NamedTextColor.RED)
                    .append(Component.text("   |   ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(String.format("Saturation %.1f", s.saturation), NamedTextColor.GOLD))
                    .append(Component.text("   |   ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Food " + s.food, NamedTextColor.YELLOW)));
        }
    }

    /** Reconnect the soul with the body (or just clean up). */
    private void finish(Session s, boolean restoreStats) {
        if (!sessions.containsKey(s.victim)) return;
        sessions.remove(s.victim);
        bodies.remove(s.body);
        if (s.task != null) s.task.cancel();

        Player p = Bukkit.getPlayer(s.victim);
        Entity b = s.bodyEntity != null ? s.bodyEntity : Bukkit.getEntity(s.body);
        Location at = b != null ? b.getLocation() : s.bodyLoc;
        double bodyHealth = (b instanceof LivingEntity le) ? le.getHealth() : -1;
        if (b != null) b.remove();

        if (p == null) return;
        p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        p.removePotionEffect(PotionEffectType.SLOW_FALLING);
        p.setGameMode(s.oldMode);
        p.setFallDistance(0f);
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        if (at != null) {
            Location tp = at.clone();
            tp.setYaw(p.getLocation().getYaw());
            tp.setPitch(p.getLocation().getPitch());
            p.teleport(tp);
        }
        if (restoreStats) {
            if (bodyHealth > 0) {
                AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
                double max = a != null ? a.getValue() : 20.0;
                p.setHealth(Math.min(bodyHealth, max));
            }
            p.setFoodLevel(s.food);
            p.setSaturation(s.saturation);
        }
        p.sendActionBar(Component.empty());
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
    }

    private void killBody(Session s, Player killer) {
        Player p = Bukkit.getPlayer(s.victim);
        finish(s, false);
        if (p == null) return;
        internal = true;
        try {
            p.damage(10000.0, killer);
            if (!p.isDead()) p.setHealth(0.0);
        } finally {
            internal = false;
        }
    }

    // ------------------------------------------------------------------ ability 2: voodoo

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (sessions.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!isAuthorized(p) || !isItem(p.getInventory().getItemInMainHand())) return;

        Entity clicked = e.getRightClicked();
        if (!(clicked instanceof Player target)) {
            if (clicked instanceof ItemFrame) e.setCancelled(true); // can't be put in frames
            return;
        }
        if (!p.isSneaking()) return;
        e.setCancelled(true);
        startReady(p, target);
    }

    private void startReady(Player user, Player target) {
        clearVoodoo(user.getUniqueId());
        Voodoo v = new Voodoo();
        v.user = user.getUniqueId();
        v.target = target.getUniqueId();
        v.bar = BossBar.bossBar(Component.text("Ready", NamedTextColor.BLACK), 1f,
                BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        user.showBossBar(v.bar);
        voodoos.put(v.user, v);
        user.playSound(user.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStandPlaced(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null || !(e.getEntity() instanceof ArmorStand stand)) return;
        Voodoo v = voodoos.get(p.getUniqueId());
        if (v == null || v.active) return;
        activate(v, stand);
    }

    private void activate(Voodoo v, ArmorStand stand) {
        Player target = Bukkit.getPlayer(v.target);
        if (target == null) {
            clearVoodoo(v.user);
            return;
        }
        v.active = true;
        v.stand = stand;
        v.remaining = VOODOO_TICKS;
        stand.customName(Component.text(target.getName(), NamedTextColor.DARK_PURPLE));
        stand.setCustomNameVisible(true);
        stand.setInvulnerable(true);
        voodooStands.put(stand.getUniqueId(), v);
        updateBar(v);

        v.task = new BukkitRunnable() {
            @Override
            public void run() {
                v.remaining -= 2;
                if (v.remaining <= 0 || Bukkit.getPlayer(v.target) == null
                        || Bukkit.getPlayer(v.user) == null || !v.stand.isValid()) {
                    clearVoodoo(v.user);
                    return;
                }
                updateBar(v);
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    private void updateBar(Voodoo v) {
        int secs = (int) Math.ceil(v.remaining / 20.0);
        v.bar.name(Component.text("Active - " + secs + "s", NamedTextColor.BLACK));
        v.bar.progress(Math.max(0f, Math.min(1f, v.remaining / (float) VOODOO_TICKS)));
    }

    private void clearVoodoo(UUID user) {
        Voodoo v = voodoos.remove(user);
        if (v == null) return;
        if (v.task != null) v.task.cancel();
        if (v.stand != null) {
            voodooStands.remove(v.stand.getUniqueId());
            v.stand.remove();
        }
        Player p = Bukkit.getPlayer(user);
        if (p != null) p.hideBossBar(v.bar);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStandManipulate(PlayerArmorStandManipulateEvent e) {
        if (voodooStands.containsKey(e.getRightClicked().getUniqueId())) e.setCancelled(true);
    }
}
