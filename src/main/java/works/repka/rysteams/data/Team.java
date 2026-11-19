package works.repka.rysteams.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import works.repka.rysteams.api.Permission;
import works.repka.rysteams.api.Role;
import works.repka.ryscore.team.TeamsApi;

/** Команда. Один и тот же класс хранится на сервере и приходит клиенту: формат один, NBT. */
public final class Team {
    /** Сколько событий ленты хранить и отдавать клиенту. Старше отданного клиент не видит, поэтому храним с небольшим запасом. */
    public static final int FEED_LIMIT = 150;
    public static final int FEED_SENT = 120;
    /** Двенадцать цветов команды, различимых по яркости. */
    public static final int[] COLORS = {0x2E8B57, 0x3A7BD5, 0x7A4FD1, 0xC0392B, 0xD47C3E, 0xE4B23C,
        0x5BE3E0, 0x8B93A5, 0xE86FA8, 0x1FA8A0, 0x7F8F3A, 0x4B4FB5};

    public enum JoinMode { INVITE, REQUEST, OPEN }

    public enum Rewards { EACH, ONCE }

    public static final class Member {
        public final UUID id;
        public String name;
        public Role role;
        public long joined;
        public long lastSeen;

        public Member(UUID id, String name, Role role, long joined, long lastSeen) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.joined = joined;
            this.lastSeen = lastSeen;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("name", name);
            t.putString("role", role.key());
            t.putLong("joined", joined);
            t.putLong("seen", lastSeen);
            return t;
        }

        static Member load(CompoundTag t) {
            return new Member(t.getUUID("id"), t.getString("name"), Role.parse(t.getString("role")), t.getLong("joined"), t.getLong("seen"));
        }
    }

    public record Feed(long time, String source, String text, String icon, boolean alert, String action) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("t", time);
            t.putString("s", source);
            t.putString("x", text);
            t.putString("i", icon);
            t.putBoolean("a", alert);
            t.putString("d", action);
            return t;
        }

        static Feed load(CompoundTag t) {
            return new Feed(t.getLong("t"), t.getString("s"), t.getString("x"), t.getString("i"), t.getBoolean("a"), t.getString("d"));
        }
    }

    /** Источник метки места гибели участника: временная, одна на игрока, в лимит меток не входит. */
    public static final String SOURCE_DEATH = "rysteams:death";

    /**
     * Метка команды.
     * @param expires когда снимается (мс), 0 значит постоянная: в старых сохранениях поля нет
     * @param owner чья это метка гибели, у прочих null
     * @param allies метку видят и союзники («Кто видит: команда и союз», 0.2.6); в старых сохранениях поля нет
     */
    public record Mark(UUID id, String name, String dimension, int x, int y, int z, String icon, String by, String source, long expires, UUID owner,
                       boolean allies) {
        public Mark(UUID id, String name, String dimension, int x, int y, int z, String icon, String by, String source, long expires, UUID owner) {
            this(id, name, dimension, x, y, z, icon, by, source, expires, owner, false);
        }

        public Mark(UUID id, String name, String dimension, int x, int y, int z, String icon, String by, String source) {
            this(id, name, dimension, x, y, z, icon, by, source, 0, null, false);
        }

        public boolean death() {
            return SOURCE_DEATH.equals(source);
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("name", name);
            t.putString("dim", dimension);
            t.putInt("x", x);
            t.putInt("y", y);
            t.putInt("z", z);
            t.putString("icon", icon);
            t.putString("by", by);
            t.putString("src", source);
            if (expires != 0) t.putLong("exp", expires);
            if (owner != null) t.putUUID("owner", owner);
            if (allies) t.putBoolean("ally", true);
            return t;
        }

        public static Mark load(CompoundTag t) {
            return new Mark(t.getUUID("id"), t.getString("name"), t.getString("dim"), t.getInt("x"), t.getInt("y"), t.getInt("z"),
                t.getString("icon"), t.getString("by"), t.getString("src"), t.getLong("exp"), t.hasUUID("owner") ? t.getUUID("owner") : null,
                t.getBoolean("ally"));
        }
    }

    public static final class Note {
        public final UUID id;
        public String text;
        public String by;
        public long time;

        public Note(UUID id, String text, String by, long time) {
            this.id = id;
            this.text = text;
            this.by = by;
            this.time = time;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("text", text);
            t.putString("by", by);
            t.putLong("time", time);
            return t;
        }

        static Note load(CompoundTag t) {
            return new Note(t.getUUID("id"), t.getString("text"), t.getString("by"), t.getLong("time"));
        }
    }

    public static final class Todo {
        public final UUID id;
        public String text;
        public boolean done;
        public String assignee;
        public String source;

        public Todo(UUID id, String text, boolean done, String assignee, String source) {
            this.id = id;
            this.text = text;
            this.done = done;
            this.assignee = assignee;
            this.source = source;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("text", text);
            t.putBoolean("done", done);
            t.putString("who", assignee);
            t.putString("src", source);
            return t;
        }

        static Todo load(CompoundTag t) {
            return new Todo(t.getUUID("id"), t.getString("text"), t.getBoolean("done"), t.getString("who"), t.getString("src"));
        }
    }

    public final UUID id;
    public String name;
    public int color;
    public String icon;
    public long created;
    public JoinMode join = JoinMode.INVITE;
    public Rewards rewards = Rewards.EACH;
    public boolean keepCopyOnLeave = true;
    /** Через сколько дней отсутствия лидера роль передаётся автоматически; 0 = никогда. */
    public int autoTransferDays = 14;
    public final Map<UUID, Member> members = new LinkedHashMap<>();
    public final Map<Permission, Set<Role>> permissions = new EnumMap<>(Permission.class);
    /** Права чужих модов (TeamsApi.registerPermission), которые лидер менял; остальные берутся по умолчанию из реестра ядра. */
    public final Map<String, Set<Role>> modPermissions = new LinkedHashMap<>();
    public final Set<UUID> allies = new HashSet<>();
    public final Map<UUID, String> requests = new LinkedHashMap<>();
    public final Deque<Feed> feed = new ArrayDeque<>();
    public final List<Mark> marks = new ArrayList<>();
    public final List<Note> notes = new ArrayList<>();
    public final List<Todo> todos = new ArrayList<>();
    /** Данные модов по ключам. */
    public CompoundTag modData = new CompoundTag();

    public Team(UUID id, String name, int color, String icon, long created) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.icon = icon;
        this.created = created;
        resetPermissions();
    }

    public void resetPermissions() {
        for (Permission p : Permission.values()) permissions.put(p, p.defaults());
        modPermissions.clear();
    }

    /** Кому дано право чужого мода: заданное лидером или значения мода по умолчанию. Лидеру всегда. */
    public Set<Role> modRoles(String id) {
        EnumSet<Role> out = EnumSet.of(Role.LEADER);
        Set<Role> explicit = modPermissions.get(id);
        if (explicit != null) {
            out.addAll(explicit);
        } else {
            TeamsApi.ModPermission p = TeamsApi.permission(id);
            if (p != null) {
                for (String key : p.defaultRoles()) for (Role r : Role.values()) if (r.key().equals(key)) out.add(r);
            }
        }
        return out;
    }

    public boolean can(Role role, Permission permission) {
        Set<Role> allowed = permissions.get(permission);
        return allowed != null && allowed.contains(role);
    }

    public boolean can(UUID player, Permission permission) {
        Member m = members.get(player);
        return m != null && can(m.role, permission);
    }

    public Member leader() {
        for (Member m : members.values()) if (m.role == Role.LEADER) return m;
        return members.isEmpty() ? null : members.values().iterator().next();
    }

    /** Метки, которые входят в лимит: временные метки гибели не считаются. */
    public int permanentMarks() {
        int n = 0;
        for (Mark m : marks) if (!m.death()) n++;
        return n;
    }

    public void addFeed(Feed entry) {
        feed.addFirst(entry);
        while (feed.size() > FEED_LIMIT) feed.removeLast();
    }

    /** @param forClient клиенту лента отдаётся укороченной, а данные модов не нужны */
    public CompoundTag save(boolean forClient) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("name", name);
        t.putInt("color", color);
        t.putString("icon", icon);
        t.putLong("created", created);
        t.putString("join", join.name());
        t.putString("rewards", rewards.name());
        t.putBoolean("keepCopy", keepCopyOnLeave);
        t.putInt("autoDays", autoTransferDays);
        ListTag list = new ListTag();
        for (Member m : members.values()) list.add(m.save());
        t.put("members", list);
        CompoundTag perms = new CompoundTag();
        for (Map.Entry<Permission, Set<Role>> e : permissions.entrySet()) {
            ListTag roles = new ListTag();
            for (Role r : e.getValue()) roles.add(StringTag.valueOf(r.key()));
            perms.put(e.getKey().key(), roles);
        }
        t.put("perms", perms);
        if (!modPermissions.isEmpty()) {
            CompoundTag mod = new CompoundTag();
            for (Map.Entry<String, Set<Role>> e : modPermissions.entrySet()) {
                ListTag roles = new ListTag();
                for (Role r : e.getValue()) roles.add(StringTag.valueOf(r.key()));
                mod.put(e.getKey(), roles);
            }
            t.put("modPerms", mod);
        }
        ListTag allyList = new ListTag();
        for (UUID ally : allies) allyList.add(StringTag.valueOf(ally.toString()));
        t.put("allies", allyList);
        // списком: у CompoundTag порядок ключей не сохраняется, а первой в панели должна быть самая ранняя заявка
        ListTag req = new ListTag();
        requests.forEach((k, v) -> {
            CompoundTag x = new CompoundTag();
            x.putUUID("id", k);
            x.putString("name", v);
            req.add(x);
        });
        t.put("requests", req);
        ListTag feedList = new ListTag();
        int n = 0;
        for (Feed f : feed) {
            if (forClient && n++ >= FEED_SENT) break;
            feedList.add(f.save());
        }
        t.put("feed", feedList);
        ListTag markList = new ListTag();
        for (Mark m : marks) markList.add(m.save());
        t.put("marks", markList);
        ListTag noteList = new ListTag();
        for (Note x : notes) noteList.add(x.save());
        t.put("notes", noteList);
        ListTag todoList = new ListTag();
        for (Todo x : todos) todoList.add(x.save());
        t.put("todos", todoList);
        if (!forClient) t.put("mods", modData);
        return t;
    }

    public static Team load(CompoundTag t) {
        Team team = new Team(t.getUUID("id"), t.getString("name"), t.getInt("color"), t.getString("icon"), t.getLong("created"));
        team.join = parse(JoinMode.class, t.getString("join"), JoinMode.INVITE);
        team.rewards = parse(Rewards.class, t.getString("rewards"), Rewards.EACH);
        team.keepCopyOnLeave = t.getBoolean("keepCopy");
        team.autoTransferDays = t.getInt("autoDays");
        for (Tag x : t.getList("members", Tag.TAG_COMPOUND)) {
            Member m = Member.load((CompoundTag) x);
            team.members.put(m.id, m);
        }
        CompoundTag perms = t.getCompound("perms");
        for (String key : perms.getAllKeys()) {
            Permission p = Permission.parse(key);
            if (p == null) continue;
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (Tag r : perms.getList(key, Tag.TAG_STRING)) roles.add(Role.parse(r.getAsString()));
            team.permissions.put(p, roles);
        }
        team.permissions.put(Permission.MANAGE, Permission.MANAGE.defaults());
        CompoundTag mod = t.getCompound("modPerms");
        for (String key : mod.getAllKeys()) {
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (Tag r : mod.getList(key, Tag.TAG_STRING)) roles.add(Role.parse(r.getAsString()));
            team.modPermissions.put(key, roles);
        }
        for (Tag x : t.getList("allies", Tag.TAG_STRING)) team.allies.add(UUID.fromString(x.getAsString()));
        if (t.getTagType("requests") == Tag.TAG_LIST) {
            for (Tag x : t.getList("requests", Tag.TAG_COMPOUND)) team.requests.put(((CompoundTag) x).getUUID("id"), ((CompoundTag) x).getString("name"));
        } else {
            // прежний формат: словарь без порядка
            CompoundTag req = t.getCompound("requests");
            for (String key : req.getAllKeys()) team.requests.put(UUID.fromString(key), req.getString(key));
        }
        for (Tag x : t.getList("feed", Tag.TAG_COMPOUND)) {
            if (team.feed.size() >= FEED_LIMIT) break;
            team.feed.addLast(Feed.load((CompoundTag) x));
        }
        for (Tag x : t.getList("marks", Tag.TAG_COMPOUND)) team.marks.add(Mark.load((CompoundTag) x));
        for (Tag x : t.getList("notes", Tag.TAG_COMPOUND)) team.notes.add(Note.load((CompoundTag) x));
        for (Tag x : t.getList("todos", Tag.TAG_COMPOUND)) team.todos.add(Todo.load((CompoundTag) x));
        team.modData = t.getCompound("mods");
        return team;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String text, E fallback) {
        for (E e : type.getEnumConstants()) if (e.name().equals(text)) return e;
        return fallback;
    }
}
