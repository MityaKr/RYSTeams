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
    private static final long ARCHIVE_MS = 7 * 24 * 3_600_000L;
    private static final long DAY_MS = 24 * 3_600_000L;
    private static final int NAME_MIN = 3, NAME_MAX = 24, TEXT_MAX = 400;

    private record Invite(UUID team, String by, long expires) {}

    private static final class Merge {
        final UUID from, into;
        boolean fromOk, intoOk;

        Merge(UUID from, UUID into) {
            this.from = from;
            this.into = into;
        }
    }

    private record Undo(CompoundTag from, CompoundTag into, UUID fromId, UUID intoId, long deadline) {}

    private static MinecraftServer server;
    private static final Map<UUID, List<Invite>> INVITES = new HashMap<>();
    private static final Map<UUID, Set<UUID>> ALLY_OFFERS = new HashMap<>();
    private static Merge merge;
    private static Undo undo;
    private static final Set<UUID> DIRTY = new HashSet<>();
    private static boolean dirtyLobby;
    private static int ticks;

    private TeamService() {}

    public static void start(MinecraftServer s) {
        server = s;
        INVITES.clear();
        ALLY_OFFERS.clear();
        merge = null;
        undo = null;
        DIRTY.clear();
        Presence.reset();
    }

    public static void stop() {
        server = null;
    }

    public static TeamStore store() {
        return TeamStore.get(server);
    }

    public static boolean running() {
        return server != null;
    }

    // --- события сервера ---

    public static void login(ServerPlayer player) {
        TeamStore s = store();
        if (TeamsConfig.isServerMode() && s.teamOf(player.getUUID()) == null) joinServerTeam(player);
        Team team = s.teamOf(player.getUUID());
        if (team != null) {
            Team.Member m = team.members.get(player.getUUID());
            m.name = player.getGameProfile().getName();
            autoTransfer(team);
            DIRTY.add(team.id);
        }
        dirtyLobby = true;
        sendState(player);
    }

    public static void logout(ServerPlayer player) {
        Team team = store().teamOf(player.getUUID());
        if (team != null) {
            team.members.get(player.getUUID()).lastSeen = now();
            store().setDirty();
            DIRTY.add(team.id);
        }
        dirtyLobby = true;
    }

    public static void tick() {
        if (server == null) return;
        ticks++;
        if (!DIRTY.isEmpty() || dirtyLobby) flush();
        if (ticks % 40 == 0) Presence.broadcast(server, store());
        if (ticks % 200 == 0) expire();
    }

    private static void expire() {
        long t = now();
        boolean changed = false;
        for (Iterator<List<Invite>> it = INVITES.values().iterator(); it.hasNext(); ) {
            List<Invite> list = it.next();
            changed |= list.removeIf(i -> i.expires < t);
            if (list.isEmpty()) it.remove();
        }
        if (undo != null && undo.deadline < t) {
            undo = null;
            changed = true;
        }
        TeamStore s = store();
        if (s.archive.removeIf(a -> t - a.time() > ARCHIVE_MS)) s.setDirty();
        if (changed) dirtyLobby = true;
    }

    private static void autoTransfer(Team team) {
        if (team.autoTransferDays <= 0 || team.members.size() < 2) return;
        Team.Member leader = team.leader();
        if (leader == null || online(leader.id) != null) return;
        if (now() - leader.lastSeen < team.autoTransferDays * DAY_MS) return;
        Team.Member next = successor(team, leader.id);
        if (next == null) return;
        leader.role = Role.OFFICER;
        next.role = Role.LEADER;
        feed(team, Component.translatable("rysteams.feed.auto_leader", next.name));
        store().setDirty();
    }

    private static void flush() {
        boolean lobby = dirtyLobby;
        Set<UUID> dirty = new HashSet<>(DIRTY);
        DIRTY.clear();
        dirtyLobby = false;
        TeamStore s = store();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID team = s.byPlayer.get(p.getUUID());
            if (lobby || (team != null && dirty.contains(team))) sendState(p);
        }
    }

    private static void touch(Team team) {
        store().setDirty();
        DIRTY.add(team.id);
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

    private static void sendState(ServerPlayer player) {
        ModNetwork.send(player, stateFor(player));
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

    private static CompoundTag stateFor(ServerPlayer player) {
        TeamStore s = store();
        UUID me = player.getUUID();
        CompoundTag out = new CompoundTag();
        out.putString("type", "state");
        Team team = s.teamOf(me);
        if (team != null) {
            CompoundTag t = team.save(true);
            if (!team.can(me, Permission.SEE_ALERTS)) {
                ListTag feed = t.getList("feed", Tag.TAG_COMPOUND);
                feed.removeIf(x -> ((CompoundTag) x).getBoolean("a"));
            }
            out.put("team", t);
            ListTag allies = new ListTag();
            for (UUID id : team.allies) {
                Team a = s.teams.get(id);
                if (a != null) allies.add(summary(a));
            }
            out.put("allies", allies);
            ListTag offers = new ListTag();
            for (UUID id : ALLY_OFFERS.getOrDefault(team.id, Set.of())) {
                Team a = s.teams.get(id);
                if (a != null) offers.add(summary(a));
            }
            out.put("allyOffers", offers);
        }
        ListTag open = new ListTag();
        for (Team t : s.teams.values()) if (team == null || t != team) open.add(summary(t));
        out.put("teams", open);
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
        ListTag players = new ListTag();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == player) continue;
            CompoundTag x = new CompoundTag();
            x.putUUID("id", p.getUUID());
            x.putString("name", p.getGameProfile().getName());
            x.putBoolean("free", s.teamOf(p.getUUID()) == null);
            players.add(x);
        }
        out.put("online", players);
        out.putBoolean("hide", s.hideCoords.contains(me));
        out.putBoolean("serverMode", TeamsConfig.isServerMode());
        for (TeamStore.Archived a : s.archive) {
            if (a.leader().equals(me) && team == null) {
                out.putString("restore", a.team().getString("name"));
                break;
            }
        }
        if (merge != null && team != null && (team.id.equals(merge.from) || team.id.equals(merge.into))) out.put("merge", mergeTag());
        if (undo != null && team != null && (team.id.equals(undo.intoId))) out.putLong("undoLeft", undo.deadline - t0);
        return out;
    }

    private static CompoundTag mergeTag() {
        TeamStore s = store();
        Team from = s.teams.get(merge.from), into = s.teams.get(merge.into);
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

    // --- действия ---

    public static void handle(ServerPlayer p, String action, CompoundTag a) {
        if (server == null) return;
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
            case "invite_reply" -> inviteReply(p, a.getUUID("team"), a.getString("answer"));
            case "restore" -> restore(p);
            case "hide" -> {
                if (a.getBoolean("on")) s.hideCoords.add(me);
                else s.hideCoords.remove(me);
                touchLobby();
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
        switch (action) {
            case "invite" -> invite(p, team, a.getUUID("player"));
            case "accept_request", "decline_request" -> {
                if (!team.can(me, Permission.ACCEPT_REQUESTS)) {
                    denied(p);
                    return;
                }
                UUID who = a.getUUID("player");
                String asked = team.requests.remove(who);
                if (asked != null && action.equals("accept_request") && s.teamOf(who) == null) addMember(team, who, asked, Role.MEMBER, false);
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
                removeMember(team, who, team.keepCopyOnLeave);
                feed(team, Component.translatable("rysteams.feed.kicked", m.name, name));
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
                disband(team, me);
            }
            case "set_perm" -> {
                Permission perm = Permission.parse(a.getString("perm"));
                Role role = Role.parse(a.getString("role"));
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
                team.notes.add(0, new Team.Note(UUID.randomUUID(), clip(a.getString("text")), name, now()));
                touch(team);
            }
            case "note_edit" -> {
                if (!board(p, team)) return;
                for (Team.Note n : team.notes) if (n.id.equals(a.getUUID("id"))) n.text = clip(a.getString("text"));
                touch(team);
            }
            case "note_del" -> {
                if (!board(p, team)) return;
                team.notes.removeIf(n -> n.id.equals(a.getUUID("id")));
                touch(team);
            }
            case "todo_add" -> {
                if (!board(p, team)) return;
                team.todos.add(new Team.Todo(UUID.randomUUID(), clip(a.getString("text")), false, "", ""));
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
            case "mark_add" -> {
                if (!team.can(me, Permission.EDIT_MARKS)) {
                    denied(p);
                    return;
                }
                BlockPos pos = p.blockPosition();
                String title = clip(a.getString("name"));
                String icon = p.getMainHandItem().isEmpty() ? "minecraft:compass" : itemId(p);
                team.marks.add(new Team.Mark(UUID.randomUUID(), title, p.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), icon, name, ""));
                feed(team, SOURCE_TEAMS, Component.translatable("rysteams.feed.mark", name, title, pos.getX(), pos.getY(), pos.getZ()), icon, false,
                    "waypoint:" + p.level().dimension().location() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ());
            }
            case "mark_del" -> {
                if (!team.can(me, Permission.EDIT_MARKS)) {
                    denied(p);
                    return;
                }
                team.marks.removeIf(m -> m.id().equals(a.getUUID("id")));
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
                if (merge != null && team.can(me, Permission.MANAGE) && (team.id.equals(merge.from) || team.id.equals(merge.into))) {
                    merge = null;
                    touchLobby();
                }
            }
            case "merge_undo" -> mergeUndo(p, team);
            default -> RYSTeams.LOG.debug("Неизвестное действие {}", action);
        }
    }

    private static String itemId(ServerPlayer p) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString();
    }

    private static boolean board(ServerPlayer p, Team team) {
        if (team.can(p.getUUID(), Permission.EDIT_BOARD)) return true;
        denied(p);
        return false;
    }

    private static String clip(String text) {
        String t = text.strip();
        return t.length() > TEXT_MAX ? t.substring(0, TEXT_MAX) : t;
    }

    private static boolean validName(String name) {
        int n = name.strip().length();
        return n >= NAME_MIN && n <= NAME_MAX;
    }

    private static boolean nameTaken(String name, Team except) {
        for (Team t : store().teams.values()) if (t != except && t.name.equalsIgnoreCase(name.strip())) return true;
        return false;
    }

    private static void create(ServerPlayer p, CompoundTag a) {
        TeamStore s = store();
        UUID me = p.getUUID();
        if (s.teamOf(me) != null || TeamsConfig.isServerMode()) return;
        String name = a.getString("name");
        if (!validName(name)) {
            toast(p, Component.translatable("rysteams.error.name"), true);
            return;
        }
        if (nameTaken(name, null)) {
            toast(p, Component.translatable("rysteams.error.taken"), true);
            return;
        }
        Team team = new Team(UUID.randomUUID(), name.strip(), a.getInt("color") & 0xFFFFFF, a.getString("icon"), now());
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
        if (team == null || store().teamOf(p.getUUID()) != null || team.join == Team.JoinMode.INVITE) return;
        team.requests.put(p.getUUID(), p.getGameProfile().getName());
        feed(team, Component.translatable("rysteams.feed.request", p.getGameProfile().getName()));
        toast(p, Component.translatable("rysteams.toast.request_sent", team.name), false);
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
        List<Invite> list = INVITES.computeIfAbsent(target, k -> new ArrayList<>());
        list.removeIf(i -> i.team.equals(team.id));
        list.add(new Invite(team.id, p.getGameProfile().getName(), now() + INVITE_MS));
        // в чате остаётся ссылка: сработает, даже если подсказка уже закрыта
        MutableComponent link = Component.literal("[" + team.name + "]").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(team.color)).withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rysteams accept " + team.id)));
        other.sendSystemMessage(Component.translatable("rysteams.chat.invited", p.getGameProfile().getName(), link));
        toast(p, Component.translatable("rysteams.toast.invite_sent", other.getGameProfile().getName()), false);
        sendState(other);
    }

    public static void inviteReply(ServerPlayer p, UUID teamId, String answer) {
        TeamStore s = store();
        List<Invite> list = INVITES.get(p.getUUID());
        if (list == null) return;
        Invite found = null;
        for (Invite i : list) if (i.team.equals(teamId) && i.expires >= now()) found = i;
        list.removeIf(i -> i.team.equals(teamId));
        if (found == null) return;
        Team team = s.teams.get(teamId);
        if (!answer.equals("no") && team != null && s.teamOf(p.getUUID()) == null) {
            if (full(team)) {
                toast(p, Component.translatable("rysteams.error.full"), true);
                return;
            }
            addMember(team, p.getUUID(), p.getGameProfile().getName(), Role.MEMBER, answer.equals("accept"));
            INVITES.remove(p.getUUID());
        }
        sendState(p);
    }

    private static Team.Member successor(Team team, UUID except) {
        Team.Member best = null;
        for (Team.Member m : team.members.values()) {
            if (m.id.equals(except)) continue;
            if (best == null || m.role.ordinal() < best.role.ordinal()) best = m;
        }
        return best;
    }

    private static void removeMember(Team team, UUID id, boolean keepCopy) {
        TeamStore s = store();
        team.members.remove(id);
        s.byPlayer.remove(id);
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
                disband(team, me);
                return;
            }
            next.role = Role.LEADER;
            feed(team, Component.translatable("rysteams.feed.leader", next.name));
        }
        removeMember(team, me, team.keepCopyOnLeave);
        feed(team, Component.translatable("rysteams.feed.left", self.name));
    }

    private static void disband(Team team, UUID leader) {
        TeamStore s = store();
        s.archive.add(new TeamStore.Archived(team.save(false), leader, now()));
        for (UUID id : new ArrayList<>(team.members.keySet())) s.byPlayer.remove(id);
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

    // --- администратор ---

    private static CompoundTag adminRow(UUID id, String name, int color, int count, String leader, long time) {
        CompoundTag x = new CompoundTag();
        x.putUUID("id", id);
        x.putString("name", name);
        x.putInt("color", color);
        x.putInt("count", count);
        x.putString("leader", leader);
        x.putLong("time", time);
        return x;
    }

    public static void sendAdmin(ServerPlayer p) {
        TeamStore s = store();
        CompoundTag out = new CompoundTag();
        out.putString("type", "admin");
        out.putBoolean("ftb", FtbImport.available(server) && !s.ftbImported);
        ListTag teams = new ListTag();
        for (Team t : s.teams.values()) {
            Team.Member leader = t.leader();
            teams.add(adminRow(t.id, t.name, t.color, t.members.size(), leader == null ? "" : leader.name, t.created));
        }
        out.put("teams", teams);
        ListTag archive = new ListTag();
        for (TeamStore.Archived a : s.archive) {
            Team t = Team.load(a.team());
            Team.Member leader = t.members.get(a.leader());
            archive.add(adminRow(t.id, t.name, t.color, t.members.size(), leader == null ? "" : leader.name, a.time()));
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
                disband(team, leader == null ? p.getUUID() : leader.id);
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
                try {
                    FtbImport.Result r = FtbImport.run(server);
                    toast(p, Component.translatable("rysteams.admin.imported", r.teams(), r.players(), r.skipped()), false);
                } catch (java.io.IOException e) {
                    toast(p, Component.literal(e.toString()), true);
                }
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
        if (s.teams.containsKey(team.id)) return null;
        team.members.keySet().removeIf(s.byPlayer::containsKey);
        if (team.members.isEmpty()) return null;
        if (team.members.get(entry.leader()) != null) team.members.get(entry.leader()).role = Role.LEADER;
        else team.members.values().iterator().next().role = Role.LEADER;
        s.teams.put(team.id, team);
        for (UUID id : team.members.keySet()) s.byPlayer.put(id, team.id);
        touchLobby();
        return team;
    }

    private static void restore(ServerPlayer p) {
        TeamStore s = store();
        UUID me = p.getUUID();
        if (s.teamOf(me) != null) return;
        for (Iterator<TeamStore.Archived> it = s.archive.iterator(); it.hasNext(); ) {
            TeamStore.Archived a = it.next();
            if (!a.leader().equals(me)) continue;
            Team team = Team.load(a.team());
            if (s.teams.containsKey(team.id)) return;
            team.members.keySet().removeIf(id -> s.byPlayer.containsKey(id));
            if (!team.members.containsKey(me)) team.members.put(me, new Team.Member(me, p.getGameProfile().getName(), Role.LEADER, now(), now()));
            s.teams.put(team.id, team);
            for (UUID id : team.members.keySet()) s.byPlayer.put(id, team.id);
            it.remove();
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
            String name = a.getString("name");
            if (!validName(name)) {
                toast(p, Component.translatable("rysteams.error.name"), true);
                return;
            }
            if (nameTaken(name, team)) {
                toast(p, Component.translatable("rysteams.error.taken"), true);
                return;
            }
            team.name = name.strip();
        }
        if (a.contains("color")) team.color = a.getInt("color") & 0xFFFFFF;
        if (a.contains("icon")) team.icon = a.getString("icon");
        touch(team);
        touchLobby();
    }

    private static void setRule(ServerPlayer p, Team team, CompoundTag a) {
        if (!team.can(p.getUUID(), Permission.MANAGE)) {
            denied(p);
            return;
        }
        switch (a.getString("rule")) {
            case "join" -> team.join = Team.JoinMode.values()[Math.floorMod(team.join.ordinal() + 1, Team.JoinMode.values().length)];
            case "rewards" -> team.rewards = team.rewards == Team.Rewards.EACH ? Team.Rewards.ONCE : Team.Rewards.EACH;
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
        touchLobby();
    }

    private static void ping(ServerPlayer p, Team team, CompoundTag a) {
        if (!team.can(p.getUUID(), Permission.PING)) {
            denied(p);
            return;
        }
        CompoundTag x = new CompoundTag();
        x.putString("type", "ping");
        x.putString("by", p.getGameProfile().getName());
        x.putInt("color", team.color);
        x.putString("kind", a.getString("kind"));
        x.putString("dim", p.level().dimension().location().toString());
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

    private static void allyOffer(ServerPlayer p, Team team, Team other) {
        if (other == null || other == team || !team.can(p.getUUID(), Permission.MANAGE) || team.allies.contains(other.id)) return;
        if (ALLY_OFFERS.getOrDefault(team.id, Set.of()).contains(other.id)) {
            allyAccept(p, team, other.id, true);
            return;
        }
        ALLY_OFFERS.computeIfAbsent(other.id, k -> new LinkedHashSet<>()).add(team.id);
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

    private static void mergePropose(ServerPlayer p, Team team, Team other) {
        if (other == null || other == team || !team.can(p.getUUID(), Permission.MANAGE) || merge != null) return;
        merge = new Merge(other.id, team.id);
        merge.intoOk = true;
        Team.Member leader = other.leader();
        feed(other, Component.translatable("rysteams.feed.merge_offer", team.name));
        if (leader != null && online(leader.id) != null) toast(online(leader.id), Component.translatable("rysteams.feed.merge_offer", team.name), false);
        touchLobby();
    }

    private static void mergeConfirm(ServerPlayer p, Team team) {
        if (merge == null || !team.can(p.getUUID(), Permission.MANAGE)) return;
        if (team.id.equals(merge.from)) merge.fromOk = true;
        else if (team.id.equals(merge.into)) merge.intoOk = true;
        else return;
        if (merge.fromOk && merge.intoOk) executeMerge();
        else touchLobby();
    }

    private static void executeMerge() {
        TeamStore s = store();
        Team from = s.teams.get(merge.from), into = s.teams.get(merge.into);
        UUID fromId = merge.from, intoId = merge.into;
        merge = null;
        if (from == null || into == null) return;
        undo = new Undo(from.save(false), into.save(false), fromId, intoId, now() + UNDO_MS);
        for (Team.Member m : from.members.values()) {
            if (m.role == Role.LEADER) m.role = Role.OFFICER;
            into.members.put(m.id, m);
            s.byPlayer.put(m.id, into.id);
        }
        into.marks.addAll(from.marks);
        into.notes.addAll(from.notes);
        into.todos.addAll(from.todos);
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
        if (undo == null || undo.deadline < now() || !team.id.equals(undo.intoId) || !team.can(p.getUUID(), Permission.MANAGE)) return;
        TeamStore s = store();
        Undo u = undo;
        undo = null;
        Team from = Team.load(u.from), into = Team.load(u.into);
        s.teams.put(from.id, from);
        s.teams.put(into.id, into);
        for (UUID id : from.members.keySet()) s.byPlayer.put(id, from.id);
        for (UUID id : into.members.keySet()) s.byPlayer.put(id, into.id);
        for (TeamsApi.Listener l : TeamsApi.listeners()) {
            try {
                l.onMergeUndone(u.fromId, u.intoId);
            } catch (Throwable t) {
                RYSTeams.LOG.warn("Слушатель отката слияния упал", t);
            }
        }
        feed(into, Component.translatable("rysteams.feed.merge_undone"));
        touchLobby();
    }

    private static void joinServerTeam(ServerPlayer p) {
        TeamStore s = store();
        Team team = s.teams.values().stream().findFirst().orElse(null);
        boolean first = team == null;
        if (first) {
            team = new Team(UUID.randomUUID(), TeamsConfig.serverTeamName(), Team.COLORS[1], "minecraft:beacon", now());
            s.teams.put(team.id, team);
        }
        addMember(team, p.getUUID(), p.getGameProfile().getName(), first ? Role.LEADER : Role.MEMBER, true);
    }

    // --- чат команды ---

    /** @return true, если сообщение ушло в канал команды и в общий чат его пускать не нужно */
    public static boolean chat(ServerPlayer p, String message) {
        if (!message.startsWith("!") || message.length() < 2) return false;
        TeamStore s = store();
        Team team = s.teamOf(p.getUUID());
        if (team == null) {
            toast(p, Component.translatable("rysteams.error.no_team"), true);
            return true;
        }
        boolean ally = message.startsWith("!!");
        String text = message.substring(ally ? 2 : 1).strip();
        if (text.isEmpty()) return true;
        MutableComponent line = Component.literal("[" + team.name + "] ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(team.color)).withBold(true))
            .append(Component.literal(p.getGameProfile().getName() + ": ").withStyle(Style.EMPTY.withBold(false).withColor(TextColor.fromRgb(team.color))))
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
        team.todos.add(new Team.Todo(UUID.randomUUID(), clip(text), false, "", source));
        touch(team);
    }

    public static void pingFromMod(UUID teamId, String dimension, int x, int y, int z, String kind, String by) {
        if (server == null) return;
        Team team = store().teams.get(teamId);
        if (team == null) return;
        CompoundTag out = new CompoundTag();
        out.putString("type", "ping");
        out.putString("by", by);
        out.putInt("color", team.color);
        out.putString("kind", kind);
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
    public static void imported() {
        store().setDirty();
        dirtyLobby = true;
    }
}
