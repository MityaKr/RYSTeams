package works.repka.rysteams.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.data.Team;

/**
 * Перенос команд из файлов FTB Teams. Читаются только данные мира, код FTB не используется.
 * UUID команд сохраняется, поэтому прогресс в других модах остаётся привязан к ним без таблицы соответствия.
 */
public final class FtbImport {
    private FtbImport() {}

    public record Result(int teams, int players, int skipped) {}

    public static Result run(MinecraftServer server) throws IOException {
        TeamStore store = TeamStore.get(server);
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("ftbteams").resolve("party");
        if (!Files.isDirectory(dir)) return new Result(0, 0, 0);
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".snbt")).sorted().toList();
        }
        int teams = 0, players = 0, skipped = 0;
        for (Path file : files) {
            try {
                Object parsed = new Snbt(Files.readString(file, StandardCharsets.UTF_8)).value();
                if (!(parsed instanceof Map<?, ?> root)) {
                    skipped++;
                    continue;
                }
                Team team = convert(root);
                if (team == null || store.teams.containsKey(team.id)) {
                    skipped++;
                    continue;
                }
                team.members.keySet().removeIf(store.byPlayer::containsKey);
                if (team.members.isEmpty()) {
                    skipped++;
                    continue;
                }
                store.teams.put(team.id, team);
                for (UUID id : team.members.keySet()) store.byPlayer.put(id, team.id);
                teams++;
                players += team.members.size();
            } catch (RuntimeException e) {
                RYSTeams.LOG.warn("Команда FTB {} не импортирована: {}", file.getFileName(), e.toString());
                skipped++;
            }
        }
        store.ftbImported = true;
        TeamService.imported();
        return new Result(teams, players, skipped);
    }

    private static Team convert(Map<?, ?> root) {
        UUID id = UUID.fromString((String) root.get("id"));
        Map<?, ?> props = root.get("properties") instanceof Map<?, ?> m ? m : Map.of();
        String name = props.get("ftbteams:display_name") instanceof String s && !s.isBlank() ? s : "Team";
        int color = Team.COLORS[Math.floorMod(id.hashCode(), Team.COLORS.length)];
        if (props.get("ftbteams:color") instanceof String c && c.startsWith("#")) color = Integer.parseUnsignedInt(c.substring(1), 16) & 0xFFFFFF;
        long now = TeamService.now();
        Team team = new Team(id, name, color, "minecraft:white_banner", now);
        if (Boolean.TRUE.equals(props.get("ftbteams:free_to_join"))) team.join = Team.JoinMode.OPEN;
        if (!(root.get("ranks") instanceof Map<?, ?> ranks)) return null;
        for (Map.Entry<?, ?> e : ranks.entrySet()) {
            Role role = switch (String.valueOf(e.getValue())) {
                case "owner" -> Role.LEADER;
                case "officer" -> Role.OFFICER;
                case "member" -> Role.MEMBER;
                // союзники и приглашённые в FTB это отдельные отношения, а не участники
                default -> null;
            };
            if (role == null) continue;
            UUID player = UUID.fromString((String) e.getKey());
            team.members.put(player, new Team.Member(player, player.toString().substring(0, 8), role, now, 0));
        }
        return team;
    }

    /** Разбор SNBT, который пишет FTB: ключи и значения разделены переводом строки, запятых нет. */
    static final class Snbt {
        private final String s;
        private int p;

        Snbt(String text) {
            s = text;
        }

        Object value() {
            ws();
            char c = s.charAt(p);
            if (c == '{') return compound();
            if (c == '[') return list();
            if (c == '"') return string();
            return bare();
        }

        private Map<String, Object> compound() {
            p++;
            Map<String, Object> out = new LinkedHashMap<>();
            for (; ; ) {
                ws();
                if (s.charAt(p) == '}') {
                    p++;
                    return out;
                }
                String key = s.charAt(p) == '"' ? string() : word();
                ws();
                if (s.charAt(p) != ':') throw new IllegalArgumentException("ожидалось ':' в позиции " + p);
                p++;
                out.put(key, value());
            }
        }

        private List<Object> list() {
            p++;
            ws();
            // типизированный массив [I; 1, 2]
            if (p + 1 < s.length() && s.charAt(p + 1) == ';') p += 2;
            List<Object> out = new java.util.ArrayList<>();
            for (; ; ) {
                ws();
                if (s.charAt(p) == ']') {
                    p++;
                    return out;
                }
                out.add(value());
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            p++;
            while (s.charAt(p) != '"') {
                if (s.charAt(p) == '\\') p++;
                b.append(s.charAt(p++));
            }
            p++;
            return b.toString();
        }

        private String word() {
            int start = p;
            while (p < s.length() && " \t\r\n,:{}[]".indexOf(s.charAt(p)) < 0) p++;
            return s.substring(start, p);
        }

        private Object bare() {
            String w = word();
            if (w.equals("true")) return Boolean.TRUE;
            if (w.equals("false")) return Boolean.FALSE;
            return w;
        }

        private void ws() {
            while (p < s.length() && " \t\r\n,".indexOf(s.charAt(p)) >= 0) p++;
        }
    }
}
