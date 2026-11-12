package works.repka.rysteams.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.rysteams.data.Team;
import works.repka.rysteams.network.ModNetwork;

/** Кто где. Раз в 2 секунды и только своей команде; ушло лишь то, что изменилось. */
public final class Presence {
    /** Сдвиг меньше этого числа блоков не рассылается: на панели метры и стрелка, точность в блок не нужна. */
    private static final int MOVE_STEP = 2;
    private static final Map<UUID, Map<UUID, CompoundTag>> SENT = new HashMap<>();
    /** То, что мод семьи сообщил о занятии игрока. */
    private static final Map<UUID, String> STATUS = new HashMap<>();

    private Presence() {}

    static void reset() {
        SENT.clear();
        STATUS.clear();
    }

    /** Игрок вышел из игры. */
    static void forget(UUID player) {
        SENT.remove(player);
        STATUS.remove(player);
    }

    /** Состав команды изменился: этим зрителям в следующий раз уйдёт всё заново. */
    static void resend(Collection<UUID> viewers) {
        for (UUID id : viewers) SENT.remove(id);
    }

    public static void setStatus(UUID player, String status) {
        if (status == null || status.isEmpty()) STATUS.remove(player);
        else STATUS.put(player, status);
    }

    static void broadcast(MinecraftServer server, TeamStore store) {
        Set<UUID> viewers = new HashSet<>();
        for (Team team : store.teams.values()) {
            // описание каждого участника строится один раз и одним экземпляром уходит всем зрителям
            Map<UUID, CompoundTag> now = new HashMap<>();
            List<ServerPlayer> online = new ArrayList<>();
            for (Team.Member m : team.members.values()) {
                ServerPlayer player = server.getPlayerList().getPlayer(m.id);
                if (player == null) continue;
                online.add(player);
                now.put(m.id, describe(store, m.id, player));
            }
            for (ServerPlayer viewer : online) {
                viewers.add(viewer.getUUID());
                sendTo(viewer, now);
            }
        }
        // вышел из игры или из команды: его записи больше не нужны
        SENT.keySet().retainAll(viewers);
    }

    private static void sendTo(ServerPlayer viewer, Map<UUID, CompoundTag> now) {
        UUID self = viewer.getUUID();
        Map<UUID, CompoundTag> sent = SENT.computeIfAbsent(self, k -> new HashMap<>());
        ListTag changed = new ListTag();
        for (Iterator<UUID> it = sent.keySet().iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (now.containsKey(id) && !id.equals(self)) continue;
            it.remove();
            CompoundTag gone = new CompoundTag();
            gone.putUUID("id", id);
            gone.putBoolean("gone", true);
            changed.add(gone);
        }
        for (Map.Entry<UUID, CompoundTag> e : now.entrySet()) {
            if (e.getKey().equals(self)) continue;
            CompoundTag old = sent.get(e.getKey());
            if (old != null && !differs(old, e.getValue())) continue;
            sent.put(e.getKey(), e.getValue());
            changed.add(e.getValue());
        }
        if (changed.isEmpty()) return;
        CompoundTag out = new CompoundTag();
        out.putString("type", "presence");
        out.put("list", changed);
        ModNetwork.send(viewer, out);
    }

    private static boolean differs(CompoundTag old, CompoundTag now) {
        if (!old.getString("dim").equals(now.getString("dim")) || !old.getString("status").equals(now.getString("status"))) return true;
        boolean pos = now.getBoolean("pos");
        if (old.getBoolean("pos") != pos) return true;
        if (!pos) return false;
        int d = Math.max(Math.abs(old.getInt("x") - now.getInt("x")), Math.max(Math.abs(old.getInt("y") - now.getInt("y")), Math.abs(old.getInt("z") - now.getInt("z"))));
        return d >= MOVE_STEP;
    }

    private static CompoundTag describe(TeamStore store, UUID id, ServerPlayer player) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("dim", player.level().dimension().location().toString());
        if (!store.hideCoords.contains(id)) {
            t.putInt("x", player.getBlockX());
            t.putInt("y", player.getBlockY());
            t.putInt("z", player.getBlockZ());
            t.putBoolean("pos", true);
        }
        String status = STATUS.get(id);
        if (status != null) t.putString("status", status);
        return t;
    }
}
