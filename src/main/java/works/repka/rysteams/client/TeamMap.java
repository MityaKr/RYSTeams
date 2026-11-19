package works.repka.rysteams.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.world.Labels;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Permission;
import works.repka.rysteams.data.Team;

/**
 * Команда на карте семьи (план карты, Т2; ядро {@code map.MapApi} и {@code world.Labels}, памятка {@code RYSMap/docs/neighbors.md}).
 *
 * <ul>
 *   <li>Слой «Команда»: пинги участников и союзников (клавиша V или пункт карты), постоянные метки команды с панелью и правкой
 *       из окна карты, места гибели участников (своё ведёт карта), участники, у которых задано «чем занят» (голова с подписью-статусом, только на большой карте).</li>
 *   <li>Подписи в мире: метки и цель из ленты отдаются источником {@link Labels}, пинги идут в общий список пингов ядра
 *       ({@link Labels#ping}, 1.15). Пока их рисует карта ({@link Labels#present()}), {@link TeamHud} свои подписи не рисует.</li>
 *   <li>Пункты меню карты: пинг четырёх видов (первый под id {@code rysmap:ping}, тогда свой пункт карты уступает место) и
 *       метка команды в этом месте.</li>
 * </ul>
 *
 * <p>Всё зовётся из главного потока клиента и отвечает из того, что прислал сервер ({@link TeamClient}). Без RYSMap реестры
 * ядра просто хранят слой и пункты.
 */
public final class TeamMap implements MapApi.Layer, Labels.Source {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "team");
    /** Пункт карты, который заменяет свой пункт пинга RYSMap. */
    static final ResourceLocation MAP_PING = ResourceLocation.fromNamespaceAndPath("rysmap", "ping");
    /** Метки дальше этого в мире не подписываются (как у {@link TeamHud}). */
    private static final int MARK_RANGE = 512;
    private static final TeamMap INSTANCE = new TeamMap();

    private long revision, signature = Long.MIN_VALUE;

    private TeamMap() {}

    public static void register() {
        MapApi.get().layer(INSTANCE);
        Labels.source(INSTANCE);
        String[] kinds = {"here", "danger", "resource", "help"};
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i];
            ResourceLocation id = i == 0 ? MAP_PING : ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "ping_" + kind);
            MapApi.get().menu(MapApi.MenuItem.builder(id, Component.translatable("rysteams.map.ping", Component.translatable("rysteams.ping." + kind)))
                .icon(i == 1 ? Icons.Shape.WARN : Icons.Shape.BELL).order(10 + i)
                .visible(s -> TeamClient.team != null).enabled(s -> TeamClient.can(Permission.PING))
                .run(s -> ping(kind, s)).build());
        }
        MapApi.get().menu(MapApi.MenuItem.builder(ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "mark"), Component.translatable("rysteams.map.mark_here"))
            .icon(Icons.Shape.FLAG).order(15)
            .visible(s -> TeamClient.team != null).enabled(s -> TeamClient.can(Permission.EDIT_MARKS))
            .run(TeamMap::markHere).build());
    }

    // ---- действия ----

    static void ping(String kind, MapApi.Spot s) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        t.putString("dim", s.dimension().location().toString());
        t.putInt("x", s.x());
        t.putInt("y", s.y());
        t.putInt("z", s.z());
        TeamClient.action("ping", t);
    }

    static void markHere(MapApi.Spot s) {
        CompoundTag t = new CompoundTag();
        t.putString("name", Component.translatable("rysteams.map.mark_name").getString());
        t.putString("dim", s.dimension().location().toString());
        t.putInt("x", s.x());
        t.putInt("y", s.y());
        t.putInt("z", s.z());
        TeamClient.action("mark_add", t);
    }

    /** Может ли игрок править метку: право на метки или своя метка (как на сервере). */
    static boolean canEdit(Team.Mark m) {
        Minecraft mc = Minecraft.getInstance();
        if (TeamClient.can(Permission.EDIT_MARKS)) return true;
        return mc.player != null && !m.death() && m.by().equals(mc.player.getGameProfile().getName());
    }

    // ---- слой ----

    @Override public ResourceLocation id() { return ID; }
    @Override public Component name() { return Component.translatable("rysteams.map.layer"); }
    @Override public Icons.Shape icon() { return Icons.Shape.USERS; }
    @Override public int order() { return 3; }

    /** Снимок того, от чего зависят метки: новое состояние команды, пинги, присутствие. Счётчик растёт при его смене. */
    @Override
    public long revision() {
        long sig = System.identityHashCode(TeamClient.team);
        for (TeamClient.Ping p : TeamClient.PINGS) sig = sig * 31 + p.hashCode();
        for (Map.Entry<UUID, TeamClient.Pres> e : TeamClient.PRESENCE.entrySet()) sig = sig * 31 + e.hashCode();
        TeamClient.Waypoint w = TeamClient.waypoint;
        sig = sig * 31 + (w == null ? 0 : w.hashCode());
        sig = sig * 31 + System.identityHashCode(TeamClient.allyMarks);
        if (sig != signature) {
            signature = sig;
            revision++;
        }
        return revision;
    }

    @Override
    public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
        String dim = dimension.location().toString();
        List<MapApi.Marker> out = new ArrayList<>();
        Team team = TeamClient.team;
        if (team != null) {
            Minecraft mc = Minecraft.getInstance();
            UUID self = mc.player == null ? null : mc.player.getUUID();
            for (Team.Mark m : team.marks) {
                if (m.dimension().equals(dim) && !ownDeath(m, self)) out.add(marker(dimension, m));
            }
            for (Map.Entry<UUID, TeamClient.Pres> e : TeamClient.PRESENCE.entrySet()) {
                TeamClient.Pres p = e.getValue();
                Team.Member member = team.members.get(e.getKey());
                if (member == null || e.getKey().equals(self) || !p.hasPos() || !p.dimension().equals(dim) || p.status().isBlank()) continue;
                out.add(MapApi.Marker.builder(e.getKey()).pos(dimension, p.x() + 0.5, p.y(), p.z() + 0.5).kind(MapApi.Kind.PLAYER)
                    .head(e.getKey()).color(0xFF000000 | team.color).label(Component.literal(member.name)).note(Component.literal(p.status()))
                    .minimap(false)
                    .tooltip(() -> List.of(Component.literal(member.name), Component.literal(p.status())))
                    .click(() -> MapApi.get().track(target(dimension, p.x(), p.y(), p.z(), member.name)))
                    .build());
            }
        }
        for (TeamClient.AllyMarks a : TeamClient.allyMarks) {
            for (Team.Mark m : a.marks()) if (m.dimension().equals(dim)) out.add(allyMarker(dimension, a, m));
        }
        for (TeamClient.Ping p : TeamClient.PINGS) {
            if (!p.dimension().equals(dim)) continue;
            Component kind = Component.translatable("rysteams.ping." + p.kind());
            out.add(MapApi.Marker.builder(pingId(p)).pos(dimension, p.x() + 0.5, p.y(), p.z() + 0.5).kind(MapApi.Kind.PING)
                .icon(p.kind().equals("danger") ? Icons.Shape.WARN : Icons.Shape.BELL).color(0xFF000000 | p.color())
                .label(Component.literal(p.by())).note(kind)
                .click(() -> MapApi.get().track(target(dimension, p.x(), p.y(), p.z(), p.by())))
                .build());
        }
        return out;
    }

    private static MapApi.Marker marker(ResourceKey<Level> dimension, Team.Mark m) {
        String title = TeamHud.markTitle(m);
        boolean death = m.death();
        MapApi.Marker.Builder b = MapApi.Marker.builder(m.id()).pos(dimension, m.x() + 0.5, m.y(), m.z() + 0.5)
            .kind(MapApi.Kind.POINT).label(Component.literal(title))
            .note(Component.translatable("rysteams.map.mark_by", m.by()))
            .panel(() -> panel(dimension, m))
            .editor(death ? null : editor(m));
        if (death) b.icon(Icons.Shape.SKULL).color(0xFFE5484D);
        else b.item(TeamClient.icon(m.icon())).color(0xFF000000 | (TeamClient.team == null ? 0xF7F8FB : TeamClient.team.color));
        return b.build();
    }

    /** Метка союзника, открытая союзу: только чтение, цвет и имя его команды. */
    private static MapApi.Marker allyMarker(ResourceKey<Level> dimension, TeamClient.AllyMarks a, Team.Mark m) {
        Component sub = Component.translatable("rysteams.map.ally_sub", a.name());
        return MapApi.Marker.builder(m.id()).pos(dimension, m.x() + 0.5, m.y(), m.z() + 0.5).kind(MapApi.Kind.POINT)
            .item(TeamClient.icon(m.icon())).color(0xFF000000 | a.color()).label(Component.literal(m.name())).note(sub)
            .panel(() -> MapApi.Panel.builder(Component.literal(m.name())).subtitle(sub)
                .row(Component.translatable("rysteams.map.row.by"), Component.literal(m.by()))
                .row(Component.translatable("rysteams.map.row.pos"), Component.literal(m.x() + " " + m.y() + " " + m.z()))
                .action(MapApi.Action.builder(Component.translatable("rysteams.map.track"), () -> MapApi.get().track(target(dimension, m.x(), m.y(), m.z(), m.name())))
                    .icon(Icons.Shape.NAV).primary(true).build())
                .build())
            .build();
    }

    private static MapApi.Panel panel(ResourceKey<Level> dimension, Team.Mark m) {
        String title = TeamHud.markTitle(m);
        MapApi.Panel.Builder p = MapApi.Panel.builder(Component.literal(title))
            .subtitle(Component.translatable(m.death() ? "rysteams.map.death_sub" : "rysteams.map.mark_sub", TeamClient.team == null ? "" : TeamClient.team.name))
            .row(Component.translatable("rysteams.map.row.by"), Component.literal(m.by()))
            .row(Component.translatable("rysteams.map.row.pos"), Component.literal(m.x() + " " + m.y() + " " + m.z()))
            .action(MapApi.Action.builder(Component.translatable("rysteams.map.track"), () -> MapApi.get().track(target(dimension, m.x(), m.y(), m.z(), title)))
                .icon(Icons.Shape.NAV).primary(true).build());
        if (!m.death() && canEdit(m)) {
            p.action(MapApi.Action.builder(Component.translatable("rysteams.map.remove"), () -> remove(m)).icon(Icons.Shape.CROSS).build());
        }
        return p.build();
    }

    private static MapApi.Editor editor(Team.Mark m) {
        return new MapApi.Editor() {
            @Override
            public boolean canEdit() {
                return TeamMap.canEdit(m);
            }

            @Override
            public void rename(String name) {
                String clean = name.strip();
                if (clean.isEmpty() || clean.equals(m.name())) return;
                CompoundTag t = new CompoundTag();
                t.putUUID("id", m.id());
                t.putString("name", clean);
                TeamClient.action("mark_edit", t);
            }

            @Override
            public void move(int x, int y, int z) {
                CompoundTag t = new CompoundTag();
                t.putUUID("id", m.id());
                t.putInt("x", x);
                t.putInt("y", y);
                t.putInt("z", z);
                TeamClient.action("mark_edit", t);
            }

            @Override
            public void remove() {
                TeamMap.remove(m);
            }

            /** «Кто видит» (макет 15): команда или команда и союз. «Только я» нет: метка команды общая. */
            @Override
            public List<Component> audiences() {
                return List.of(Component.translatable("rysteams.map.audience.team"), Component.translatable("rysteams.map.audience.allies"));
            }

            @Override
            public int audience() {
                return m.allies() ? 1 : 0;
            }

            @Override
            public void audience(int index) {
                if ((index == 1) == m.allies()) return;
                CompoundTag t = new CompoundTag();
                t.putUUID("id", m.id());
                t.putBoolean("allies", index == 1);
                TeamClient.action("mark_edit", t);
            }
        };
    }

    static void remove(Team.Mark m) {
        TeamClient.action("mark_del", "id", m.id());
    }

    @Override
    public List<MapApi.Hit> search(String query) {
        Team team = TeamClient.team;
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (team == null || q.isEmpty()) return List.of();
        List<MapApi.Hit> out = new ArrayList<>();
        for (Team.Mark m : team.marks) {
            String title = TeamHud.markTitle(m);
            if (!title.toLowerCase(Locale.ROOT).contains(q)) continue;
            ResourceKey<Level> dim = dimension(m.dimension());
            if (dim == null) continue;
            MapApi.Hit.Builder hit = MapApi.Hit.builder(Component.literal(title), target(dim, m.x(), m.y(), m.z(), title))
                .note(Component.translatable("rysteams.map.mark_sub", team.name));
            if (m.death()) hit.icon(Icons.Shape.SKULL).color(0xFFE5484D);
            else hit.item(TeamClient.icon(m.icon())).color(0xFF000000 | team.color);
            out.add(hit.build());
        }
        for (TeamClient.AllyMarks a : TeamClient.allyMarks) {
            for (Team.Mark m : a.marks()) {
                ResourceKey<Level> dim = dimension(m.dimension());
                if (dim == null || !m.name().toLowerCase(Locale.ROOT).contains(q)) continue;
                out.add(MapApi.Hit.builder(Component.literal(m.name()), target(dim, m.x(), m.y(), m.z(), m.name()))
                    .note(Component.translatable("rysteams.map.ally_sub", a.name())).item(TeamClient.icon(m.icon())).color(0xFF000000 | a.color()).build());
            }
        }
        return out;
    }

    // ---- подписи в мире ----

    @Override
    public Collection<Labels.Label> labels(ResourceKey<Level> dimension) {
        String dim = dimension.location().toString();
        List<Labels.Label> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        Team team = TeamClient.team;
        if (team != null) {
            for (Team.Mark m : team.marks) {
                if (!m.dimension().equals(dim) || ownDeath(m, mc.player == null ? null : mc.player.getUUID())
                    || (mc.player != null && distance(mc, m.x(), m.z()) > MARK_RANGE)) continue;
                Labels.Label.Builder b = Labels.Label.builder(m.id()).pos(dimension, m.x() + 0.5, m.y() + 1.5, m.z() + 0.5)
                    .kind(Labels.Kind.POINT).text(Component.literal(TeamHud.markTitle(m)));
                if (m.death()) b.icon(Icons.Shape.SKULL).color(0xFFE5484D);
                else b.item(TeamClient.icon(m.icon())).color(0xFF000000 | team.color);
                out.add(b.build());
            }
        }
        for (TeamClient.AllyMarks a : TeamClient.allyMarks) {
            for (Team.Mark m : a.marks()) {
                if (!m.dimension().equals(dim) || (mc.player != null && distance(mc, m.x(), m.z()) > MARK_RANGE)) continue;
                out.add(Labels.Label.builder(m.id()).pos(dimension, m.x() + 0.5, m.y() + 1.5, m.z() + 0.5).kind(Labels.Kind.POINT)
                    .item(TeamClient.icon(m.icon())).color(0xFF000000 | a.color()).text(Component.literal(m.name())).build());
            }
        }
        TeamClient.Waypoint w = TeamClient.waypoint;
        if (w != null && w.dimension().equals(dim)) {
            out.add(Labels.Label.builder(UUID.nameUUIDFromBytes(("waypoint:" + w.label() + w.x() + ":" + w.y() + ":" + w.z()).getBytes(StandardCharsets.UTF_8)))
                .pos(dimension, w.x() + 0.5, w.y() + 1.2, w.z() + 0.5).kind(Labels.Kind.GOAL).icon(Icons.Shape.FLAG)
                .color(0xFF000000 | w.color()).text(Component.literal(w.label())).build());
        }
        return out;
    }

    // ---- пинги в мире (ядро 1.15) ----

    /** Id пингов RYSTeams в общем списке ядра: снять свои при выходе из мира. */
    private static final java.util.Set<UUID> WORLD_PINGS = new java.util.HashSet<>();

    /**
     * Пинг в мире через ядро ({@link Labels#ping}, 1.15): срок жизни как у пинга RYSTeams, кольцо, цвет команды. Клавиша V,
     * колесо и пункт карты шлют один пакет {@code ping}, поэтому вид у всех один. Пока карты нет, {@link TeamHud} рисует сам.
     */
    static void worldPing(TeamClient.Ping p) {
        ResourceLocation dim = ResourceLocation.tryParse(p.dimension());
        if (dim == null) return;
        UUID id = pingId(p);
        String kind = Component.translatable("rysteams.ping." + p.kind()).getString().toLowerCase(Locale.ROOT);
        Labels.ping(Labels.Label.builder(id).pos(ResourceKey.create(Registries.DIMENSION, dim), p.x() + 0.5, p.y() + 1.2, p.z() + 0.5)
            .kind(Labels.Kind.PING).icon(p.kind().equals("danger") ? Icons.Shape.WARN : Icons.Shape.BELL).color(0xFF000000 | p.color())
            .text(Component.literal(p.by() + ": " + kind)).until(p.until()).build());
        synchronized (WORLD_PINGS) {
            WORLD_PINGS.add(id);
        }
    }

    static void clearWorldPings() {
        synchronized (WORLD_PINGS) {
            Labels.removePings(l -> WORLD_PINGS.contains(l.id()));
            WORLD_PINGS.clear();
        }
    }

    // ---- помощники ----

    /** Своё место гибели ведёт сама карта (план карты, 9.1, таблица RYSTeams): в слой и подписи оно не попадает. */
    static boolean ownDeath(Team.Mark m, @Nullable UUID self) {
        return m.death() && self != null && self.equals(m.owner());
    }

    /** У пинга нет своего id: он постоянен для одного пинга, пока тот живёт. */
    static UUID pingId(TeamClient.Ping p) {
        String key = "ping:" + p.by() + ":" + p.dimension() + ":" + p.x() + ":" + p.y() + ":" + p.z() + ":" + p.until();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static MapApi.Target target(ResourceKey<Level> dimension, int x, int y, int z, String label) {
        return MapApi.Target.builder(dimension, x + 0.5, y, z + 0.5).label(Component.literal(label)).build();
    }

    private static @Nullable ResourceKey<Level> dimension(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    private static double distance(Minecraft mc, int x, int z) {
        double dx = mc.player.getX() - x, dz = mc.player.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
