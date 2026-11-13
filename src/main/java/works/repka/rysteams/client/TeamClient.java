package works.repka.rysteams.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import works.repka.rysteams.api.Permission;
import works.repka.rysteams.data.Team;
import works.repka.rysteams.network.ModNetwork;

/** Что клиент знает о командах. Заполняется только сообщениями сервера. */
public final class TeamClient {
    /** @param pending игрок уже отправил заявку в эту команду */
    public record Info(UUID id, String name, int color, String icon, int count, String join, String by, long until, boolean pending) {
        public Info(UUID id, String name, int color, String icon, int count, String join, String by, long until) {
            this(id, name, color, icon, count, join, by, until, false);
        }
    }

    public record Online(UUID id, String name, boolean free) {}

    public record Pres(String dimension, boolean hasPos, int x, int y, int z, String status) {}

    public record Ping(String by, int color, String kind, String dimension, int x, int y, int z, long until) {}

    public record Waypoint(String label, int color, String dimension, int x, int y, int z, long until) {}

    public record MergeLine(String source, Component text, boolean question) {}

    public record MergeInfo(Info from, Info into, boolean fromOk, boolean intoOk, List<MergeLine> lines) {}

    public record Toast(Component text, boolean bad, long until) {}


    @Nullable public static Team team;
    public static List<Info> teams = List.of();
    public static List<Info> invites = List.of();
    public static List<Info> allies = List.of();
    public static List<Info> allyOffers = List.of();
    public static List<Online> online = List.of();
    public static boolean hideCoords;
    /** «Сообщать о моей смерти»: хранится на сервере, по умолчанию включено. */
    public static boolean deathNotice = true;
    public static boolean serverMode;
    public static String restore = "";
    @Nullable public static MergeInfo merge;
    public static long undoUntil;
    public static final Map<UUID, Pres> PRESENCE = new HashMap<>();
    public static final List<Ping> PINGS = new ArrayList<>();
    @Nullable public static Waypoint waypoint;
    @Nullable public static Toast toast;
    /** Плашка приглашения висит полминуты после прихода; потом приглашение остаётся в лобби и в ссылке чата. */
    public static final long INVITE_SHOW_MS = 30_000;
    public static long inviteShownUntil;
    /** Ответ клавишей ушёл, ждём состояние без этого приглашения. Истёкшее само по себе плашку не возвращает. */
    public static boolean inviteAnswered;
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static final Map<String, Component> TEXTS = new HashMap<>();

    private TeamClient() {}

    public static void reset() {
        team = null;
        teams = invites = allies = allyOffers = List.of();
        online = List.of();
        merge = null;
        undoUntil = 0;
        restore = "";
        PRESENCE.clear();
        PINGS.clear();
        waypoint = null;
        toast = null;
        inviteShownUntil = 0;
        inviteAnswered = false;
    }

    public static boolean inviteVisible() {
        return !invites.isEmpty() && System.currentTimeMillis() < inviteShownUntil;
    }

    public static void accept(CompoundTag t) {
        switch (t.getString("type")) {
            case "state" -> state(t);
            case "presence" -> presence(t);
            case "ping" -> PINGS.add(new Ping(t.getString("by"), t.getInt("color"), t.getString("kind"), t.getString("dim"),
                t.getInt("x"), t.getInt("y"), t.getInt("z"), System.currentTimeMillis() + ClientEntry.PING_SECONDS.get() * 1000L));
            case "toast" -> toast(text(t.getString("text")), t.getBoolean("bad"));
            case "admin" -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof AdminScreen open) open.accept(t);
                else mc.setScreen(new AdminScreen(t));
            }
            default -> { }
        }
    }

    private static Info info(CompoundTag x) {
        long left = x.getLong("left");
        return new Info(x.getUUID("id"), x.getString("name"), x.getInt("color"), x.getString("icon"), x.getInt("count"),
            x.getString("join"), x.getString("by"), left == 0 ? 0 : System.currentTimeMillis() + left);
    }

    private static List<Info> infos(CompoundTag t, String key) {
        List<Info> out = new ArrayList<>();
        for (Tag x : t.getList(key, Tag.TAG_COMPOUND)) out.add(info((CompoundTag) x));
        return out;
    }

    private static void state(CompoundTag t) {
        team = t.contains("team") ? Team.load(t.getCompound("team")) : null;
        // сервер шлёт общий список всех команд и всех игроков: себя и свою команду убираем здесь
        java.util.Set<String> pending = new java.util.HashSet<>();
        for (Tag x : t.getList("pending", Tag.TAG_STRING)) pending.add(x.getAsString());
        List<Info> all = new ArrayList<>();
        for (Info i : infos(t, "teams")) {
            if (team != null && i.id().equals(team.id)) continue;
            all.add(pending.contains(i.id().toString()) ? new Info(i.id(), i.name(), i.color(), i.icon(), i.count(), i.join(), i.by(), i.until(), true) : i);
        }
        teams = all;
        List<Info> fresh = infos(t, "invites");
        // на приглашение ответили, сервер его убрал: плашка переходит к следующему висящему
        if (inviteAnswered) {
            for (Info old : invites) {
                boolean gone = true;
                for (Info i : fresh) if (i.id().equals(old.id())) gone = false;
                if (gone) {
                    inviteAnswered = false;
                    if (!fresh.isEmpty()) inviteShownUntil = System.currentTimeMillis() + INVITE_SHOW_MS;
                    break;
                }
            }
        }
        for (Info i : fresh) {
            boolean known = false;
            // то же приглашение приходит с каждым состоянием; новое или повторное живёт дольше прежнего
            for (Info old : invites) if (old.id().equals(i.id()) && i.until() < old.until() + 5000) known = true;
            if (!known) inviteShownUntil = System.currentTimeMillis() + INVITE_SHOW_MS;
        }
        invites = fresh;
        allies = infos(t, "allies");
        allyOffers = infos(t, "allyOffers");
        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player == null ? null : mc.player.getUUID();
        List<Online> people = new ArrayList<>();
        for (Tag x : t.getList("online", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            if (c.getUUID("id").equals(self)) continue;
            people.add(new Online(c.getUUID("id"), c.getString("name"), c.getBoolean("free")));
        }
        online = people;
        // присутствие бывших сокомандников больше не нужно
        if (team == null) PRESENCE.clear();
        else PRESENCE.keySet().retainAll(team.members.keySet());
        hideCoords = t.getBoolean("hide");
        deathNotice = t.getBoolean("deathNotice");
        serverMode = t.getBoolean("serverMode");
        restore = t.getString("restore");
        undoUntil = t.contains("undoLeft") ? System.currentTimeMillis() + t.getLong("undoLeft") : 0;
        if (t.contains("merge")) {
            CompoundTag m = t.getCompound("merge");
            List<MergeLine> lines = new ArrayList<>();
            for (Tag x : m.getList("lines", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) x;
                lines.add(new MergeLine(c.getString("src"), text(c.getString("text")), c.getBoolean("q")));
            }
            merge = new MergeInfo(info(m.getCompound("from")), info(m.getCompound("into")), m.getBoolean("fromOk"), m.getBoolean("intoOk"), lines);
        } else {
            merge = null;
        }
    }

    private static void presence(CompoundTag t) {
        for (Tag x : t.getList("list", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            if (c.getBoolean("gone")) {
                PRESENCE.remove(c.getUUID("id"));
            } else {
                PRESENCE.put(c.getUUID("id"), new Pres(c.getString("dim"), c.getBoolean("pos"), c.getInt("x"), c.getInt("y"), c.getInt("z"), c.getString("status")));
            }
        }
    }

    /** Строка в общих уведомлениях семьи: справа вверху, в колонке тостов игры; ошибка красная. Новая строка заменяет прежнюю. */
    public static void toast(Component text, boolean bad) {
        toast = new Toast(text, bad, System.currentTimeMillis() + TeamNotices.ROW_MS);
        TeamNotices.row(text, bad);
    }

    public static boolean can(Permission permission) {
        Minecraft mc = Minecraft.getInstance();
        return team != null && mc.player != null && team.can(mc.player.getUUID(), permission);
    }

    public static Component text(String json) {
        Component known = TEXTS.get(json);
        if (known != null) return known;
        if (TEXTS.size() > 1500) TEXTS.clear();
        Minecraft mc = Minecraft.getInstance();
        Component c;
        try {
            c = mc.level == null ? Component.literal(json) : Component.Serializer.fromJson(json, mc.level.registryAccess());
        } catch (RuntimeException e) {
            c = Component.literal(json);
        }
        if (c == null) c = Component.empty();
        if (mc.level != null) TEXTS.put(json, c);
        return c;
    }

    public static ItemStack icon(String id) {
        return ICONS.computeIfAbsent(id, key -> {
            ResourceLocation location = ResourceLocation.tryParse(key);
            return location == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.get(location).getDefaultInstance();
        });
    }

    public static void action(String action) {
        ModNetwork.action(action, new CompoundTag());
    }

    public static void action(String action, CompoundTag args) {
        ModNetwork.action(action, args);
    }

    public static void action(String action, String key, UUID id) {
        CompoundTag t = new CompoundTag();
        t.putUUID(key, id);
        ModNetwork.action(action, t);
    }
}
