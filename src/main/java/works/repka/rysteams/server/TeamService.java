package works.repka.rysteams.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.TeamsConfig;
import works.repka.rysteams.api.Permission;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.data.Team;
import works.repka.rysteams.network.ModNetwork;

/** Вся логика команд. Каждое действие приходит от клиента, поэтому права проверяются здесь. */
public final class TeamService {
    public static final String SOURCE_TEAMS = "teams";
    private static final long INVITE_MS = 5 * 60_000L;
    private static final long UNDO_MS = 30_000L;
    /** Предложение слияния без ответа снимается через десять минут. */
    private static final long MERGE_MS = 10 * 60_000L;
    private static final long ARCHIVE_MS = 7 * 24 * 3_600_000L;
    private static final long DAY_MS = 24 * 3_600_000L;
    private static final int NAME_MIN = 3, NAME_MAX = 24, TEXT_MAX = 400, ICON_MAX = 128;
    /** Сколько ленты кладётся в архив распущенной команды: данные модов остаются целиком. */
    private static final int ARCHIVE_FEED = 20;
    /** Ведро действий игрока: восемь в секунду, запас двадцать. Обычный интерфейс до него не дотягивается. */
    private static final double RATE_PER_S = 8, BURST = 20;
    private static final long PING_MS = 500, INVITE_AGAIN_MS = 10_000, REQUEST_AGAIN_MS = 30_000;
    private static final Set<String> PING_KINDS = Set.of("here", "danger", "resource", "help");
    /** Метка гибели живёт полчаса и снимается раньше, когда погибший подходит к ней ближе шести блоков. */
    private static final long DEATH_MARK_MS = 30 * 60_000L, DEATH_FEED_MS = 10_000;
    private static final double DEATH_NEAR = 6;

    private record Invite(UUID team, String by, long expires) {}

    private static final class Merge {
        final UUID from, into;
        final long expires;
        boolean fromOk, intoOk;

        Merge(UUID from, UUID into, long expires) {
            this.from = from;
            this.into = into;
            this.expires = expires;
        }

        boolean involves(UUID team) {
            return from.equals(team) || into.equals(team);
        }
    }

    private record Undo(CompoundTag from, CompoundTag into, UUID fromId, UUID intoId, long deadline) {}

    private static final class Bucket {
        double tokens = BURST;
        long last = now(), warned;
    }

    private static MinecraftServer server;
    private static final Map<UUID, List<Invite>> INVITES = new HashMap<>();
    private static final Map<UUID, Set<UUID>> ALLY_OFFERS = new HashMap<>();
    /** Предложения слияния по команде, в которую сливают. Команда участвует не больше чем в одном. */
    private static final Map<UUID, Merge> MERGES = new HashMap<>();
    /** Откат слияния по команде, в которую слили. */
    private static final Map<UUID, Undo> UNDOS = new HashMap<>();
    private static final Map<UUID, Bucket> BUCKETS = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> COOLDOWN = new HashMap<>();
    /** Повтор предложения слияния по паре «кто:кому». Не по игроку: перезаход его бы сбросил, а лента жертвы общая. */
    private static final Map<String, Long> MERGE_AGAIN = new HashMap<>();
    /** Когда погибший снова может попасть в ленту: череда смертей не заваливает её записями. */
    private static final Map<UUID, Long> DEATH_FEED = new HashMap<>();
    private static final Set<UUID> DIRTY = new HashSet<>();
    /** Игроки, которым нужно новое состояние, хотя их команда не менялась: приглашение, заявка, скрытие координат. */
    private static final Set<UUID> DIRTY_PLAYERS = new HashSet<>();
    private static boolean dirtyLobby;
    /**
     * Вошедшие и вышедшие с прошлой рассылки (разбор логики 19.11, раздел 5, пункт 5): вошедшему уходит лобби целиком,
     * остальным только строка «+игрок» или «−игрок», а не полное состояние всем.
     */
    private static final Set<UUID> JOINED = new LinkedHashSet<>(), LEFT = new LinkedHashSet<>();
    private static int ticks;

    private TeamService() {}

    public static void start(MinecraftServer s) {
        server = s;
        INVITES.clear();
        ALLY_OFFERS.clear();
        MERGES.clear();
        UNDOS.clear();
        BUCKETS.clear();
        COOLDOWN.clear();
        MERGE_AGAIN.clear();
        DEATH_FEED.clear();
        DIRTY.clear();
        DIRTY_PLAYERS.clear();
        JOINED.clear();
        LEFT.clear();
        Presence.reset();
        FtbImport.repairNames(s);
        repair(store());
        ApiEvents.reset(store());
    }

    public static void stop() {
        server = null;
        MERGE_AGAIN.clear();
    }

    public static TeamStore store() {
        return TeamStore.get(server);
    }

    public static boolean running() {
        return server != null;
    }

    /** Чинит сохранения прежних версий: коды форматирования в названиях и раздутые списки. */
    private static void repair(TeamStore s) {
        boolean changed = false;
        for (Team team : s.teams.values()) {
            String name = normName(team.name);
            if (name.length() < NAME_MIN) name = "Team";
            if (!name.equals(team.name)) {
                team.name = freeName(name, team);
                changed = true;
            }
            changed |= trim(team);
        }
        changed |= s.byPlayer.entrySet().removeIf(e -> !s.teams.containsKey(e.getValue()));
        if (changed) s.setDirty();
    }

    private static boolean trim(Team team) {
        boolean changed = false;
        while (team.notes.size() > TeamsConfig.maxNotes()) {
            team.notes.remove(team.notes.size() - 1);
            changed = true;
        }
        while (team.todos.size() > TeamsConfig.maxTodos()) {
            team.todos.remove(team.todos.size() - 1);
            changed = true;
        }
        // временные метки гибели в лимит не входят: срезаются только постоянные, с конца
        for (int i = team.marks.size() - 1; i >= 0 && team.permanentMarks() > TeamsConfig.maxMarks(); i--) {
            if (team.marks.get(i).death()) continue;
            team.marks.remove(i);
            changed = true;
        }
        return changed;
    }

    // --- события сервера ---

    public static void login(ServerPlayer player) {
        try {
            TeamStore s = store();
            UUID id = player.getUUID();
            if (s.byPlayer.containsKey(id) && s.teamOf(id) == null) {
                s.byPlayer.remove(id);
                s.setDirty();
            }
            if (TeamsConfig.isServerMode() && s.teamOf(id) == null) joinServerTeam(player);
            Team team = s.teamOf(id);
            if (team != null) {
                Team.Member m = team.members.get(id);
                if (m == null) {
                    // рассинхрон: игрок числится в команде, которой о нём неизвестно
                    RYSTeams.LOG.warn("Игрок {} числился в команде {}, но не в её составе", player.getGameProfile().getName(), team.name);
                    s.byPlayer.remove(id);
                    s.setDirty();
                } else {
                    String name = player.getGameProfile().getName();
                    if (!name.equals(m.name)) {
                        m.name = name;
                        s.setDirty();
                    }
                    autoTransfer(team);
                    DIRTY.add(team.id);
                }
            }
        } catch (RuntimeException e) {
            RYSTeams.LOG.error("Вход игрока {}: ошибка команд", player.getGameProfile().getName(), e);
        }
        // состояние и лобби уйдут ему в ближайшем тике (игрок уже в списке сервера), остальным строка «+игрок»
        UUID joined = player.getUUID();
        LEFT.remove(joined);
        JOINED.add(joined);
        DIRTY_PLAYERS.add(joined);
    }

    public static void logout(ServerPlayer player) {
        UUID id = player.getUUID();
        Team team = store().teamOf(id);
        if (team != null) {
            Team.Member m = team.members.get(id);
            if (m != null) m.lastSeen = now();
            DIRTY.add(team.id);
        }
        store().setDirty();
        Presence.forget(id);
        BUCKETS.remove(id);
        COOLDOWN.remove(id);
        // остальным строка «−игрок»; вошедший и тут же вышедший никому не нужен
        if (!JOINED.remove(id)) LEFT.add(id);
        DIRTY_PLAYERS.remove(id);
    }

    public static void tick() {
        if (server == null) return;
        ticks++;
        // события контракта (роль, союз, имя) сверкой с прошлым снимком: ловит любые пути правки, в том числе слияние и автопередачу
        if (ticks % 1200 == 0) safely("события для модов", () -> ApiEvents.check(store()));
        else if (!DIRTY.isEmpty()) safely("события для модов", () -> ApiEvents.check(store(), DIRTY));
        if (!DIRTY.isEmpty() || !DIRTY_PLAYERS.isEmpty() || dirtyLobby || !JOINED.isEmpty() || !LEFT.isEmpty()) safely("рассылка состояния", TeamService::flush);
        if (ticks % 40 == 0) safely("присутствие", () -> Presence.broadcast(server, store()));
        if (ticks % 200 == 0) safely("сроки", TeamService::expire);
        if (ticks % 20 == 0) safely("метки гибели", TeamService::expireMarks);
    }

    /** Ошибка в данных одной команды не должна ронять тик сервера. */
    private static void safely(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            RYSTeams.LOG.error("RYSTeams: {} не выполнено", what, e);
        }
    }

    private static void expire() {
        long t = now();
        for (Iterator<Map.Entry<UUID, List<Invite>>> it = INVITES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, List<Invite>> e = it.next();
            if (e.getValue().removeIf(i -> i.expires < t)) DIRTY_PLAYERS.add(e.getKey());
            if (e.getValue().isEmpty()) it.remove();
        }
        for (Iterator<Merge> it = MERGES.values().iterator(); it.hasNext(); ) {
            Merge m = it.next();
            if (m.expires >= t) continue;
            it.remove();
            DIRTY.add(m.from);
            DIRTY.add(m.into);
        }
        for (Iterator<Undo> it = UNDOS.values().iterator(); it.hasNext(); ) {
            Undo u = it.next();
            if (u.deadline >= t) continue;
            it.remove();
            DIRTY.add(u.intoId);
        }
        TeamStore s = store();
        if (s.archive.removeIf(a -> t - a.time() > ARCHIVE_MS)) s.setDirty();
    }

    /** Раз в секунду: снять истёкшие метки и метки гибели, к которым подошёл сам погибший. */
    private static void expireMarks() {
        long t = now();
        for (Team team : store().teams.values()) {
            if (team.marks.isEmpty()) continue;
            boolean changed = team.marks.removeIf(m -> m.expires() > 0 && m.expires() <= t || m.death() && reached(m));
            if (changed) touch(team);
        }
    }

    /** Погибший жив, в том же измерении и ближе шести блоков. Пока он на экране смерти, он стоит на самой метке. */
    private static boolean reached(Team.Mark m) {
        if (m.owner() == null) return false;
        ServerPlayer p = online(m.owner());
        if (p == null || !p.isAlive() || !p.level().dimension().location().toString().equals(m.dimension())) return false;
        return p.distanceToSqr(m.x() + 0.5, m.y() + 0.5, m.z() + 0.5) < DEATH_NEAR * DEATH_NEAR;
    }

    private static void autoTransfer(Team team) {
        if (team.autoTransferDays <= 0 || team.members.size() < 2) return;
        Team.Member leader = team.leader();
        if (leader == null || online(leader.id) != null) return;
        // 0: когда лидер заходил, неизвестно (импорт), передавать не за что
        if (leader.lastSeen <= 0) return;
        if (now() - leader.lastSeen < team.autoTransferDays * DAY_MS) return;
        Team.Member next = successor(team, leader.id);
        if (next == null) return;
        leader.role = Role.OFFICER;
        next.role = Role.LEADER;
        feed(team, Component.translatable("rysteams.feed.auto_leader", next.name));
        store().setDirty();
    }

    /**
     * Рассылка. Лобби (все команды и все в сети) ходит отдельным пакетом: целиком всем, когда меняется список команд или
     * кто в какой команде (создание, вступление, уход, роспуск: редко), и одному вошедшему; на вход и выход остальным
     * уходит строка «+игрок» или «−игрок». Состояние своей команды получают её участники и игроки из {@link #DIRTY_PLAYERS}.
     */
    private static void flush() {
        boolean lobby = dirtyLobby;
        Set<UUID> dirty = new HashSet<>(DIRTY);
        Set<UUID> players = new HashSet<>(DIRTY_PLAYERS);
        Set<UUID> joined = new LinkedHashSet<>(JOINED), left = new LinkedHashSet<>(LEFT);
        DIRTY.clear();
        DIRTY_PLAYERS.clear();
        JOINED.clear();
        LEFT.clear();
        dirtyLobby = false;
        TeamStore s = store();
        CompoundTag lobbyTag = lobby || !joined.isEmpty() ? lobbyTag(s) : null;
        CompoundTag delta = !lobby && (!joined.isEmpty() || !left.isEmpty()) ? lobbyDelta(s, joined, left) : null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            try {
                if (lobby || joined.contains(id)) {
                    ModNetwork.send(p, lobbyTag);
                } else if (delta != null) {
                    ModNetwork.send(p, delta);
                }
            } catch (RuntimeException e) {
                RYSTeams.LOG.error("Лобби для {} не отправлено", p.getGameProfile().getName(), e);
            }
        }
        // союзникам тоже: у них список союза и открытые союзу метки этой команды
        for (UUID id : new ArrayList<>(dirty)) {
            Team t = s.teams.get(id);
            if (t != null) dirty.addAll(t.allies);
        }
        Shared shared = new Shared();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID team = s.byPlayer.get(p.getUUID());
            if (!lobby && !players.contains(p.getUUID()) && (team == null || !dirty.contains(team))) continue;
            try {
                ModNetwork.send(p, stateFor(p, shared));
            } catch (RuntimeException e) {
                RYSTeams.LOG.error("Состояние для {} не отправлено", p.getGameProfile().getName(), e);
            }
        }
    }

    private static void touch(Team team) {
        store().setDirty();
        DIRTY.add(team.id);
    }

    /** Лобби целиком: сводки всех команд и все игроки в сети (себя клиент пропускает сам). */
    private static CompoundTag lobbyTag(TeamStore s) {
        CompoundTag out = new CompoundTag();
        out.putString("type", "lobby");
        ListTag teams = new ListTag();
        for (Team t : s.teams.values()) teams.add(summary(t));
        out.put("teams", teams);
        ListTag online = new ListTag();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(onlineTag(s, p));
        out.put("online", online);
        return out;
    }

    private static CompoundTag onlineTag(TeamStore s, ServerPlayer p) {
        CompoundTag x = new CompoundTag();
        x.putUUID("id", p.getUUID());
        x.putString("name", p.getGameProfile().getName());
        x.putBoolean("free", !s.byPlayer.containsKey(p.getUUID()));
        return x;
    }

    /** Строка лобби на вход и выход: кто пришёл (с именем и «без команды»), кто ушёл. */
    private static CompoundTag lobbyDelta(TeamStore s, Set<UUID> joined, Set<UUID> left) {
        CompoundTag out = new CompoundTag();
        out.putString("type", "lobby_delta");
        ListTag add = new ListTag();
        for (UUID id : joined) {
            ServerPlayer p = online(id);
            if (p != null) add.add(onlineTag(s, p));
        }
        out.put("add", add);
        ListTag gone = new ListTag();
        for (UUID id : left) gone.add(StringTag.valueOf(id.toString()));
        out.put("gone", gone);
        return out;
    }

    private static void touchLobby() {
        store().setDirty();
        dirtyLobby = true;
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static ServerPlayer online(UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    // --- состояние для клиента ---

    /**
     * Части состояния, общие для многих получателей: собираются один раз за рассылку и одним экземпляром кладутся всем.
     * После отправки теги не меняются, клиент их только читает.
     */
    private static final class Shared {
        final Map<UUID, CompoundTag> full = new HashMap<>(), noAlerts = new HashMap<>();
        final Map<UUID, ListTag> allies = new HashMap<>(), offers = new HashMap<>(), allyMarks = new HashMap<>();
        final Map<Merge, CompoundTag> merges = new HashMap<>();
    }

    private static CompoundTag summary(Team t) {
        CompoundTag x = new CompoundTag();
        x.putUUID("id", t.id);
        x.putString("name", t.name);
        x.putInt("color", t.color);
        x.putString("icon", t.icon);
        x.putInt("count", t.members.size());
        x.putString("join", t.join.name());
        return x;
    }

    private static ListTag summaries(Collection<UUID> ids) {
        TeamStore s = store();
        ListTag out = new ListTag();
        for (UUID id : ids) {
            Team a = s.teams.get(id);
            if (a != null) out.add(summary(a));
        }
        return out;
    }

    /** Метки союзников, открытые союзу («Кто видит: команда и союз»): по команде-союзнику, с её именем и цветом. */
    private static ListTag allyMarks(Team team) {
        TeamStore s = store();
        ListTag out = new ListTag();
        for (UUID id : team.allies) {
            Team ally = s.teams.get(id);
            if (ally == null) continue;
            ListTag marks = new ListTag();
            for (Team.Mark m : ally.marks) if (m.allies() && !m.death()) marks.add(m.save());
            if (marks.isEmpty()) continue;
            CompoundTag x = summary(ally);
            x.put("marks", marks);
            out.add(x);
        }
        return out;
    }

    private static CompoundTag teamTag(Shared sh, Team team, boolean alerts) {
        CompoundTag full = sh.full.computeIfAbsent(team.id, k -> team.save(true));
        if (alerts) return full;
        return sh.noAlerts.computeIfAbsent(team.id, k -> {
            CompoundTag copy = full.copy();
            copy.getList("feed", Tag.TAG_COMPOUND).removeIf(x -> ((CompoundTag) x).getBoolean("a"));
            return copy;
        });
    }

    private static CompoundTag stateFor(ServerPlayer player, Shared sh) {
        TeamStore s = store();
        UUID me = player.getUUID();
        CompoundTag out = new CompoundTag();
        out.putString("type", "state");
        Team team = s.teamOf(me);
        if (team != null) {
            out.put("team", teamTag(sh, team, team.can(me, Permission.SEE_ALERTS)));
            out.put("allies", sh.allies.computeIfAbsent(team.id, k -> summaries(team.allies)));
            out.put("allyOffers", sh.offers.computeIfAbsent(team.id, k -> summaries(ALLY_OFFERS.getOrDefault(team.id, Set.of()))));
            out.put("allyMarks", sh.allyMarks.computeIfAbsent(team.id, k -> allyMarks(team)));
        }
        // общий список команд и игроков в сети ходит отдельно (пакет lobby), здесь только своё
        if (team == null) {
            ListTag pending = new ListTag();
            for (Team t : s.teams.values()) if (t.requests.containsKey(me)) pending.add(StringTag.valueOf(t.id.toString()));
            out.put("pending", pending);
        }
        ListTag invites = new ListTag();
        long t0 = now();
        for (Invite i : INVITES.getOrDefault(me, List.of())) {
            Team t = s.teams.get(i.team);
            if (t == null || i.expires < t0) continue;
            CompoundTag x = summary(t);
            x.putString("by", i.by);
            x.putLong("left", i.expires - t0);
            invites.add(x);
        }
        out.put("invites", invites);
        out.putBoolean("hide", s.hideCoords.contains(me));
        out.putBoolean("deathNotice", !s.quietDeath.contains(me));
        out.putBoolean("serverMode", TeamsConfig.isServerMode());
        if (team == null) {
            for (TeamStore.Archived a : s.archive) {
                if (a.leader().equals(me) && !a.byAdmin()) {
                    out.putString("restore", a.team().getString("name"));
                    break;
                }
            }
        }
        Merge merge = team == null ? null : mergeOf(team.id);
        if (merge != null) {
            if (!sh.merges.containsKey(merge)) sh.merges.put(merge, mergeTag(merge));
            CompoundTag tag = sh.merges.get(merge);
            if (tag != null) out.put("merge", tag);
        }
        Undo undo = team == null ? null : UNDOS.get(team.id);
        if (undo != null && undo.deadline >= t0) out.putLong("undoLeft", undo.deadline - t0);
        return out;
    }

    private static Merge mergeOf(UUID team) {
        for (Merge m : MERGES.values()) if (m.involves(team)) return m;
        return null;
    }

    /** Предпросмотр слияния. Если одной из команд уже нет, предложение снимается и возвращается null. */
    private static CompoundTag mergeTag(Merge merge) {
        TeamStore s = store();
        Team from = s.teams.get(merge.from), into = s.teams.get(merge.into);
        if (from == null || into == null) {
            MERGES.remove(merge.into);
            DIRTY.add(merge.from);
            DIRTY.add(merge.into);
            return null;
        }
        CompoundTag x = new CompoundTag();
        x.put("from", summary(from));
        x.put("into", summary(into));
        x.putBoolean("fromOk", merge.fromOk);
        x.putBoolean("intoOk", merge.intoOk);
        ListTag lines = new ListTag();
        addLine(lines, SOURCE_TEAMS, Component.translatable("rysteams.merge.roles", from.leader() == null ? "" : from.leader().name), false);
        addLine(lines, SOURCE_TEAMS, Component.translatable("rysteams.merge.feed"), false);
        for (TeamsApi.Listener l : TeamsApi.listeners()) {
            try {
                for (TeamsApi.MergeLine line : l.previewMerge(merge.from, merge.into)) addLine(lines, line.source().getPath(), line.text(), line.question());
            } catch (Throwable t) {
                RYSTeams.LOG.warn("Предпросмотр слияния: ошибка в слушателе", t);
            }
        }
        x.put("lines", lines);
        return x;
    }

    private static void addLine(ListTag list, String source, Component text, boolean question) {
        CompoundTag x = new CompoundTag();
        x.putString("src", source);
        x.putString("text", json(text));
        x.putBoolean("q", question);
        list.add(x);
    }

    private static String json(Component c) {
        return Component.Serializer.toJson(c, server.registryAccess());
    }

    // --- лента и уведомления ---

    public static void feed(Team team, Component text) {
        feed(team, SOURCE_TEAMS, text, "", false, "");
    }

    public static void feed(Team team, String source, Component text, String icon, boolean alert, String action) {
        team.addFeed(new Team.Feed(now(), source, json(text), icon, alert, action));
        touch(team);
    }

    private static void toast(ServerPlayer player, Component text, boolean bad) {
        CompoundTag x = new CompoundTag();
        x.putString("type", "toast");
        x.putString("text", json(text));
        x.putBoolean("bad", bad);
        ModNetwork.send(player, x);
    }

    private static void denied(ServerPlayer player) {
        toast(player, Component.translatable("rysteams.error.denied"), true);
    }

    private static void limit(ServerPlayer player) {
        toast(player, Component.translatable("rysteams.error.limit"), true);
    }

    // --- частота действий ---

    /** Ведро токенов: скрипт или изменённый клиент не завалит сервер и команду рассылками. */
    private static boolean allow(ServerPlayer p) {
        Bucket b = BUCKETS.computeIfAbsent(p.getUUID(), k -> new Bucket());
        long t = now();
        b.tokens = Math.min(BURST, b.tokens + (t - b.last) * RATE_PER_S / 1000.0);
        b.last = t;
        if (b.tokens >= 1) {
            b.tokens -= 1;
            return true;
        }
        tooFast(p);
        return false;
    }

    private static void tooFast(ServerPlayer p) {
        Bucket b = BUCKETS.computeIfAbsent(p.getUUID(), k -> new Bucket());
        long t = now();
        if (t - b.warned < 1000) return;
        b.warned = t;
        toast(p, Component.translatable("rysteams.error.too_fast"), true);
    }

    /** @return true, если то же действие было недавно и сейчас его выполнять нельзя */
    private static boolean cooldown(ServerPlayer p, String key, long ms) {
        Map<String, Long> map = COOLDOWN.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        long t = now();
        Long until = map.get(key);
        if (until != null && until > t) {
            tooFast(p);
            return true;
        }
        if (map.size() > 32) map.values().removeIf(v -> v <= t);
        map.put(key, t + ms);
        return false;
    }

    // --- действия ---

    public static void handle(ServerPlayer p, String action, CompoundTag a) {
        if (server == null) return;
        if (!allow(p)) return;
        try {
            dispatch(p, action, a);
        } catch (RuntimeException e) {
            // кривой пакет не должен ронять сервер
            RYSTeams.LOG.warn("Действие {} от {} не выполнено: {}", action, p.getGameProfile().getName(), e.toString());
        }
    }

    private static void dispatch(ServerPlayer p, String action, CompoundTag a) {
        TeamStore s = store();
        UUID me = p.getUUID();
        Team team = s.teamOf(me);
        if (action.startsWith("admin_")) {
            admin(p, action, a);
            return;
        }
        switch (action) {
            case "create" -> create(p, a);
            case "join" -> join(p, s.teams.get(a.getUUID("team")));
            case "request" -> request(p, s.teams.get(a.getUUID("team")));
            case "cancel_request" -> cancelRequest(p, s.teams.get(a.getUUID("team")));
            case "invite_reply" -> inviteReply(p, a.getUUID("team"), a.getString("answer"));
            case "restore" -> restore(p);
            case "hide" -> {
                if (a.getBoolean("on")) s.hideCoords.add(me);
                else s.hideCoords.remove(me);
                // остальным координаты перестанет слать присутствие, состояние меняется только у самого игрока
                s.setDirty();
                DIRTY_PLAYERS.add(me);
            }
            case "death_notice" -> {
                if (a.getBoolean("on")) s.quietDeath.remove(me);
                else s.quietDeath.add(me);
                s.setDirty();
                DIRTY_PLAYERS.add(me);
            }
            default -> {
                if (team == null) return;
                teamAction(p, team, action, a);
            }
        }
    }

    private static void teamAction(ServerPlayer p, Team team, String action, CompoundTag a) {
        TeamStore s = store();
        UUID me = p.getUUID();
        String name = p.getGameProfile().getName();
        // в режиме server команда одна на всех: распустить, слить или заключить союз нельзя
        if (TeamsConfig.isServerMode() && (action.equals("disband") || action.startsWith("merge_") || action.startsWith("ally_"))) {
            denied(p);
            return;
        }
        switch (action) {
            case "invite" -> invite(p, team, a.getUUID("player"));
            case "accept_request", "decline_request" -> {
                if (!team.can(me, Permission.ACCEPT_REQUESTS)) {
                    denied(p);
                    return;
                }
                UUID who = a.getUUID("player");
                String asked = team.requests.get(who);
                if (asked == null) return;
                if (action.equals("accept_request") && s.teamOf(who) == null) {
                    if (full(team)) {
                        // заявка остаётся: место может освободиться
                        toast(p, Component.translatable("rysteams.error.full"), true);
                        return;
                    }
                    team.requests.remove(who);
                    addMember(team, who, asked, Role.MEMBER, false);
                } else {
                    team.requests.remove(who);
                }
                touch(team);
            }
            case "leave" -> leave(p, team);
            case "kick" -> {
                UUID who = a.getUUID("player");
                Team.Member m = team.members.get(who);
                if (!team.can(me, Permission.MANAGE) || m == null || who.equals(me)) {
                    denied(p);
                    return;
                }
                if (TeamsConfig.isServerMode()) {
                    // исключённый вернулся бы при следующем входе: вместо исключения роль гостя
                    if (m.role == Role.GUEST) return;
                    m.role = Role.GUEST;
                    feed(team, Component.translatable("rysteams.feed.role", m.name, Component.translatable("rysteams.role." + Role.GUEST.key())));
                    return;
                }
                removeMember(team, who, team.keepCopyOnLeave);
                feed(team, Component.translatable("rysteams.feed.kicked", m.name, name));
                ServerPlayer kicked = online(who);
                if (kicked != null) toast(kicked, Component.translatable("rysteams.toast.kicked", team.name, name), true);
            }
            case "set_role" -> {
                Team.Member m = team.members.get(a.getUUID("player"));
                Role role = Role.parse(a.getString("role"));
                if (!team.can(me, Permission.MANAGE) || m == null || m.id.equals(me) || role == Role.LEADER) {
                    denied(p);
                    return;
                }
                m.role = role;
                feed(team, Component.translatable("rysteams.feed.role", m.name, Component.translatable("rysteams.role." + role.key())));
            }
            case "transfer" -> {
                Team.Member m = team.members.get(a.getUUID("player"));
                if (!team.can(me, Permission.MANAGE) || m == null || m.id.equals(me)) {
                    denied(p);
                    return;
                }
                team.members.get(me).role = Role.OFFICER;
                m.role = Role.LEADER;
                feed(team, Component.translatable("rysteams.feed.leader", m.name));
            }
            case "disband" -> {
                if (!team.can(me, Permission.MANAGE)) {
                    denied(p);
                    return;
                }
                disband(team, me, false);
            }
            case "set_perm" -> {
                String key = a.getString("perm");
                Permission perm = Permission.parse(key);
                Role role = Role.parse(a.getString("role"));
                if (perm == null && TeamsApi.permission(key) != null) {
                    // право чужого мода: столбец лидера закреплён
                    if (!team.can(me, Permission.MANAGE) || role == Role.LEADER) {
                        denied(p);
                        return;
                    }
                    Set<Role> roles = team.modPermissions.computeIfAbsent(key, k -> EnumSet.copyOf(team.modRoles(k)));
                    if (!roles.remove(role)) roles.add(role);
                    touch(team);
                    return;
                }
                if (!team.can(me, Permission.MANAGE) || perm == null || perm.locked() || role == Role.LEADER || TeamsConfig.isLocked(perm)) {
                    denied(p);
                    return;
                }
                Set<Role> roles = team.permissions.computeIfAbsent(perm, k -> EnumSet.of(Role.LEADER));
                if (!roles.remove(role)) roles.add(role);
                touch(team);
            }
            case "reset_perms" -> {
                if (!team.can(me, Permission.MANAGE)) {
                    denied(p);
                    return;
                }
                team.resetPermissions();
                touch(team);
            }
            case "set_identity" -> setIdentity(p, team, a);
            case "set_rule" -> setRule(p, team, a);
            case "note_add" -> {
                if (!board(p, team)) return;
                String text = clip(a.getString("text"));
                if (text.isEmpty()) return;
                if (team.notes.size() >= TeamsConfig.maxNotes()) {
                    limit(p);
                    return;
                }
                team.notes.add(0, new Team.Note(UUID.randomUUID(), text, name, now()));
                touch(team);
            }
            case "note_edit" -> {
                if (!board(p, team)) return;
                String text = clip(a.getString("text"));
                if (text.isEmpty()) return;
                for (Team.Note n : team.notes) if (n.id.equals(a.getUUID("id"))) n.text = text;
                touch(team);
            }
            case "note_del" -> {
                if (!board(p, team)) return;
                team.notes.removeIf(n -> n.id.equals(a.getUUID("id")));
                touch(team);
            }
            case "todo_add" -> {
                if (!board(p, team)) return;
                String text = clip(a.getString("text"));
                if (text.isEmpty()) return;
                if (team.todos.size() >= TeamsConfig.maxTodos()) {
                    limit(p);
                    return;
                }
                team.todos.add(new Team.Todo(UUID.randomUUID(), text, false, "", ""));
                touch(team);
            }
            case "todo_toggle" -> {
                if (!board(p, team)) return;
                for (Team.Todo t : team.todos) {
                    if (t.id.equals(a.getUUID("id"))) {
                        t.done = !t.done;
                        if (t.done) feed(team, Component.translatable("rysteams.feed.todo_done", name, t.text));
                    }
                }
                touch(team);
            }
            case "todo_assign" -> {
                if (!board(p, team)) return;
                for (Team.Todo t : team.todos) if (t.id.equals(a.getUUID("id"))) t.assignee = t.assignee.equals(name) ? "" : name;
                touch(team);
            }
            case "todo_del" -> {
                if (!board(p, team)) return;
                team.todos.removeIf(t -> t.id.equals(a.getUUID("id")));
                touch(team);
            }
            case "mark_add" -> markAdd(p, team, a);
            case "mark_edit" -> markEdit(p, team, a, me, name);
            case "mark_del" -> {
                UUID id = a.getUUID("id");
                Team.Mark mark = null;
                for (Team.Mark m : team.marks) if (m.id().equals(id)) mark = m;
                if (mark == null) return;
                // свою метку автор убирает и без права на метки
                if (!team.can(me, Permission.EDIT_MARKS) && !mark.by().equals(name)) {
                    denied(p);
                    return;
                }
                team.marks.remove(mark);
                touch(team);
            }
            case "ping" -> ping(p, team, a);
            case "ally_offer" -> allyOffer(p, team, s.teams.get(a.getUUID("team")));
            case "ally_accept" -> allyAccept(p, team, a.getUUID("team"), true);
            case "ally_decline" -> allyAccept(p, team, a.getUUID("team"), false);
            case "ally_remove" -> {
                if (!team.can(me, Permission.MANAGE)) {
                    denied(p);
                    return;
                }
                Team other = s.teams.get(a.getUUID("team"));
                team.allies.remove(a.getUUID("team"));
                if (other != null) {
                    other.allies.remove(team.id);
                    touch(other);
                }
                touch(team);
            }
            case "merge_propose" -> mergePropose(p, team, s.teams.get(a.getUUID("team")));
            case "merge_confirm" -> mergeConfirm(p, team);
            case "merge_cancel" -> {
                Merge m = mergeOf(team.id);
                if (m == null) return;
                if (!team.can(me, Permission.MANAGE)) {
                    denied(p);
                    return;
                }
                MERGES.remove(m.into);
                DIRTY.add(m.from);
                DIRTY.add(m.into);
            }
            case "merge_undo" -> mergeUndo(p, team);
            default -> RYSTeams.LOG.debug("Неизвестное действие {}", action);
        }
    }

    /** Измерение действия: из аргумента {@code dim}, если такое есть на сервере (пинг и метка с карты), иначе измерение игрока. */
    private static String dimension(ServerPlayer p, CompoundTag a) {
        if (a.contains("dim")) {
            ResourceLocation id = ResourceLocation.tryParse(a.getString("dim"));
            if (id != null && p.server.levelKeys().stream().anyMatch(k -> k.location().equals(id))) return id.toString();
        }
        return p.level().dimension().location().toString();
    }

    private static void markAdd(ServerPlayer p, Team team, CompoundTag a) {
        if (!team.can(p.getUUID(), Permission.EDIT_MARKS)) {
            denied(p);
            return;
        }
        String title = clip(a.getString("name"));
        if (title.isEmpty()) return;
        // с карты метка ставится в выбранное место (Т2), клавишей и с экрана команды там, где стоит игрок
        BlockPos pos = a.contains("x") ? new BlockPos(a.getInt("x"), a.getInt("y"), a.getInt("z")) : p.blockPosition();
        String dim = dimension(p, a);
        for (Team.Mark m : team.marks) {
            if (m.death()) continue;
            if (m.dimension().equals(dim) && Math.abs(m.x() - pos.getX()) <= 2 && Math.abs(m.y() - pos.getY()) <= 2 && Math.abs(m.z() - pos.getZ()) <= 2) {
                toast(p, Component.translatable("rysteams.error.mark_near", m.name()), true);
                return;
            }
        }
        if (team.permanentMarks() >= TeamsConfig.maxMarks()) {
            limit(p);
            return;
        }
        String name = p.getGameProfile().getName();
        String icon = p.getMainHandItem().isEmpty() ? "minecraft:compass" : itemId(p);
        team.marks.add(new Team.Mark(UUID.randomUUID(), title, dim, pos.getX(), pos.getY(), pos.getZ(), icon, name, ""));
        feed(team, SOURCE_TEAMS, Component.translatable("rysteams.feed.mark", name, title, pos.getX(), pos.getY(), pos.getZ()), icon, false,
            "waypoint:" + dim + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ());
    }

    /** Правка метки из окна карты (Т2): новое имя, место, «кто видит» (команда или команда и союз). Права те же, что на удаление; метку гибели не правят. */
    private static void markEdit(ServerPlayer p, Team team, CompoundTag a, UUID me, String name) {
        UUID id = a.getUUID("id");
        int index = -1;
        for (int i = 0; i < team.marks.size(); i++) if (team.marks.get(i).id().equals(id)) index = i;
        if (index < 0) return;
        Team.Mark m = team.marks.get(index);
        if (m.death()) return;
        if (!team.can(me, Permission.EDIT_MARKS) && !m.by().equals(name)) {
            denied(p);
            return;
        }
        String title = a.contains("name") ? clip(a.getString("name")) : m.name();
        if (title.isEmpty()) return;
        int x = a.contains("x") ? a.getInt("x") : m.x(), y = a.contains("y") ? a.getInt("y") : m.y(), z = a.contains("z") ? a.getInt("z") : m.z();
        boolean allies = a.contains("allies") ? a.getBoolean("allies") : m.allies();
        if (title.equals(m.name()) && x == m.x() && y == m.y() && z == m.z() && allies == m.allies()) return;
        team.marks.set(index, new Team.Mark(m.id(), title, m.dimension(), x, y, z, m.icon(), m.by(), m.source(), m.expires(), m.owner(), allies));
        touch(team);
    }

    private static String itemId(ServerPlayer p) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString();
    }

    private static boolean board(ServerPlayer p, Team team) {
        if (team.can(p.getUUID(), Permission.EDIT_BOARD)) return true;
        denied(p);
        return false;
    }

    /** Без кодов форматирования: текст приходит в пакете, мимо фильтра символов ванильного чата. Управляющие символы становятся пробелами. */
    static String clean(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') continue;
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString();
    }

    static String clip(String text) {
        String t = clean(text).strip();
        if (t.length() <= TEXT_MAX) return t;
        t = t.substring(0, TEXT_MAX);
        return Character.isHighSurrogate(t.charAt(t.length() - 1)) ? t.substring(0, t.length() - 1) : t;
    }

    static String normName(String raw) {
        return clean(raw).strip().replaceAll("\\s+", " ");
    }

    private static boolean validName(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§' || Character.isISOControl(c)) return false;
        }
        int n = normName(raw).length();
        return n >= NAME_MIN && n <= NAME_MAX;
    }

    /** Значок команды: id предмета, а не произвольная строка. */
    private static String icon(String raw, String fallback) {
        if (raw.length() > ICON_MAX || ResourceLocation.tryParse(raw) == null) return fallback;
        return raw;
    }

    private static boolean nameTaken(String name, Team except) {
        String n = normName(name);
        for (Team t : store().teams.values()) if (t != except && t.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    /** Свободное название: при совпадении дописывается « (2)», « (3)» и так далее. */
    static String freeName(String name, Team except) {
        return freeNameBy(name, n -> nameTaken(n, except));
    }

    /** То же с проверкой «занято» снаружи: перенос с FTB считает и команды, которые ещё не легли в хранилище. */
    static String freeNameBy(String name, java.util.function.Predicate<String> taken) {
        if (!taken.test(name)) return name;
        for (int i = 2; ; i++) {
            String suffix = " (" + i + ")";
            String base = name.length() + suffix.length() > NAME_MAX ? name.substring(0, Math.max(1, NAME_MAX - suffix.length())).strip() : name;
            if (!taken.test(base + suffix)) return base + suffix;
        }
    }

    private static void create(ServerPlayer p, CompoundTag a) {
        TeamStore s = store();
        UUID me = p.getUUID();
        if (s.teamOf(me) != null || TeamsConfig.isServerMode()) return;
        String raw = a.getString("name");
        if (!validName(raw)) {
            toast(p, Component.translatable("rysteams.error.name"), true);
            return;
        }
        String name = normName(raw);
        if (nameTaken(name, null)) {
            toast(p, Component.translatable("rysteams.error.taken"), true);
            return;
        }
        Team team = new Team(UUID.randomUUID(), name, a.getInt("color") & 0xFFFFFF, icon(a.getString("icon"), "minecraft:white_banner"), now());
        if (TeamsConfig.isOpenMode()) team.join = Team.JoinMode.OPEN;
        s.teams.put(team.id, team);
        INVITES.remove(me);
        addMember(team, me, p.getGameProfile().getName(), Role.LEADER, true);
        feed(team, Component.translatable("rysteams.feed.created", p.getGameProfile().getName()));
        touchLobby();
    }

    private static boolean full(Team team) {
        int max = TeamsConfig.maxMembers();
        return max > 0 && team.members.size() >= max;
    }

    private static void addMember(Team team, UUID id, String name, Role role, boolean carry) {
        TeamStore s = store();
        team.members.put(id, new Team.Member(id, name, role, now(), now()));
        s.byPlayer.put(id, team.id);
        // игрок в команде: его заявки в другие команды и приглашения больше не нужны
        for (Team other : s.teams.values()) if (other != team && other.requests.remove(id) != null) touch(other);
        INVITES.remove(id);
        Presence.resend(team.members.keySet());
        for (TeamsApi.Listener l : TeamsApi.listeners()) {
            try {
                l.onJoined(team.id, id, carry);
            } catch (Throwable t) {
                RYSTeams.LOG.warn("Слушатель вступления упал", t);
            }
        }
        if (role != Role.LEADER) feed(team, Component.translatable("rysteams.feed.joined", name));
        touch(team);
        touchLobby();
    }

    private static void join(ServerPlayer p, Team team) {
        if (team == null || store().teamOf(p.getUUID()) != null) return;
        if (team.join != Team.JoinMode.OPEN) {
            denied(p);
            return;
        }
        if (full(team)) {
            toast(p, Component.translatable("rysteams.error.full"), true);
            return;
        }
        addMember(team, p.getUUID(), p.getGameProfile().getName(), Role.MEMBER, true);
    }

    private static void request(ServerPlayer p, Team team) {
        UUID me = p.getUUID();
        if (team == null || store().teamOf(me) != null || team.join == Team.JoinMode.INVITE) return;
        // повтор не пишет в ленту: иначе посторонний вытеснил бы историю чужой команды
        if (team.requests.containsKey(me)) {
            DIRTY_PLAYERS.add(me);
            return;
        }
        if (cooldown(p, "request:" + team.id, REQUEST_AGAIN_MS)) return;
        team.requests.put(me, p.getGameProfile().getName());
        feed(team, Component.translatable("rysteams.feed.request", p.getGameProfile().getName()));
        toast(p, Component.translatable("rysteams.toast.request_sent", team.name), false);
        DIRTY_PLAYERS.add(me);
    }

    private static void cancelRequest(ServerPlayer p, Team team) {
        if (team == null || team.requests.remove(p.getUUID()) == null) return;
        touch(team);
        DIRTY_PLAYERS.add(p.getUUID());
    }

    private static void invite(ServerPlayer p, Team team, UUID target) {
        if (!team.can(p.getUUID(), Permission.INVITE)) {
            denied(p);
            return;
        }
        ServerPlayer other = online(target);
        if (other == null) return;
        if (store().teamOf(target) != null) {
            toast(p, Component.translatable("rysteams.error.has_team", other.getGameProfile().getName()), true);
            return;
        }
        if (full(team)) {
            toast(p, Component.translatable("rysteams.error.full"), true);
            return;
        }
        if (cooldown(p, "invite:" + target, INVITE_AGAIN_MS)) return;
        List<Invite> list = INVITES.computeIfAbsent(target, k -> new ArrayList<>());
        list.removeIf(i -> i.team.equals(team.id));
        list.add(new Invite(team.id, p.getGameProfile().getName(), now() + INVITE_MS));
        // в чате остаётся ссылка: сработает, даже если подсказка уже закрыта
        MutableComponent link = Component.literal("[" + team.name + "]").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(team.color)).withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rysteams accept " + team.id)));
        other.sendSystemMessage(Component.translatable("rysteams.chat.invited", p.getGameProfile().getName(), link));
        toast(p, Component.translatable("rysteams.toast.invite_sent", other.getGameProfile().getName()), false);
        DIRTY_PLAYERS.add(target);
    }

    public static void inviteReply(ServerPlayer p, UUID teamId, String answer) {
        TeamStore s = store();
        List<Invite> list = INVITES.get(p.getUUID());
        if (list == null) return;
        Invite found = null;
        for (Invite i : list) if (i.team.equals(teamId) && i.expires >= now()) found = i;
        list.removeIf(i -> i.team.equals(teamId));
        DIRTY_PLAYERS.add(p.getUUID());
        if (found == null) return;
        Team team = s.teams.get(teamId);
        if (!answer.equals("no") && team != null && s.teamOf(p.getUUID()) == null) {
            if (full(team)) {
                toast(p, Component.translatable("rysteams.error.full"), true);
                return;
            }
            addMember(team, p.getUUID(), p.getGameProfile().getName(), Role.MEMBER, answer.equals("accept"));
        }
    }

    private static Team.Member successor(Team team, UUID except) {
        Team.Member best = null;
        for (Team.Member m : team.members.values()) {
            if (m.id.equals(except)) continue;
            if (best == null || m.role.ordinal() < best.role.ordinal()) best = m;
        }
        return best;
    }

    /** У команды должен быть лидер: без него право MANAGE не получает никто. */
    static void ensureLeader(Team team) {
        for (Team.Member m : team.members.values()) if (m.role == Role.LEADER) return;
        Team.Member next = successor(team, null);
        if (next != null) next.role = Role.LEADER;
    }

    private static void removeMember(Team team, UUID id, boolean keepCopy) {
        TeamStore s = store();
        Presence.resend(team.members.keySet());
        team.members.remove(id);
        s.byPlayer.remove(id);
        // метка гибели нужна команде, чтобы прийти на помощь; ушедшему она больше не поможет
        team.marks.removeIf(m -> m.death() && id.equals(m.owner()));
        for (TeamsApi.Listener l : TeamsApi.listeners()) {
            try {
                l.onLeft(team.id, id, keepCopy);
            } catch (Throwable t) {
                RYSTeams.LOG.warn("Слушатель ухода упал", t);
            }
        }
        touch(team);
        touchLobby();
    }

    private static void leave(ServerPlayer p, Team team) {
        UUID me = p.getUUID();
        Team.Member self = team.members.get(me);
        if (self == null || TeamsConfig.isServerMode()) return;
        if (self.role == Role.LEADER) {
            Team.Member next = successor(team, me);
            if (next == null) {
                disband(team, me, false);
                return;
            }
            next.role = Role.LEADER;
            feed(team, Component.translatable("rysteams.feed.leader", next.name));
        }
        removeMember(team, me, team.keepCopyOnLeave);
        feed(team, Component.translatable("rysteams.feed.left", self.name));
    }

    /**
     * Роспуск: команда уходит в архив, каждый участник уходит из неё с копией прогресса.
     * @param byAdmin распустил администратор: вернуть команду может только он
     */
    private static void disband(Team team, UUID leader, boolean byAdmin) {
        TeamStore s = store();
        CompoundTag snapshot = team.save(false);
        ListTag feed = snapshot.getList("feed", Tag.TAG_COMPOUND);
        while (feed.size() > ARCHIVE_FEED) feed.remove(feed.size() - 1);
        s.archive.add(new TeamStore.Archived(snapshot, leader, now(), byAdmin));
        MERGES.values().removeIf(m -> m.involves(team.id));
        UNDOS.remove(team.id);
        ALLY_OFFERS.remove(team.id);
        for (Set<UUID> offers : ALLY_OFFERS.values()) offers.remove(team.id);
        Presence.resend(team.members.keySet());
        for (UUID id : new ArrayList<>(team.members.keySet())) {
            team.members.remove(id);
            s.byPlayer.remove(id);
            // при роспуске копия остаётся всегда, независимо от правила «при уходе»
            for (TeamsApi.Listener l : TeamsApi.listeners()) {
                try {
                    l.onLeft(team.id, id, true);
                } catch (Throwable t) {
                    RYSTeams.LOG.warn("Слушатель ухода упал", t);
                }
            }
            ServerPlayer player = online(id);
            if (player == null) continue;
            boolean canRestore = !byAdmin && id.equals(leader);
            toast(player, Component.translatable(canRestore ? "rysteams.toast.disbanded_restore" : "rysteams.toast.disbanded", team.name), !canRestore);
        }
        s.teams.remove(team.id);
        for (Team other : s.teams.values()) if (other.allies.remove(team.id)) touch(other);
        for (TeamsApi.Listener l : TeamsApi.listeners()) {
            try {
                l.onDisbanded(team.id);
            } catch (Throwable t) {
                RYSTeams.LOG.warn("Слушатель распада упал", t);
            }
        }
        touchLobby();
    }

    /** Вернувшимся из архива: слушатели сливают личный прогресс, сделанный после роспуска, обратно в команду. */
    private static void rejoined(Team team) {
        for (UUID id : team.members.keySet()) {
            for (TeamsApi.Listener l : TeamsApi.listeners()) {
                try {
                    l.onJoined(team.id, id, true);
                } catch (Throwable t) {
                    RYSTeams.LOG.warn("Слушатель вступления упал", t);
                }
            }
        }
        int max = TeamsConfig.maxMembers();
        if (max > 0 && team.members.size() > max) RYSTeams.LOG.warn("Команда {} вернулась из архива сверх лимита: {} из {}", team.name, team.members.size(), max);
    }

    // --- администратор ---

    private static CompoundTag adminRow(UUID id, String name, int color, String icon, int count, String leader, long time) {
        CompoundTag x = new CompoundTag();
        x.putUUID("id", id);
        x.putString("name", name);
        x.putInt("color", color);
        x.putString("icon", icon);
        x.putInt("count", count);
        x.putString("leader", leader);
        x.putLong("time", time);
        return x;
    }

    public static void sendAdmin(ServerPlayer p) {
        TeamStore s = store();
        CompoundTag out = new CompoundTag();
        out.putString("type", "admin");
        // импорт требует уровня 3, как и команда /rysteams import ftb
        out.putBoolean("ftb", FtbImport.available(server) && !s.ftbImported && p.hasPermissions(3));
        ListTag teams = new ListTag();
        for (Team t : s.teams.values()) {
            Team.Member leader = t.leader();
            teams.add(adminRow(t.id, t.name, t.color, t.icon, t.members.size(), leader == null ? "" : leader.name, t.created));
        }
        out.put("teams", teams);
        ListTag archive = new ListTag();
        for (TeamStore.Archived a : s.archive) {
            Team t = Team.load(a.team());
            Team.Member leader = t.members.get(a.leader());
            archive.add(adminRow(t.id, t.name, t.color, t.icon, t.members.size(), leader == null ? "" : leader.name, a.time()));
        }
        out.put("archive", archive);
        ModNetwork.send(p, out);
    }

    private static void admin(ServerPlayer p, String action, CompoundTag a) {
        if (!p.hasPermissions(2)) {
            denied(p);
            return;
        }
        TeamStore s = store();
        switch (action) {
            case "admin_disband" -> {
                Team team = s.teams.get(a.getUUID("team"));
                if (team == null) return;
                Team.Member leader = team.leader();
                disband(team, leader == null ? p.getUUID() : leader.id, true);
            }
            case "admin_restore" -> {
                for (TeamStore.Archived entry : new ArrayList<>(s.archive)) {
                    if (entry.team().getUUID("id").equals(a.getUUID("team"))) {
                        unarchive(entry);
                        break;
                    }
                }
            }
            case "admin_import" -> {
                if (!p.hasPermissions(3)) {
                    denied(p);
                    return;
                }
                works.repka.ryscore.migrate.Migrations.Outcome o = works.repka.ryscore.migrate.Migrations.runManual(TeamsMigration.ID, server);
                toast(p, importMessage(o), o.status() == works.repka.ryscore.migrate.Migrations.Status.FAILED);
            }
            default -> {
                return;
            }
        }
        sendAdmin(p);
    }

    /** Возвращает команду из архива. Кто успел вступить в другую команду, остаётся там. */
    private static Team unarchive(TeamStore.Archived entry) {
        TeamStore s = store();
        Team team = Team.load(entry.team());
        s.archive.remove(entry);
        s.setDirty();
        if (s.teams.containsKey(team.id)) return null;
        team.members.keySet().removeIf(s.byPlayer::containsKey);
        if (team.members.isEmpty()) return null;
        for (Team.Member m : team.members.values()) if (m.role == Role.LEADER) m.role = Role.OFFICER;
        if (team.members.get(entry.leader()) != null) team.members.get(entry.leader()).role = Role.LEADER;
        else ensureLeader(team);
        team.name = freeName(team.name, team);
        s.teams.put(team.id, team);
        for (UUID id : team.members.keySet()) s.byPlayer.put(id, team.id);
        rejoined(team);
        touchLobby();
        return team;
    }

    private static void restore(ServerPlayer p) {
        TeamStore s = store();
        UUID me = p.getUUID();
        if (s.teamOf(me) != null) return;
        for (Iterator<TeamStore.Archived> it = s.archive.iterator(); it.hasNext(); ) {
            TeamStore.Archived a = it.next();
            // роспуск администратором лидер отменить не может
            if (!a.leader().equals(me) || a.byAdmin()) continue;
            Team team = Team.load(a.team());
            if (s.teams.containsKey(team.id)) return;
            team.members.keySet().removeIf(id -> s.byPlayer.containsKey(id));
            if (!team.members.containsKey(me)) team.members.put(me, new Team.Member(me, p.getGameProfile().getName(), Role.LEADER, now(), now()));
            team.name = freeName(team.name, team);
            s.teams.put(team.id, team);
            for (UUID id : team.members.keySet()) s.byPlayer.put(id, team.id);
            it.remove();
            rejoined(team);
            feed(team, Component.translatable("rysteams.feed.restored", p.getGameProfile().getName()));
            touchLobby();
            return;
        }
    }

    private static void setIdentity(ServerPlayer p, Team team, CompoundTag a) {
        if (!team.can(p.getUUID(), Permission.MANAGE)) {
            denied(p);
            return;
        }
        if (a.contains("name")) {
            String raw = a.getString("name");
            if (!validName(raw)) {
                toast(p, Component.translatable("rysteams.error.name"), true);
                return;
            }
            if (nameTaken(raw, team)) {
                toast(p, Component.translatable("rysteams.error.taken"), true);
                return;
            }
            team.name = normName(raw);
        }
        if (a.contains("color")) team.color = a.getInt("color") & 0xFFFFFF;
        if (a.contains("icon")) team.icon = icon(a.getString("icon"), team.icon);
        touch(team);
        touchLobby();
    }

    private static void setRule(ServerPlayer p, Team team, CompoundTag a) {
        if (!team.can(p.getUUID(), Permission.MANAGE)) {
            denied(p);
            return;
        }
        String rule = a.getString("rule");
        switch (rule) {
            case "join" -> team.join = Team.JoinMode.values()[Math.floorMod(team.join.ordinal() + 1, Team.JoinMode.values().length)];
            case "keep" -> team.keepCopyOnLeave = !team.keepCopyOnLeave;
            case "auto" -> {
                int[] steps = {0, 7, 14, 30};
                int next = 0;
                for (int i = 0; i < steps.length; i++) if (steps[i] == team.autoTransferDays) next = (i + 1) % steps.length;
                team.autoTransferDays = steps[next];
            }
            default -> {
                return;
            }
        }
        touch(team);
        // из правил в сводке лобби есть только способ вступления
        if (rule.equals("join")) touchLobby();
    }

    private static void ping(ServerPlayer p, Team team, CompoundTag a) {
        if (!team.can(p.getUUID(), Permission.PING)) {
            denied(p);
            return;
        }
        if (cooldown(p, "ping", PING_MS)) return;
        CompoundTag x = new CompoundTag();
        x.putString("type", "ping");
        x.putString("by", p.getGameProfile().getName());
        x.putInt("color", team.color);
        x.putString("kind", pingKind(a.getString("kind")));
        // с карты пинг может уйти в другое измерение (Т2): только в существующее
        x.putString("dim", dimension(p, a));
        x.putInt("x", a.getInt("x"));
        x.putInt("y", a.getInt("y"));
        x.putInt("z", a.getInt("z"));
        Set<UUID> targets = new LinkedHashSet<>(team.members.keySet());
        TeamStore s = store();
        for (UUID ally : team.allies) {
            Team t = s.teams.get(ally);
            if (t != null) targets.addAll(t.members.keySet());
        }
        for (UUID id : targets) {
            ServerPlayer target = online(id);
            if (target != null) ModNetwork.send(target, x);
        }
    }

    private static String pingKind(String kind) {
        return PING_KINDS.contains(kind) ? kind : "here";
    }

    private static void allyOffer(ServerPlayer p, Team team, Team other) {
        if (other == null || other == team || team.allies.contains(other.id)) return;
        if (!team.can(p.getUUID(), Permission.MANAGE)) {
            denied(p);
            return;
        }
        if (ALLY_OFFERS.getOrDefault(team.id, Set.of()).contains(other.id)) {
            allyAccept(p, team, other.id, true);
            return;
        }
        Set<UUID> offers = ALLY_OFFERS.computeIfAbsent(other.id, k -> new LinkedHashSet<>());
        if (offers.contains(team.id)) {
            toast(p, Component.translatable("rysteams.toast.ally_sent", other.name), false);
            return;
        }
        if (cooldown(p, "ally:" + other.id, REQUEST_AGAIN_MS)) return;
        offers.add(team.id);
        feed(other, Component.translatable("rysteams.feed.ally_offer", team.name));
        toast(p, Component.translatable("rysteams.toast.ally_sent", other.name), false);
    }

    private static void allyAccept(ServerPlayer p, Team team, UUID otherId, boolean yes) {
        if (!team.can(p.getUUID(), Permission.MANAGE)) {
            denied(p);
            return;
        }
        Set<UUID> offers = ALLY_OFFERS.get(team.id);
        if (offers == null || !offers.remove(otherId)) return;
        Team other = store().teams.get(otherId);
        if (other != null && yes) {
            team.allies.add(otherId);
            other.allies.add(team.id);
            feed(team, Component.translatable("rysteams.feed.ally", other.name));
            feed(other, Component.translatable("rysteams.feed.ally", team.name));
        }
        touch(team);
    }

    // --- слияние: подтверждают оба лидера, 30 секунд можно откатить ---

    private static boolean mergeTooBig(Team a, Team b) {
        int max = TeamsConfig.maxMembers();
        return max > 0 && a.members.size() + b.members.size() > max;
    }

    /** Доска объединённой команды не должна выйти за пределы: иначе trim молча срезал бы записи поглощённой. */
    private static Component mergeOverflow(Team a, Team b) {
        int notes = a.notes.size() + b.notes.size() - TeamsConfig.maxNotes();
        if (notes > 0) return Component.translatable("rysteams.error.merge_notes", notes);
        int todos = a.todos.size() + b.todos.size() - TeamsConfig.maxTodos();
        if (todos > 0) return Component.translatable("rysteams.error.merge_todos", todos);
        int marks = a.permanentMarks() + b.permanentMarks() - TeamsConfig.maxMarks();
        if (marks > 0) return Component.translatable("rysteams.error.merge_marks", marks);
        return null;
    }

    private static void mergePropose(ServerPlayer p, Team team, Team other) {
        if (other == null || other == team) return;
        if (!team.can(p.getUUID(), Permission.MANAGE)) {
            denied(p);
            return;
        }
        if (mergeOf(team.id) != null || mergeOf(other.id) != null || UNDOS.containsKey(team.id) || UNDOS.containsKey(other.id)) {
            toast(p, Component.translatable("rysteams.error.merge_busy"), true);
            return;
        }
        if (mergeTooBig(team, other)) {
            toast(p, Component.translatable("rysteams.error.merge_full", TeamsConfig.maxMembers()), true);
            return;
        }
        Component over = mergeOverflow(team, other);
        if (over != null) {
            toast(p, over, true);
            return;
        }
        // предложение и отмена по кругу не должны вытеснять ленту чужой команды
        long t = now();
        // ключ по игроку: пересоздание команды (новый id) кулдаун не обходит
        String pair = p.getUUID() + ":" + other.id;
        Long until = MERGE_AGAIN.get(pair);
        if (until != null && until > t) {
            tooFast(p);
            return;
        }
        if (MERGE_AGAIN.size() > 64) MERGE_AGAIN.values().removeIf(v -> v <= t);
        MERGE_AGAIN.put(pair, t + REQUEST_AGAIN_MS);
        Merge m = new Merge(other.id, team.id, now() + MERGE_MS);
        m.intoOk = true;
        MERGES.put(team.id, m);
        Team.Member leader = other.leader();
        feed(other, Component.translatable("rysteams.feed.merge_offer", team.name));
        if (leader != null && online(leader.id) != null) toast(online(leader.id), Component.translatable("rysteams.feed.merge_offer", team.name), false);
        touch(team);
    }

    private static void mergeConfirm(ServerPlayer p, Team team) {
        Merge m = mergeOf(team.id);
        if (m == null) return;
        if (!team.can(p.getUUID(), Permission.MANAGE)) {
            denied(p);
            return;
        }
        if (team.id.equals(m.from)) m.fromOk = true;
        else m.intoOk = true;
        DIRTY.add(m.from);
        DIRTY.add(m.into);
        if (!m.fromOk || !m.intoOk) return;
        TeamStore s = store();
        Team from = s.teams.get(m.from), into = s.teams.get(m.into);
        Component refusal = null;
        if (from != null && into != null && mergeTooBig(from, into)) {
            refusal = Component.translatable("rysteams.error.merge_full", TeamsConfig.maxMembers());
        } else if (from != null && into != null) {
            // пока ждали второго лидера, доски могли пополниться
            refusal = mergeOverflow(from, into);
        }
        if (refusal != null) {
            // предложение снимается целиком, как и обещает текст «предложите снова»: иначе оба флага остались бы true,
            // кнопки «Подтвердить» ни у кого нет, а новое предложение упирается в merge_busy и кулдаун
            MERGES.remove(m.into);
            // Merge(from, into) создаётся как new Merge(цель, предлагающий): кулдаун предлагающего записан на цель, то есть на m.from
            String suffix = ":" + m.from;
            MERGE_AGAIN.keySet().removeIf(k -> k.endsWith(suffix));
            toast(p, refusal, true);
            Team other = team.id.equals(m.from) ? into : from;
            if (other != null) {
                Team.Member leader = other.leader();
                ServerPlayer leaderOnline = leader == null ? null : online(leader.id);
                if (leaderOnline != null && leaderOnline != p) toast(leaderOnline, refusal, true);
            }
            return;
        }
        executeMerge(m);
    }

    private static void executeMerge(Merge m) {
        TeamStore s = store();
        MERGES.remove(m.into);
        Team from = s.teams.get(m.from), into = s.teams.get(m.into);
        UUID fromId = m.from, intoId = m.into;
        if (from == null || into == null) return;
        UNDOS.put(intoId, new Undo(from.save(false), into.save(false), fromId, intoId, now() + UNDO_MS));
        for (Team.Member member : from.members.values()) {
            if (member.role == Role.LEADER) member.role = Role.OFFICER;
            into.members.put(member.id, member);
            s.byPlayer.put(member.id, into.id);
        }
        Presence.resend(into.members.keySet());
        into.marks.addAll(from.marks);
        into.notes.addAll(from.notes);
        into.todos.addAll(from.todos);
        // пределы проверены при предложении и подтверждении, здесь trim ничего не срезает
        trim(into);
        into.allies.addAll(from.allies);
        into.allies.remove(into.id);
        into.allies.remove(from.id);
        List<Team.Feed> merged = new ArrayList<>(into.feed);
        merged.addAll(from.feed);
        merged.sort((a, b) -> Long.compare(b.time(), a.time()));
        into.feed.clear();
        for (Team.Feed f : merged) if (into.feed.size() < Team.FEED_LIMIT) into.feed.addLast(f);
        for (String key : from.modData.getAllKeys()) if (!into.modData.contains(key)) into.modData.put(key, from.modData.get(key));
        s.teams.remove(from.id);
        ALLY_OFFERS.remove(fromId);
        for (Set<UUID> offers : ALLY_OFFERS.values()) offers.remove(fromId);
        for (Team other : s.teams.values()) {
            if (other.allies.remove(fromId)) {
                other.allies.add(intoId);
                touch(other);
            }
        }
        for (TeamsApi.Listener l : TeamsApi.listeners()) {
            try {
                l.onMerged(fromId, intoId);
            } catch (Throwable t) {
                RYSTeams.LOG.warn("Слушатель слияния упал", t);
            }
        }
        feed(into, Component.translatable("rysteams.feed.merged", from.name));
        touchLobby();
    }

    private static void mergeUndo(ServerPlayer p, Team team) {
        Undo u = UNDOS.get(team.id);
        if (u == null || u.deadline < now()) return;
        if (!team.can(p.getUUID(), Permission.MANAGE)) {
            denied(p);
            return;
        }
        TeamStore s = store();
        UNDOS.remove(team.id);
        Team from = Team.load(u.from), into = Team.load(u.into);
        // возвращаются только те, кто сейчас в объединённой команде; ушедшие за эти секунды остаются, где они есть
        Set<UUID> current = new HashSet<>(team.members.keySet());
        for (UUID id : current) s.byPlayer.remove(id);
        from.members.keySet().retainAll(current);
        into.members.keySet().retainAll(current);
        ensureLeader(from);
        ensureLeader(into);
        s.teams.remove(team.id);
        // за эти секунды название могли занять: новая команда или переименование объединённой.
        // freeName только после remove: объединённая команда с тем же id ещё дала бы лишний суффикс
        if (!into.members.isEmpty()) {
            into.name = freeName(into.name, into);
            s.teams.put(into.id, into);
        }
        if (!from.members.isEmpty()) {
            from.name = freeName(from.name, from);
            s.teams.put(from.id, from);
        }
        for (UUID id : from.members.keySet()) s.byPlayer.put(id, from.id);
        for (UUID id : into.members.keySet()) s.byPlayer.put(id, into.id);
        // союзы других команд снова смотрят на прежние команды
        for (Team other : s.teams.values()) {
            if (other == from || other == into) continue;
            boolean changed = false;
            if (from.allies.contains(other.id)) changed |= other.allies.add(from.id);
            if (!into.allies.contains(other.id)) changed |= other.allies.remove(into.id);
            if (changed) touch(other);
        }
        Presence.resend(current);
        // вступившие после слияния остаются без команды
        for (UUID id : current) {
            if (s.byPlayer.containsKey(id)) continue;
            for (TeamsApi.Listener l : TeamsApi.listeners()) {
                try {
                    l.onLeft(u.intoId, id, true);
                } catch (Throwable t) {
                    RYSTeams.LOG.warn("Слушатель ухода упал", t);
                }
            }
        }
        for (TeamsApi.Listener l : TeamsApi.listeners()) {
            try {
                l.onMergeUndone(u.fromId, u.intoId);
            } catch (Throwable t) {
                RYSTeams.LOG.warn("Слушатель отката слияния упал", t);
            }
        }
        if (s.teams.containsKey(into.id)) feed(into, Component.translatable("rysteams.feed.merge_undone"));
        touchLobby();
    }

    /** Режим server: общая команда хранится по id, новичок попадает именно в неё. */
    private static void joinServerTeam(ServerPlayer p) {
        TeamStore s = store();
        Team team = s.serverTeam == null ? null : s.teams.get(s.serverTeam);
        if (team == null) {
            // мир с прежней версии: общая команда узнаётся по названию из настроек
            for (Team t : s.teams.values()) if (t.name.equalsIgnoreCase(TeamsConfig.serverTeamName())) team = t;
        }
        boolean first = team == null || team.members.isEmpty();
        if (team == null) {
            team = new Team(UUID.randomUUID(), freeName(normName(TeamsConfig.serverTeamName()), null), Team.COLORS[1], "minecraft:beacon", now());
            s.teams.put(team.id, team);
        }
        if (!team.id.equals(s.serverTeam)) {
            s.serverTeam = team.id;
            s.setDirty();
        }
        addMember(team, p.getUUID(), p.getGameProfile().getName(), first ? Role.LEADER : Role.MEMBER, true);
    }

    // --- гибель участника ---

    /**
     * Участник погиб: запись в ленте с тревогой и метка для всей команды. Сервер решает по своим данным:
     * отказ от сообщения и скрытие координат хранятся здесь, а не приходят от клиента.
     */
    public static void onDeath(ServerPlayer p) {
        if (server == null) return;
        TeamStore s = store();
        UUID id = p.getUUID();
        Team team = s.teamOf(id);
        if (team == null || !team.members.containsKey(id) || s.quietDeath.contains(id)) return;
        String name = p.getGameProfile().getName();
        String dim = p.level().dimension().location().toString();
        BlockPos pos = p.blockPosition();
        boolean hidden = s.hideCoords.contains(id);
        // одна метка на игрока: новая гибель заменяет прежнюю
        boolean changed = team.marks.removeIf(m -> m.death() && id.equals(m.owner()));
        long t = now();
        if (!hidden) {
            team.marks.add(new Team.Mark(UUID.randomUUID(), name, dim, pos.getX(), pos.getY(), pos.getZ(), DEATH_ICON, name,
                Team.SOURCE_DEATH, t + DEATH_MARK_MS, id));
            changed = true;
        }
        Long until = DEATH_FEED.get(id);
        if (until == null || until <= t) {
            if (DEATH_FEED.size() > 256) DEATH_FEED.values().removeIf(v -> v <= t);
            DEATH_FEED.put(id, t + DEATH_FEED_MS);
            Component text = hidden ? Component.translatable("rysteams.feed.death_hidden", name, dimText(dim))
                : Component.translatable("rysteams.feed.death", name, dimText(dim), pos.getX(), pos.getY(), pos.getZ());
            feed(team, SOURCE_TEAMS, text, DEATH_ICON, true,
                hidden ? "" : "waypoint:" + dim + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ());
        } else if (changed) {
            touch(team);
        }
    }

    private static final String DEATH_ICON = "minecraft:skeleton_skull";

    /** Название измерения для ленты: у ванильных перевод, у остальных путь id, как на клиенте. */
    private static Component dimText(String id) {
        return switch (id) {
            case "minecraft:overworld" -> Component.translatable("rysteams.dim.overworld");
            case "minecraft:the_nether" -> Component.translatable("rysteams.dim.nether");
            case "minecraft:the_end" -> Component.translatable("rysteams.dim.end");
            default -> Component.literal(id.substring(id.indexOf(':') + 1).replace('_', ' '));
        };
    }

    // --- чат команды ---

    /** @return true, если сообщение ушло в канал команды и в общий чат его пускать не нужно */
    public static boolean chat(ServerPlayer p, String message) {
        // «!!» и следом не «!»: союз; «!» и следом не пробел и не «!»: команда. «!!!» и «! текст» идут в общий чат как есть
        boolean ally = message.length() > 2 && message.startsWith("!!") && message.charAt(2) != '!';
        boolean own = !ally && message.length() > 1 && message.charAt(0) == '!' && message.charAt(1) != '!' && !Character.isWhitespace(message.charAt(1));
        if (!ally && !own) return false;
        TeamStore s = store();
        Team team = s.teamOf(p.getUUID());
        // без команды сообщение уходит в общий чат: «!» бывает и у команд чат-ботов
        if (team == null) return false;
        String text = message.substring(ally ? 2 : 1).strip();
        if (text.isEmpty()) return true;
        String name = p.getGameProfile().getName();
        RYSTeams.LOG.info("[{}{}] {}: {}", ally ? "+" : "", team.name, name, text);
        MutableComponent line = Component.literal("[" + team.name + "] ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(team.color)).withBold(true))
            .append(Component.literal(name + ": ").withStyle(Style.EMPTY.withBold(false).withColor(TextColor.fromRgb(team.color))))
            .append(Component.literal(text).withStyle(Style.EMPTY.withBold(false).withColor(ChatFormatting.WHITE)));
        Collection<UUID> targets = new LinkedHashSet<>(team.members.keySet());
        if (ally) {
            for (UUID id : team.allies) {
                Team t = s.teams.get(id);
                if (t != null) targets.addAll(t.members.keySet());
            }
        }
        for (UUID id : targets) {
            ServerPlayer target = online(id);
            if (target != null) target.sendSystemMessage(line);
        }
        return true;
    }

    // --- API ---

    public static void post(UUID teamId, TeamsApi.Post post) {
        if (server == null) return;
        Team team = store().teams.get(teamId);
        if (team == null) return;
        ResourceLocation icon = post.icon();
        feed(team, post.source().getPath(), post.text(), icon == null ? "" : icon.toString(), post.alert(), "");
    }

    public static void addTodo(UUID teamId, String source, String text) {
        if (server == null) return;
        Team team = store().teams.get(teamId);
        if (team == null) return;
        String clipped = clip(text);
        if (clipped.isEmpty() || team.todos.size() >= TeamsConfig.maxTodos()) return;
        team.todos.add(new Team.Todo(UUID.randomUUID(), clipped, false, "", source));
        touch(team);
    }

    public static void pingFromMod(UUID teamId, String dimension, int x, int y, int z, String kind, String by) {
        if (server == null) return;
        Team team = store().teams.get(teamId);
        if (team == null) return;
        CompoundTag out = new CompoundTag();
        out.putString("type", "ping");
        out.putString("by", by == null || by.isBlank() ? "?" : clean(by));
        out.putInt("color", team.color);
        out.putString("kind", pingKind(kind));
        out.putString("dim", dimension);
        out.putInt("x", x);
        out.putInt("y", y);
        out.putInt("z", z);
        for (UUID id : team.members.keySet()) {
            ServerPlayer target = online(id);
            if (target != null) ModNetwork.send(target, out);
        }
    }

    /** Импортированные команды появляются сразу у всех. */
    /** Ответ администратору на перенос с FTB: счётчики шага или строка ядра, почему он не шёл. */
    public static Component importMessage(works.repka.ryscore.migrate.Migrations.Outcome o) {
        var r = o.report();
        if (!o.ran() || r == null) return Component.literal(o.toString());
        return Component.translatable("rysteams.admin.imported", r.counts().getOrDefault("teams", 0L), r.counts().getOrDefault("players", 0L), r.skipped().size());
    }

    public static void imported() {
        if (!running()) return;
        store().setDirty();
        dirtyLobby = true;
    }

    /**
     * Предложения союза из переезда с FTB (одностороннее доверие команды игроку другой команды): лидер второй команды
     * принимает их в окне команды, как обычные. Живут до перезапуска, как и все предложения союза; строка в ленте остаётся.
     */
    public static void allyOffers(List<FtbImport.Offer> offers) {
        if (!running() || offers.isEmpty()) return;
        TeamStore s = store();
        for (FtbImport.Offer o : offers) {
            Team from = s.teams.get(o.from()), to = s.teams.get(o.to());
            if (from == null || to == null || from.allies.contains(to.id)) continue;
            if (ALLY_OFFERS.computeIfAbsent(to.id, k -> new LinkedHashSet<>()).add(from.id)) {
                feed(to, Component.translatable("rysteams.feed.ally_offer", from.name));
            }
        }
    }
}
