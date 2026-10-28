package es.normsafio.killfeed;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * KillFeed: avisos de muerte animados arriba a la derecha.
 * Usa una boss bar sin dibujo (sprites transparentes del pack v14) y la fuente
 * normsafio:killfeed con iconos y espacios para colocar el texto a la derecha.
 */
public final class KillFeedPlugin extends JavaPlugin implements Listener, TabExecutor {

    static final Key FONT = Key.key("normsafio", "killfeed");
    static final int ICON_W = 10;       // ancho de un icono en tamaño normal
    static final int[] FRAME_H = {9, 14, 19};

    enum Icon { PLUMA, ESPADA, HACHA, ARCO, TNT, RADIACION, CALAVERA, FUEGO, LAVA, AGUA, VACIO, TRIDENTE;
        char glyph(int frame) { return (char) (0xE100 + ordinal() * 3 + frame); }
    }
    enum Role { TEXT, ATTACKER, VICTIM }
    record Part(Component base, int width, Role role) {}
    record Msg(List<Part> parts, Icon icon, UUID killer, UUID victim) {
        int totalWidth() {
            int t = 4 + ICON_W;
            for (Part p : parts) t += p.width();
            return t;
        }
    }
    enum Kind { NORMAL, YOU, ME }
    static final class Line {
        final BossBar bar; final Msg msg; final Kind kind; int age = 0;
        Line(BossBar bar, Msg msg, Kind kind) { this.bar = bar; this.msg = msg; this.kind = kind; }
    }

    private final Map<UUID, List<Line>> feeds = new HashMap<>();
    private final Set<UUID> packOk = new HashSet<>();
    private NamespacedKey keyRadius, keyOff, keyForce;
    private int life, maxLines, defaultRadius;
    private boolean onlyWithPack, hideChat, killSound;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyRadius = new NamespacedKey(this, "radio");
        keyOff = new NamespacedKey(this, "off");
        keyForce = new NamespacedKey(this, "forzar");
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("killfeed")).setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, List<Line>> en : feeds.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) for (Line l : en.getValue()) p.hideBossBar(l.bar);
        }
        feeds.clear();
    }

    private void loadSettings() {
        reloadConfig();
        life = Math.max(30, getConfig().getInt("duracion-ticks", 80));
        maxLines = Math.max(1, Math.min(6, getConfig().getInt("max-lineas", 4)));
        defaultRadius = getConfig().getInt("mitad-ancho-gui", 234);
        onlyWithPack = getConfig().getBoolean("solo-con-resourcepack", true);
        hideChat = getConfig().getBoolean("ocultar-mensaje-chat", false);
        killSound = getConfig().getBoolean("sonido-al-matar", true);
    }

    // ------------------------------------------------------------------ eventos

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) packOk.add(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        packOk.remove(id);
        feeds.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getPlayer();
        EntityDamageEvent last = v.getLastDamageCause();
        EntityDamageByEntityEvent byEntity = last instanceof EntityDamageByEntityEvent b ? b : null;

        String plain = e.deathMessage() == null ? "" : PlainTextComponentSerializer.plainText().serialize(e.deathMessage()).toLowerCase();

        Player killer = v.getKiller();
        if (killer == null && byEntity != null && byEntity.getDamager() instanceof TNTPrimed t && t.getSource() instanceof Player s) killer = s;
        if (killer != null && killer.equals(v)) killer = null;

        Msg msg;
        if (plain.contains("radia")) {
            msg = death(v.getName(), "ha muerto por radiación", Icon.RADIACION, v.getUniqueId());
        } else if (killer != null) {
            msg = killMsg(killer, v, byEntity);
        } else if (byEntity != null) {
            msg = mobMsg(v, byEntity);
        } else {
            msg = causeMsg(v, last == null ? null : last.getCause());
        }
        show(msg);
        if (hideChat) e.deathMessage(null);
    }

    // ------------------------------------------------------------------ construcción de mensajes

    private Msg death(String victim, String tail, Icon icon, UUID victimId) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(Component.text(victim, NamedTextColor.RED), textWidth(victim), Role.VICTIM));
        parts.add(txt(" " + tail));
        return new Msg(parts, icon, null, victimId);
    }

    private Msg kill(String killer, String victim, String tail, Icon icon, UUID killerId, UUID victimId) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(Component.text(killer, NamedTextColor.GOLD), textWidth(killer), Role.ATTACKER));
        parts.add(txt(" ha matado a "));
        parts.add(new Part(Component.text(victim, NamedTextColor.RED), textWidth(victim), Role.VICTIM));
        parts.add(txt(" " + tail));
        return new Msg(parts, icon, killerId, victimId);
    }

    private Part txt(String s) {
        return new Part(Component.text(s, NamedTextColor.WHITE), textWidth(s), Role.TEXT);
    }

    private Msg killMsg(Player killer, Player v, EntityDamageByEntityEvent byEntity) {
        String tail;
        Icon icon;
        Entity damager = byEntity == null ? null : byEntity.getDamager();
        if (damager instanceof Trident) {
            icon = Icon.TRIDENTE; tail = "con tridente" + dist(killer, v);
        } else if (damager instanceof AbstractArrow) {
            Material hand = killer.getInventory().getItemInMainHand().getType();
            icon = Icon.ARCO; tail = (hand == Material.CROSSBOW ? "con ballesta" : "con arco") + dist(killer, v);
        } else if (damager instanceof Projectile) {
            icon = Icon.CALAVERA; tail = "con un proyectil" + dist(killer, v);
        } else if (damager instanceof TNTPrimed) {
            icon = Icon.TNT; tail = "con TNT";
        } else {
            Material hand = killer.getInventory().getItemInMainHand().getType();
            String n = hand.name();
            if (n.endsWith("_SWORD")) { icon = Icon.ESPADA; tail = "con espada"; }
            else if (n.endsWith("_AXE")) { icon = Icon.HACHA; tail = "con hacha"; }
            else if (hand == Material.MACE) { icon = Icon.HACHA; tail = "con maza"; }
            else if (hand == Material.TRIDENT) { icon = Icon.TRIDENTE; tail = "con tridente"; }
            else if (hand == Material.AIR) { icon = Icon.CALAVERA; tail = "con los puños"; }
            else { icon = Icon.CALAVERA; tail = "con un objeto"; }
        }
        return kill(killer.getName(), v.getName(), tail, icon, killer.getUniqueId(), v.getUniqueId());
    }

    private String dist(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) return "";
        int d = (int) Math.round(a.getLocation().distance(b.getLocation()));
        return d >= 6 ? " (" + d + " m)" : "";
    }

    private Msg mobMsg(Player v, EntityDamageByEntityEvent byEntity) {
        Entity d = byEntity.getDamager();
        if (d instanceof Projectile p && p.getShooter() instanceof Entity shooter) d = shooter;
        if (d instanceof Creeper || d instanceof TNTPrimed)
            return death(v.getName(), "ha volado por los aires", Icon.TNT, v.getUniqueId());
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(Component.text(v.getName(), NamedTextColor.RED), textWidth(v.getName()), Role.VICTIM));
        parts.add(txt(" murió a manos de "));
        parts.add(new Part(Component.translatable(d.getType().translationKey(), NamedTextColor.GOLD), 66, Role.ATTACKER));
        return new Msg(parts, Icon.CALAVERA, null, v.getUniqueId());
    }

    private Msg causeMsg(Player v, EntityDamageEvent.DamageCause c) {
        String tail = "ha muerto";
        Icon icon = Icon.CALAVERA;
        if (c != null) switch (c) {
            case FALL -> { tail = "ha muerto de caída"; icon = Icon.PLUMA; }
            case FIRE, FIRE_TICK, HOT_FLOOR -> { tail = "ha muerto quemado"; icon = Icon.FUEGO; }
            case LAVA -> { tail = "se ha bañado en lava"; icon = Icon.LAVA; }
            case DROWNING -> { tail = "se ha ahogado"; icon = Icon.AGUA; }
            case VOID -> { tail = "ha caído al vacío"; icon = Icon.VACIO; }
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> { tail = "ha volado por los aires"; icon = Icon.TNT; }
            case STARVATION -> tail = "ha muerto de hambre";
            case CONTACT -> tail = "ha muerto pinchado";
            case LIGHTNING -> tail = "ha sido alcanzado por un rayo";
            case SUFFOCATION -> tail = "se ha asfixiado";
            case FLY_INTO_WALL -> tail = "se ha estampado";
            case POISON -> tail = "ha muerto envenenado";
            case MAGIC, WITHER -> tail = "ha muerto por magia";
            case FREEZE -> tail = "se ha congelado";
            case SUICIDE, KILL -> tail = "se ha suicidado";
            default -> { }
        }
        return death(v.getName(), tail, icon, v.getUniqueId());
    }

    // ------------------------------------------------------------------ mostrar y animar

    private boolean eligible(Player p) {
        if (p.getPersistentDataContainer().has(keyOff, PersistentDataType.BYTE)) return false;
        if (!onlyWithPack) return true;
        return packOk.contains(p.getUniqueId()) || p.getPersistentDataContainer().has(keyForce, PersistentDataType.BYTE);
    }

    private void show(Msg msg) {
        for (Player p : Bukkit.getOnlinePlayers()) if (eligible(p)) showTo(p, msg);
    }

    private void showTo(Player p, Msg msg) {
        Kind kind = p.getUniqueId().equals(msg.killer()) ? Kind.YOU : p.getUniqueId().equals(msg.victim()) ? Kind.ME : Kind.NORMAL;
        List<Line> list = feeds.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        while (list.size() >= maxLines) p.hideBossBar(list.remove(0).bar);
        BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        Line line = new Line(bar, msg, kind);
        bar.name(render(p, line));
        p.showBossBar(bar);
        list.add(line);
        if (kind == Kind.YOU && killSound)
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
    }

    private void tick() {
        Iterator<Map.Entry<UUID, List<Line>>> it = feeds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Line>> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) { it.remove(); continue; }
            List<Line> list = en.getValue();
            for (Iterator<Line> li = list.iterator(); li.hasNext(); ) {
                Line l = li.next();
                l.age++;
                if (l.age >= life) { p.hideBossBar(l.bar); li.remove(); continue; }
                if (l.age <= 30 || l.age >= life - 9) l.bar.name(render(p, l));
            }
            if (list.isEmpty()) it.remove();
        }
    }

    private int radius(Player p) {
        Integer r = p.getPersistentDataContainer().get(keyRadius, PersistentDataType.INTEGER);
        return r != null ? r : defaultRadius;
    }

    private static double easeOutBack(double x) {
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    private int slide(int age) {
        final int in = 10, out = 8;
        if (age < in) return (int) Math.round(200 * (1 - easeOutBack(age / (double) in)));
        if (age >= life - out) { double x = (age - (life - out)) / (double) out; return (int) Math.round(260 * x * x * x); }
        return 0;
    }

    private Component render(Player p, Line l) {
        Msg m = l.msg;
        int age = l.age;
        boolean flash = l.kind != Kind.NORMAL && age >= 8 && age < 30 && (age / 2) % 2 == 0;
        int frame = (age >= 8 && age < 10) ? 2 : (age >= 10 && age < 13) ? 1 : 0;

        int content = m.totalWidth();          // texto + espacio + icono
        int panelW = content + 8;              // 4 px de margen a cada lado
        int s = 2 * radius(p) - panelW + 2 * slide(age);
        TextComponent.Builder b = Component.text().font(FONT);
        b.append(Component.text(spaces(s)));
        TextColor bg = l.kind == Kind.YOU ? TextColor.color(0x0B4A1E) : l.kind == Kind.ME ? TextColor.color(0x5A0B0B) : TextColor.color(0x000000);
        b.append(Component.text(panel(panelW), bg));
        b.append(Component.text(spaces(4 - panelW)));
        for (Part part : m.parts()) {
            Component c = part.base();
            if (flash && part.role() != Role.TEXT) c = c.color(NamedTextColor.WHITE);
            b.append(c);
        }
        b.append(Component.text(" "));
        b.append(Component.text(String.valueOf(m.icon().glyph(frame)), NamedTextColor.WHITE));
        int comp = FRAME_H[0] - FRAME_H[frame];
        if (comp != 0) b.append(Component.text(spaces(comp)));
        b.append(Component.text(spaces(4)));
        return b.build();
    }

    /** Fondo oscuro de ancho exacto: baldosas de 8 px y de 1 px (cada una avanza 1 px de más, se compensa). */
    static String panel(int w) {
        StringBuilder sb = new StringBuilder();
        char back = (char) 0xE300; // -1 px
        for (int i = 0; i < w / 8; i++) sb.append('\uE180').append(back);
        for (int i = 0; i < w % 8; i++) sb.append('\uE181').append(back);
        return sb.toString();
    }

    /** Espacio horizontal exacto en píxeles con la fuente (positivo o negativo). */
    static String spaces(int px) {
        int base = px >= 0 ? 0xE200 : 0xE300;
        int n = Math.min(1023, Math.abs(px));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 10; k++) if ((n & (1 << k)) != 0) sb.append((char) (base + k));
        return sb.toString();
    }

    // ------------------------------------------------------------------ anchos de texto (fuente por defecto)

    static int textWidth(String s) {
        int t = 0;
        for (char c : s.toCharArray()) t += charWidth(c);
        return t;
    }

    static int charWidth(char c) {
        switch (c) {
            case ' ': return 4;
            case 'i': case '!': case '.': case ',': case ':': case ';': case '|': return 2;
            case '\'': case 'l': case 'í': case '`': return 3;
            case 'I': case 't': case '[': case ']': case '(': case ')': case '{': case '}': case '*': case 'ì': return 4;
            case 'f': case 'k': case '<': case '>': case '"': return 5;
            case '@': case '~': return 7;
            default: return 6;
        }
    }

    // ------------------------------------------------------------------ comandos

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            sender.sendMessage("§6/kf on|off §7· activar/desactivar para ti");
            sender.sendMessage("§6/kf ajuste <píxeles> §7· acerca/aleja el aviso del borde (por defecto " + defaultRadius + ")");
            if (sender.hasPermission("killfeed.admin"))
                sender.sendMessage("§6/kf test <caida|espada|arco|tnt|radiacion|fuego|lava|agua|vacio|matas|tematan> §7· /kf push <icono> <texto> §7· /kf reload");
            return true;
        }
        String sub = a[0].toLowerCase();
        if (sub.equals("reload") && sender.hasPermission("killfeed.admin")) { loadSettings(); sender.sendMessage("§aRecargado."); return true; }

        if (sub.equals("push") && sender.hasPermission("killfeed.admin")) {
            if (a.length < 3) { sender.sendMessage("§cUso: /kf push <icono> <texto con &colores>"); return true; }
            Icon icon;
            try { icon = Icon.valueOf(a[1].toUpperCase()); }
            catch (IllegalArgumentException ex) { sender.sendMessage("§cIconos: " + Arrays.toString(Icon.values()).toLowerCase()); return true; }
            String text = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
            Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(text);
            int w = textWidth(PlainTextComponentSerializer.plainText().serialize(c));
            show(new Msg(List.of(new Part(c, w, Role.TEXT)), icon, null, null));
            return true;
        }

        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }
        switch (sub) {
            case "on" -> {
                p.getPersistentDataContainer().remove(keyOff);
                p.getPersistentDataContainer().set(keyForce, PersistentDataType.BYTE, (byte) 1);
                p.sendMessage("§aKill feed activado.");
            }
            case "off" -> {
                p.getPersistentDataContainer().set(keyOff, PersistentDataType.BYTE, (byte) 1);
                p.sendMessage("§cKill feed desactivado.");
            }
            case "ajuste" -> {
                if (a.length < 2) { p.sendMessage("§cUso: /kf ajuste <número> (más grande = más pegado al borde)"); return true; }
                try {
                    int r = Math.max(60, Math.min(700, Integer.parseInt(a[1])));
                    p.getPersistentDataContainer().set(keyRadius, PersistentDataType.INTEGER, r);
                    p.sendMessage("§aAjuste guardado: " + r + ". Prueba con /kf test caida");
                } catch (NumberFormatException ex) { p.sendMessage("§cPon un número."); }
            }
            case "test" -> {
                if (!p.hasPermission("killfeed.admin")) return true;
                String t = a.length > 1 ? a[1].toLowerCase() : "caida";
                UUID me = p.getUniqueId();
                Msg m = switch (t) {
                    case "espada" -> kill("Ana", "Pepe", "con espada", Icon.ESPADA, null, null);
                    case "arco" -> kill("Ana", "Pepe", "con arco (32 m)", Icon.ARCO, null, null);
                    case "tnt" -> death("Pepe", "ha volado por los aires", Icon.TNT, null);
                    case "radiacion" -> death("Pepe", "ha muerto por radiación", Icon.RADIACION, null);
                    case "fuego" -> death("Pepe", "ha muerto quemado", Icon.FUEGO, null);
                    case "lava" -> death("Pepe", "se ha bañado en lava", Icon.LAVA, null);
                    case "agua" -> death("Pepe", "se ha ahogado", Icon.AGUA, null);
                    case "vacio" -> death("Pepe", "ha caído al vacío", Icon.VACIO, null);
                    case "matas" -> kill(p.getName(), "Pepe", "con espada", Icon.ESPADA, me, null);
                    case "tematan" -> kill("Ana", p.getName(), "con arco (20 m)", Icon.ARCO, null, me);
                    default -> death("Pepe", "ha muerto de caída", Icon.PLUMA, null);
                };
                showTo(p, m);
            }
            default -> p.sendMessage("§cSubcomando desconocido. Usa /kf");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return filter(List.of("on", "off", "ajuste", "test", "push", "reload"), a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("test"))
            return filter(List.of("caida", "espada", "arco", "tnt", "radiacion", "fuego", "lava", "agua", "vacio", "matas", "tematan"), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("push"))
            return filter(Arrays.stream(Icon.values()).map(i -> i.name().toLowerCase()).toList(), a[1]);
        return List.of();
    }

    private static List<String> filter(List<String> all, String start) {
        String s = start.toLowerCase();
        return all.stream().filter(x -> x.startsWith(s)).toList();
    }
}
