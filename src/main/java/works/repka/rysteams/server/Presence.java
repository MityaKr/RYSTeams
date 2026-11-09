package works.repka.rysteams.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.rysteams.data.Team;
import works.repka.rysteams.network.ModNetwork;

/** Кто где. Раз в 2 секунды и только своей команде; ушло лишь то, что изменилось. */
public final class Presence {
    private static final Map<UUID, Map<UUID, CompoundTag>> SENT = new HashMap<>();
    /** То, что мод семьи сообщил о занятии игрока. */
    private static final Map<UUID, String> STATUS = new HashMap<>();

    private Presence() {}

    static void reset() {
        SENT.clear();
        STATUS.clear();
    }

    public static void setStatus(UUID player, String status) {
        if (status == null || status.isEmpty()) STATUS.remove(player);
        else STATUS.put(player, status);
    }

    static void broadcast(MinecraftServer server, TeamStore store) {
        for (Team team : store.teams.values()) {
            for (Team.Member viewer : team.members.values()) {
                ServerPlayer target = server.getPlayerList().getPlayer(viewer.id);
                if (target == null) {
                    SENT.remove(viewer.id);
                    continue;
                }
                sendTo(server, store, team, target);
            }
        }
    }

    private static void sendTo(MinecraftServer server, TeamStore store, Team team, ServerPlayer viewer) {
        Map<UUID, CompoundTag> sent = SENT.computeIfAbsent(viewer.getUUID(), k -> new HashMap<>());
        ListTag changed = new ListTag();
        for (Team.Member m : team.members.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(m.id);
            if (player == null || m.id.equals(viewer.getUUID())) {
                if (sent.remove(m.id) != null) {
                    CompoundTag gone = new CompoundTag();
                    gone.putUUID("id", m.id);
                    gone.putBoolean("gone", true);
                    changed.add(gone);
                }
                continue;
            }
            CompoundTag now = describe(store, m.id, player);
            if (!now.equals(sent.get(m.id))) {
                sent.put(m.id, now);
                changed.add(now.copy());
            }
        }
        if (changed.isEmpty()) return;
        CompoundTag out = new CompoundTag();
        out.putString("type", "presence");
        out.put("list", changed);
        ModNetwork.send(viewer, out);
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
