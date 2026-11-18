package works.repka.rysteams.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.ryscore.migrate.Migrations;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.data.Team;

/**
 * Шаг {@code teams} на копии файлов FTB сервера RepYS ({@code _shared/_saved/fixtures/ftb-2026-11-18}): первый запуск,
 * повтор, сбой записи с продолжением при следующем запуске и живой случай, где команды уже перенесены.
 */
class TeamsMigrationTest {
    static final UUID VENIK = UUID.fromString("0c383da8-5fb5-41ef-96be-7cae45e6e640");
    static final UUID MITYA = UUID.fromString("216ea99a-d6eb-45be-8a5b-3da7852588a5");
    static final UUID KIFA = UUID.fromString("633d6f3f-f318-4004-a3fb-b5131b22c28b");

    @TempDir
    Path game;
    Path world;

    /** Хранилище «в памяти сервера» и то, что лежит «на диске»; перезапуск читает диск заново. */
    TeamStore current = new TeamStore();
    CompoundTag disk;
    boolean failSave;
    int saves;

    @BeforeEach
    void setUp() throws IOException {
        Path fixtures = Path.of(System.getProperty("rysteams.fixtures", "../../_shared/_saved/fixtures/ftb-2026-11-18"));
        assumeTrue(Files.isDirectory(fixtures.resolve("world/ftbteams/party")), "нет копии файлов FTB: " + fixtures);
        world = game.resolve("world");
        copy(fixtures.resolve("world/ftbteams"), world.resolve("ftbteams"));
        flag("[\"teams\", \"quests\", \"claims\", \"map\"]");
        Migrations.register(new TeamsMigration(new TeamsMigration.Storage() {
            @Override
            public TeamStore store(Migrations.Context c) {
                return current;
            }

            @Override
            public void save(Migrations.Context c, TeamStore store) throws IOException {
                if (failSave) throw new IOException("диск полон");
                saves++;
                disk = store.save(new CompoundTag(), null);
            }
        }));
    }

    private void flag(String steps) throws IOException {
        Path file = game.resolve(Migrations.FLAG_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"v\": 1, \"from\": \"ftb\", \"steps\": " + steps + "}", StandardCharsets.UTF_8);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }

    private Migrations.Outcome start() {
        Migrations.Result r = Migrations.runServer(game, world, null);
        return r.outcome(TeamsMigration.ID);
    }

    private void restart() {
        current = disk == null ? new TeamStore() : TeamStore.load(disk.copy(), null);
    }

    private static Map<UUID, CompoundTag> snapshot(TeamStore store) {
        Map<UUID, CompoundTag> out = new LinkedHashMap<>();
        for (Team t : store.teams.values()) out.put(t.id, t.save(false));
        return out;
    }

    private static Team team(TeamStore store, UUID id, String name, Map<UUID, Role> members) {
        Team t = new Team(id, name, 0x59FF80, "minecraft:white_banner", 1);
        members.forEach((p, role) -> t.members.put(p, new Team.Member(p, p.toString().substring(0, 8), role, 1, 1)));
        store.teams.put(id, t);
        for (UUID p : members.keySet()) store.byPlayer.put(p, id);
        return t;
    }

    @Test
    void firstRunBringsVenikWithBothPlayersAndRoles() {
        Migrations.Outcome o = start();
        assertEquals(Migrations.Status.DONE, o.status(), o.toString());
        Team venik = current.teams.get(VENIK);
        assertNotNull(venik);
        assertEquals("VENIK", venik.name);
        assertEquals(0x59FF80, venik.color);
        assertEquals(2, venik.members.size());
        assertEquals(Role.LEADER, venik.members.get(MITYA).role);
        assertEquals(Role.OFFICER, venik.members.get(KIFA).role);
        assertEquals("MityaYS", venik.members.get(MITYA).name);
        assertEquals(VENIK, current.byPlayer.get(KIFA));
        assertTrue(current.ftbImported);
        assertEquals(1L, o.report().counts().get("teams"));
        assertEquals(2L, o.report().counts().get("players"));
        assertTrue(Files.isRegularFile(world.resolve("rys/migrate.json")), "отметка ядра");
        assertTrue(Files.isDirectory(world.resolve("rys/backup")), "копия FTB ядра");
        assertTrue(Files.isRegularFile(world.resolve("ftbteams/party/" + VENIK + ".snbt")), "исходники FTB на месте");
    }

    @Test
    void secondStartDoesNothing() {
        start();
        Map<UUID, CompoundTag> before = snapshot(current);
        int savesBefore = saves;
        restart();
        Migrations.Outcome again = start();
        assertEquals(Migrations.Status.ALREADY_DONE, again.status());
        assertEquals(before, snapshot(current));
        assertEquals(savesBefore, saves, "шаг не запускался");
    }

    @Test
    void lostMarkIsSafeToRepeat() throws IOException {
        start();
        Map<UUID, CompoundTag> before = snapshot(current);
        // отметка пропала (файл испорчен руками): шаг идёт заново и видит, что всё уже есть
        Files.writeString(world.resolve("rys/migrate.json"), "{ не json", StandardCharsets.UTF_8);
        restart();
        Migrations.Outcome again = start();
        assertEquals(Migrations.Status.DONE, again.status(), again.toString());
        assertEquals(0L, again.report().counts().get("teams"));
        assertTrue(again.report().skipped().contains("VENIK: уже есть"), again.toString());
        assertEquals(before, snapshot(current));
    }

    @Test
    void failedSaveLeavesNoMarkAndNextStartFinishes() {
        failSave = true;
        Migrations.Outcome failed = start();
        assertEquals(Migrations.Status.FAILED, failed.status());
        assertTrue(failed.message().contains("диск полон"));
        // сервер упал, ничего не записав: при следующем запуске база пустая
        failSave = false;
        restart();
        assertTrue(current.teams.isEmpty());
        Migrations.Outcome next = start();
        assertEquals(Migrations.Status.DONE, next.status(), next.toString());
        assertEquals(2, current.teams.get(VENIK).members.size());
        restart();
        assertEquals(2, current.teams.get(VENIK).members.size(), "записано на диск");
    }

    @Test
    void liveServerAlreadyMovedIsMarkedWithoutChanges() {
        Team own = team(current, VENIK, "VENIK", Map.of(MITYA, Role.LEADER, KIFA, Role.OFFICER));
        own.notes.add(new Team.Note(UUID.randomUUID(), "записка после переезда", "MityaYS", 5));
        Map<UUID, CompoundTag> before = snapshot(current);
        Migrations.Outcome o = start();
        assertEquals(Migrations.Status.DONE, o.status(), o.toString());
        assertEquals(0L, o.report().counts().get("teams"));
        assertEquals(1L, o.report().counts().get("existing"));
        assertEquals(0L, o.report().counts().get("conflicts"));
        assertTrue(o.report().skipped().contains("VENIK: уже есть"), o.toString());
        assertEquals(before, snapshot(current));
    }

    @Test
    void ownTeamThatDiffersStaysAsIs() {
        // в RYSTeams Kifa65 уже не офицер, а в команду вступил третий игрок
        UUID third = UUID.randomUUID();
        team(current, VENIK, "VENIK", Map.of(MITYA, Role.LEADER, KIFA, Role.MEMBER, third, Role.MEMBER));
        Map<UUID, CompoundTag> before = snapshot(current);
        Migrations.Outcome o = start();
        assertEquals(Migrations.Status.DONE, o.status());
        assertEquals(before, snapshot(current));
        assertEquals(1L, o.report().counts().get("conflicts"));
        String skipped = String.join("\n", o.report().skipped());
        assertTrue(skipped.contains("officer → member") && skipped.contains("новый"), skipped);
    }

    @Test
    void playersAlreadyInTeamsStayThere() {
        UUID other = UUID.randomUUID();
        team(current, other, "Other", Map.of(KIFA, Role.LEADER));
        Migrations.Outcome o = start();
        assertEquals(Migrations.Status.DONE, o.status());
        assertEquals(other, current.byPlayer.get(KIFA), "Kifa65 остался в своей команде");
        assertEquals(1, current.teams.get(other).members.size());
        Team venik = current.teams.get(VENIK);
        assertEquals(1, venik.members.size());
        assertEquals(Role.LEADER, venik.members.get(MITYA).role);
        assertEquals(1L, o.report().counts().get("conflicts"));
        assertTrue(o.report().lines().stream().anyMatch(l -> l.contains("уже в команде Other")), o.toString());
    }

    @Test
    void teamWhoseEveryPlayerIsTakenIsSkipped() {
        team(current, UUID.randomUUID(), "First", Map.of(MITYA, Role.LEADER));
        team(current, UUID.randomUUID(), "Second", Map.of(KIFA, Role.LEADER));
        Migrations.Outcome o = start();
        assertEquals(Migrations.Status.DONE, o.status());
        assertNull(current.teams.get(VENIK));
        assertTrue(o.report().skipped().contains("VENIK: все игроки уже в командах RYSTeams"), o.toString());
    }

    @Test
    void takenNameGetsSuffix() {
        team(current, UUID.randomUUID(), "venik", Map.of(UUID.randomUUID(), Role.LEADER));
        start();
        assertEquals("VENIK (2)", current.teams.get(VENIK).name);
    }

    @Test
    void withoutFlagNothingMoves() throws IOException {
        Files.move(game.resolve(Migrations.FLAG_FILE), game.resolve("flag.off"));
        Migrations.Outcome o = start();
        assertEquals(Migrations.Status.NOT_IN_FLAG, o.status());
        assertTrue(current.teams.isEmpty());
        assertFalse(Files.exists(world.resolve("rys/migrate.json")));
    }

    @Test
    void brokenFileMakesItPartial() throws IOException {
        Files.writeString(world.resolve("ftbteams/party/ffffffff-0000-0000-0000-000000000000.snbt"), "{ id: \"x\" ranks: {", StandardCharsets.UTF_8);
        Migrations.Outcome o = start();
        assertEquals(Migrations.Status.PARTIAL, o.status(), o.toString());
        assertNotNull(current.teams.get(VENIK), "остальные команды перенесены");
    }

    /** Копия мира тестового сервера RepYS: команды там перенесены руками 17.11, шаг должен только поставить отметку. */
    @Test
    void liveCopyOfRepysServer() throws IOException {
        Path live = Path.of(System.getProperty("rysteams.liveWorld", "C:/Projects/RepYS-ServerTest/world"));
        Path dat = live.resolve("data/rysteams.dat");
        assumeTrue(Files.isRegularFile(dat), "нет копии сервера: " + dat);
        CompoundTag root = NbtIo.readCompressed(dat, NbtAccounter.unlimitedHeap());
        current = TeamStore.load(root.getCompound("data"), null);
        assumeTrue(current.teams.containsKey(VENIK), "в копии нет VENIK");
        Map<UUID, CompoundTag> before = snapshot(current);
        Migrations.Outcome o = start();
        assertEquals(Migrations.Status.DONE, o.status(), o.toString());
        assertEquals(0L, o.report().counts().get("teams"));
        assertEquals(before, snapshot(current));
        System.out.println("[rys-migrate] копия сервера RepYS: " + o);
    }
}
