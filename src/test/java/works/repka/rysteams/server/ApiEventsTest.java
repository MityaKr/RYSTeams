package works.repka.rysteams.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.data.Team;

/** События контракта сверкой только изменённых команд (разбор логики 19.11, раздел 5, пункт 7), полный обход как раньше. */
class ApiEventsTest {
    static final List<String> EVENTS = new ArrayList<>();

    @BeforeAll
    static void listen() {
        TeamsApi.listen(new TeamsApi.Listener() {
            @Override
            public void onRoleChanged(UUID team, UUID player) {
                EVENTS.add("role " + team);
            }

            @Override
            public void onAlliesChanged(UUID team) {
                EVENTS.add("allies " + team);
            }

            @Override
            public void onRenamed(UUID team) {
                EVENTS.add("name " + team);
            }
        });
    }

    TeamStore store;
    Team a, b;
    UUID lena = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        EVENTS.clear();
        store = new TeamStore();
        a = team("Alpha");
        b = team("Beta");
        a.members.put(lena, new Team.Member(lena, "Lena", Role.MEMBER, 1, 1));
        ApiEvents.reset(store);
    }

    private Team team(String name) {
        Team t = new Team(UUID.randomUUID(), name, 0x59FF80, "minecraft:white_banner", 1);
        store.teams.put(t.id, t);
        return t;
    }

    @Test
    void onlyChangedTeamsAreChecked() {
        a.members.get(lena).role = Role.OFFICER;
        b.name = "Beta 2";
        ApiEvents.check(store, Set.of(a.id));
        assertEquals(List.of("role " + a.id), EVENTS, "Beta не помечена: её имя ждёт полного обхода");
        ApiEvents.check(store);
        assertEquals(List.of("role " + a.id, "name " + b.id), EVENTS);
    }

    @Test
    void allianceReachesTheUnmarkedSide() {
        a.allies.add(b.id);
        b.allies.add(a.id);
        ApiEvents.check(store, Set.of(a.id));
        assertTrue(EVENTS.contains("allies " + a.id) && EVENTS.contains("allies " + b.id), EVENTS.toString());
        EVENTS.clear();
        // распад союза помечен только у Alpha: Beta находится по прежнему снимку
        a.allies.remove(b.id);
        b.allies.remove(a.id);
        ApiEvents.check(store, Set.of(a.id));
        assertTrue(EVENTS.contains("allies " + b.id), EVENTS.toString());
    }

    @Test
    void goneTeamIsForgotten() {
        store.teams.remove(b.id);
        ApiEvents.check(store, Set.of(b.id));
        assertTrue(EVENTS.isEmpty());
        // та же команда вернулась (откат слияния): это новая команда, событий нет
        store.teams.put(b.id, b);
        b.name = "Beta 3";
        ApiEvents.check(store, Set.of(b.id));
        assertTrue(EVENTS.isEmpty(), EVENTS.toString());
    }
}
