package works.repka.rysteams.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Метка команды в NBT: «Кто видит: команда и союз» (0.2.6) переживает сохранение, старые сохранения читаются как «команда». */
class TeamMarkTest {
    @Test
    void sharedWithAlliesSurvivesSaveAndLoad() {
        Team.Mark m = new Team.Mark(UUID.randomUUID(), "Склад", "minecraft:overworld", 1, 64, -3, "minecraft:chest", "MityaYS", "", 0, null, true);
        Team.Mark back = Team.Mark.load(m.save());
        assertEquals(m, back);
        assertTrue(back.allies());
    }

    @Test
    void oldSaveIsTeamOnly() {
        Team.Mark m = new Team.Mark(UUID.randomUUID(), "Портал", "minecraft:the_nether", 5, 70, 5, "minecraft:compass", "Kifa65", "");
        CompoundTag t = m.save();
        assertFalse(t.contains("ally"), "поле пишется только для открытых союзу");
        assertFalse(Team.Mark.load(t).allies());
    }

    @Test
    void teamKeepsTheFlag() {
        Team team = new Team(UUID.randomUUID(), "VENIK", 0x59FF80, "minecraft:white_banner", 1);
        team.marks.add(new Team.Mark(UUID.randomUUID(), "Склад", "minecraft:overworld", 1, 64, -3, "minecraft:chest", "MityaYS", "", 0, null, true));
        team.marks.add(new Team.Mark(UUID.randomUUID(), "Портал", "minecraft:overworld", 9, 64, 9, "minecraft:compass", "MityaYS", ""));
        Team back = Team.load(team.save(false));
        assertTrue(back.marks.get(0).allies());
        assertFalse(back.marks.get(1).allies());
    }
}
