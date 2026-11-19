package works.repka.rysteams.api;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** Права по имени из готовой таблицы (разбор логики 19.11): каждое находится, чужое и пустое дают null. */
class PermissionTest {
    @Test
    void everyKeyParsesBack() {
        for (Permission p : Permission.values()) assertSame(p, Permission.parse(p.key()));
    }

    @Test
    void unknownIsNull() {
        assertNull(Permission.parse("rysclaims:claim"));
        assertNull(Permission.parse(""));
        assertNull(Permission.parse(null));
        assertNull(Permission.parse("INVITE"), "имя пишется строчными, как в сохранениях");
    }
}
