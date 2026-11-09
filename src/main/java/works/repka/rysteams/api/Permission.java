package works.repka.rysteams.api;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Что разрешено ролям. Значения по умолчанию из макета матрицы прав. */
public enum Permission {
    INVITE(Role.LEADER, Role.OFFICER),
    ACCEPT_REQUESTS(Role.LEADER, Role.OFFICER),
    CLAIM_REWARDS(Role.LEADER, Role.OFFICER, Role.MEMBER),
    SUBMIT_TASKS(Role.LEADER, Role.OFFICER, Role.MEMBER),
    EDIT_BOARD(Role.LEADER, Role.OFFICER, Role.MEMBER),
    EDIT_MARKS(Role.LEADER, Role.OFFICER),
    PING(Role.LEADER, Role.OFFICER, Role.MEMBER, Role.GUEST),
    SEE_ALERTS(Role.LEADER, Role.OFFICER, Role.MEMBER, Role.GUEST),
    /** Исключать, менять роли и настройки может только лидер, строка зафиксирована. */
    MANAGE(Role.LEADER);

    private final Set<Role> defaults;

    Permission(Role first, Role... rest) {
        this.defaults = EnumSet.of(first, rest);
    }

    public Set<Role> defaults() {
        return EnumSet.copyOf(defaults);
    }

    public boolean locked() {
        return this == MANAGE;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Permission parse(String text) {
        for (Permission p : values()) if (p.key().equals(text)) return p;
        return null;
    }
}
