package works.repka.rysteams.api;

import java.util.Locale;

/** Роли команды от старшей к младшей. */
public enum Role {
    LEADER, OFFICER, MEMBER, GUEST;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role parse(String text) {
        for (Role role : values()) if (role.key().equals(text)) return role;
        return MEMBER;
    }
}
