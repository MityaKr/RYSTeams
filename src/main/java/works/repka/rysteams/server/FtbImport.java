package works.repka.rysteams.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryscore.migrate.Snbt;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.data.Team;

/**
 * Перенос команд из файлов FTB Teams ({@code world/ftbteams/party/*.snbt}). Читаются только данные мира читателем ядра
 * {@link Snbt}, код FTB не используется. UUID команды сохраняется, поэтому прогресс и земля в других модах остаются
 * привязаны к ней без таблицы соответствия.
 *
 * <p>Своё не затирается: команда с UUID, который уже есть в RYSTeams, не трогается (совпадает она с FTB или нет, видно в
 * отчёте); игрок, который уже состоит в команде RYSTeams, там и остаётся, в переносимую команду он не попадает. Хранилище
 * меняется одним шагом в конце, после того как посчитано всё.
 */
public final class FtbImport {
    private FtbImport() {}

    public record Result(int teams, int players, int skipped) {}

    /** Команда FTB, как она лежит в файле: участники с ролями RYSTeams, союзники и приглашённые отброшены. */
    record FtbTeam(UUID id, String name, int color, boolean open, Map<UUID, Role> members, String file) {}

    static Path partyDir(Path world) {
        return world.resolve("ftbteams").resolve("party");
    }

    /** Есть ли в мире команды FTB, которые можно перенести. */
    public static boolean hasData(@Nullable Path world) {
        if (world == null) return false;
        Path dir = partyDir(world);
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> list = Files.list(dir)) {
            return list.anyMatch(p -> p.getFileName().toString().endsWith(".snbt"));
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean available(MinecraftServer server) {
        return hasData(world(server));
    }

    private static Path world(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).normalize();
    }

    /** Перенос без ядра (автотест, старые вызовы). Шаг переезда зовёт {@link #merge} сам. */
    public static Result run(MinecraftServer server) throws IOException {
        TeamStore store = TeamStore.get(server);
        Result r = merge(world(server), store, server, Migrations.Report.create());
        TeamService.imported();
        return r;
    }

    /**
     * Читает команды FTB и добавляет в хранилище те, которых в нём нет. Строки отчёта: перенесено, уже есть, конфликты.
     *
     * @param server для ников из кэша профилей; без сервера ник берётся из файла игрока FTB или начала UUID
     */
    static Result merge(Path world, TeamStore store, @Nullable MinecraftServer server, Migrations.Report report) throws IOException {
        List<FtbTeam> ftb = read(world, report);
        Map<UUID, UUID> taken = new HashMap<>(store.byPlayer);
        Map<UUID, String> newNames = new HashMap<>();
        List<Team> created = new ArrayList<>();
        int players = 0, skipped = 0, existing = 0, conflicts = 0;
        long now = TeamService.now();
        for (FtbTeam f : ftb) {
            Team own = store.teams.get(f.id());
            if (own != null) {
                existing++;
                String diff = diff(f, own, world, server);
                if (diff.isEmpty()) {
                    report.skipped(own.name, "уже есть");
                } else {
                    conflicts++;
                    report.skipped(own.name, "уже есть, состав в RYSTeams другой (" + diff + "), оставлена как есть");
                }
                continue;
            }
            Team team = new Team(f.id(), f.name(), f.color(), "minecraft:white_banner", now);
            if (f.open()) team.join = Team.JoinMode.OPEN;
            for (Map.Entry<UUID, Role> e : f.members().entrySet()) {
                UUID player = e.getKey();
                UUID other = taken.get(player);
                if (other != null) {
                    conflicts++;
                    String where = store.teams.containsKey(other) ? store.teams.get(other).name : newNames.getOrDefault(other, other.toString());
                    report.line(f.name() + ": " + nameOf(world, server, player) + " уже в команде " + where + ", остался там");
                    continue;
                }
                // когда игрок заходил, FTB не хранит: считаем, что сейчас, иначе автопередача сразу сместит лидера
                team.members.put(player, new Team.Member(player, nameOf(world, server, player), e.getValue(), now, now));
            }
            if (team.members.isEmpty()) {
                skipped++;
                report.skipped(f.name(), "все игроки уже в командах RYSTeams");
                continue;
            }
            // владелец мог уже состоять в другой команде: лидером становится старший из оставшихся
            TeamService.ensureLeader(team);
            String name = TeamService.freeNameBy(f.name(), n -> nameTaken(store, newNames, n));
            if (!name.equals(f.name())) report.line(f.name() + ": название занято, стало " + name);
            team.name = name;
            int max = works.repka.rysteams.TeamsConfig.maxMembers();
            if (max > 0 && team.members.size() > max) report.line(name + ": перенесена сверх лимита участников, " + team.members.size() + " из " + max);
            created.add(team);
            newNames.put(team.id, name);
            for (UUID id : team.members.keySet()) taken.put(id, team.id);
            players += team.members.size();
        }
        // всё посчитано: хранилище меняется одним шагом
        for (Team team : created) {
            store.teams.put(team.id, team);
            for (UUID id : team.members.keySet()) store.byPlayer.put(id, team.id);
        }
        store.ftbImported = true;
        store.setDirty();
        report.count("teams", created.size()).count("players", players).count("existing", existing).count("conflicts", conflicts);
        return new Result(created.size(), players, skipped + existing);
    }

    private static boolean nameTaken(TeamStore store, Map<UUID, String> newNames, String name) {
        String n = TeamService.normName(name);
        for (Team t : store.teams.values()) if (t.name.equalsIgnoreCase(n)) return true;
        for (String s : newNames.values()) if (s.equalsIgnoreCase(n)) return true;
        return false;
    }

    /** Чем команда RYSTeams отличается от той же команды FTB: пустая строка, если состав и роли совпадают. */
    private static String diff(FtbTeam f, Team own, Path world, @Nullable MinecraftServer server) {
        List<String> out = new ArrayList<>();
        Set<UUID> all = new HashSet<>(f.members().keySet());
        all.addAll(own.members.keySet());
        for (UUID p : all) {
            Role was = f.members().get(p);
            Team.Member now = own.members.get(p);
            if (was != null && now == null) out.add("нет " + nameOf(world, server, p));
            else if (was == null) out.add("новый " + now.name);
            else if (was != now.role) out.add(now.name + " " + was.key() + " → " + now.role.key());
        }
        out.sort(null);
        return String.join(", ", out);
    }

    /** Команды из папки {@code party}, по имени файла. Файл, который не прочитался, отмечает перенос частичным. */
    static List<FtbTeam> read(Path world, Migrations.Report report) throws IOException {
        Path dir = partyDir(world);
        List<FtbTeam> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".snbt")).sorted().toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                FtbTeam team = convert(Snbt.readObject(file), name);
                if (team == null) {
                    report.skipped(name, "нет id или ролей");
                    continue;
                }
                out.add(team);
            } catch (IOException | RuntimeException e) {
                RYSTeams.LOG.warn("{} команда FTB {} не прочитана: {}", Migrations.TAG, name, e.toString());
                report.partial("файл " + name + " не прочитан: " + e.getMessage());
            }
        }
        return out;
    }

    static @Nullable FtbTeam convert(JsonObject root, String file) {
        UUID id = Snbt.uuid(root.get("id"));
        if (id == null) return null;
        JsonObject props = Snbt.object(root, "properties");
        String name = TeamService.normName(Snbt.string(props, "ftbteams:display_name", ""));
        if (name.length() < 3) name = "Team";
        if (name.length() > 24) name = name.substring(0, 24).strip();
        int color = Team.COLORS[Math.floorMod(id.hashCode(), Team.COLORS.length)];
        String c = Snbt.string(props, "ftbteams:color", "");
        if (c.startsWith("#")) {
            try {
                color = Integer.parseUnsignedInt(c.substring(1), 16) & 0xFFFFFF;
            } catch (NumberFormatException ignored) {
                // цвет по UUID
            }
        }
        boolean open = Snbt.bool(props, "ftbteams:free_to_join", false);
        if (!(root.get("ranks") instanceof JsonObject ranks)) return null;
        Map<UUID, Role> members = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : ranks.entrySet()) {
            String rank = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "";
            Role role = switch (rank) {
                case "owner" -> Role.LEADER;
                case "officer" -> Role.OFFICER;
                case "member" -> Role.MEMBER;
                // союзники и приглашённые в FTB это отдельные отношения, а не участники
                default -> null;
            };
            UUID player = Snbt.uuid(new com.google.gson.JsonPrimitive(e.getKey()));
            if (role != null && player != null) members.put(player, role);
        }
        return new FtbTeam(id, name, color, open, members, file);
    }

    /** Ник из файла игрока FTB Teams, затем из кэша профилей сервера. Если нигде нет, начало UUID. */
    static String nameOf(Path world, @Nullable MinecraftServer server, UUID player) {
        Path file = world.resolve("ftbteams").resolve("player").resolve(player + ".snbt");
        if (Files.isRegularFile(file)) {
            try {
                String name = Snbt.string(Snbt.readObject(file), "player_name", "");
                if (!name.isBlank()) return name;
            } catch (IOException | RuntimeException e) {
                RYSTeams.LOG.warn("Файл игрока FTB {} не прочитан: {}", file.getFileName(), e.toString());
            }
        }
        var cache = server == null ? null : server.getProfileCache();
        if (cache != null) {
            var profile = cache.get(player);
            if (profile.isPresent() && profile.get().getName() != null) return profile.get().getName();
        }
        return placeholder(player);
    }

    private static String placeholder(UUID player) {
        return player.toString().substring(0, 8);
    }

    /** Прежний импорт записывал вместо ника начало UUID: исправляем уже перенесённые команды. */
    public static void repairNames(MinecraftServer server) {
        TeamStore store = TeamStore.get(server);
        Path world = world(server);
        boolean changed = false;
        for (Team team : store.teams.values()) {
            for (Team.Member member : team.members.values()) {
                if (!member.name.equals(placeholder(member.id))) continue;
                String real = nameOf(world, server, member.id);
                if (real.equals(member.name)) continue;
                member.name = real;
                changed = true;
            }
        }
        if (changed) store.setDirty();
    }
}
