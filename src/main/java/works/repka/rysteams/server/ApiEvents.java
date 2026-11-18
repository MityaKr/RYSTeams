package works.repka.rysteams.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.data.Team;

/**
 * События контракта ядра о смене роли, союза и имени (TeamsApi 1.11). Сервис меняет команды во многих местах (действия
 * экрана, слияние и его отмена, автопередача лидерства, уход), поэтому события считаются сверкой со снимком, а не
 * вызовами в каждом месте. Сверка идёт, когда команды помечены к рассылке, и раз в секунду; команд немного.
 */
final class ApiEvents {
    private record Snap(String name, int color, String icon, Map<UUID, Role> roles, Set<UUID> allies) {
        static Snap of(Team t) {
            Map<UUID, Role> roles = new LinkedHashMap<>();
            for (Team.Member m : t.members.values()) roles.put(m.id, m.role);
            return new Snap(t.name, t.color, t.icon, roles, new HashSet<>(t.allies));
        }
    }

    private static final Map<UUID, Snap> SNAPS = new HashMap<>();

    private ApiEvents() {}

    /** Снимок без событий: при запуске сервера. */
    static void reset(TeamStore store) {
        SNAPS.clear();
        for (Team t : store.teams.values()) SNAPS.put(t.id, Snap.of(t));
    }

    static void check(TeamStore store) {
        for (Team t : store.teams.values()) {
            Snap now = Snap.of(t), was = SNAPS.put(t.id, now);
            // новая команда: вступление и создание слушатель уже знает из onJoined
            if (was == null) continue;
            if (!was.name.equals(now.name) || was.color != now.color || !was.icon.equals(now.icon)) fire(l -> l.onRenamed(t.id));
            if (!was.allies.equals(now.allies)) fire(l -> l.onAlliesChanged(t.id));
            for (Map.Entry<UUID, Role> e : now.roles.entrySet()) {
                Role old = was.roles.get(e.getKey());
                // вступивший и ушедший приходят своими событиями
                if (old != null && old != e.getValue()) fire(l -> l.onRoleChanged(t.id, e.getKey()));
            }
        }
        SNAPS.keySet().retainAll(store.teams.keySet());
    }

    private static void fire(Consumer<TeamsApi.Listener> call) {
        for (TeamsApi.Listener l : TeamsApi.listeners()) {
            try {
                call.accept(l);
            } catch (Throwable t) {
                RYSTeams.LOG.warn("Слушатель команд упал", t);
            }
        }
    }
}
