package works.repka.rysteams.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import works.repka.rysteams.data.Team;

/** Все команды мира. Файл {@code data/rysteams.dat}, формат версионирован полем {@code v}. */
public final class TeamStore extends SavedData {
    private static final int VERSION = 1;
    private static final String NAME = "rysteams";

    /** Распущенная команда: лидер может вернуть её в течение недели. Распущенную администратором возвращает только администратор. */
    public record Archived(CompoundTag team, UUID leader, long time, boolean byAdmin) {}

    public final Map<UUID, Team> teams = new LinkedHashMap<>();
    public final Map<UUID, UUID> byPlayer = new HashMap<>();
    public final List<Archived> archive = new ArrayList<>();
    public final Set<UUID> hideCoords = new HashSet<>();
    /** Кто выключил «Сообщать о моей смерти». По умолчанию сообщение включено, поэтому храним только отказ. */
    public final Set<UUID> quietDeath = new HashSet<>();
    public boolean ftbImported;
    /** Общая команда режима server. */
    public UUID serverTeam;

    public static TeamStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(TeamStore::new, TeamStore::load), NAME);
    }

    private static TeamStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TeamStore store = new TeamStore();
        for (Tag x : tag.getList("teams", Tag.TAG_COMPOUND)) {
            Team team = Team.load((CompoundTag) x);
            store.teams.put(team.id, team);
            for (UUID member : team.members.keySet()) store.byPlayer.put(member, team.id);
        }
        for (Tag x : tag.getList("archive", Tag.TAG_COMPOUND)) {
            CompoundTag a = (CompoundTag) x;
            store.archive.add(new Archived(a.getCompound("team"), a.getUUID("leader"), a.getLong("time"), a.getBoolean("admin")));
        }
        for (Tag x : tag.getList("hidden", Tag.TAG_STRING)) store.hideCoords.add(UUID.fromString(x.getAsString()));
        for (Tag x : tag.getList("quietDeath", Tag.TAG_STRING)) store.quietDeath.add(UUID.fromString(x.getAsString()));
        store.ftbImported = tag.getBoolean("ftb");
        if (tag.hasUUID("serverTeam")) store.serverTeam = tag.getUUID("serverTeam");
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("v", VERSION);
        ListTag list = new ListTag();
        for (Team team : teams.values()) list.add(team.save(false));
        tag.put("teams", list);
        ListTag archived = new ListTag();
        for (Archived a : archive) {
            CompoundTag x = new CompoundTag();
            x.put("team", a.team);
            x.putUUID("leader", a.leader);
            x.putLong("time", a.time);
            x.putBoolean("admin", a.byAdmin);
            archived.add(x);
        }
        tag.put("archive", archived);
        ListTag hidden = new ListTag();
        for (UUID id : hideCoords) hidden.add(StringTag.valueOf(id.toString()));
        tag.put("hidden", hidden);
        ListTag quiet = new ListTag();
        for (UUID id : quietDeath) quiet.add(StringTag.valueOf(id.toString()));
        tag.put("quietDeath", quiet);
        tag.putBoolean("ftb", ftbImported);
        if (serverTeam != null) tag.putUUID("serverTeam", serverTeam);
        return tag;
    }

    public Team teamOf(UUID player) {
        UUID id = byPlayer.get(player);
        return id == null ? null : teams.get(id);
    }
}
