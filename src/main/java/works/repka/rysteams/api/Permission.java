package works.repka.rysteams.api;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Что разрешено ролям. Значения по умолчанию из макета матрицы прав. */
public enum Permission {
    INVITE(Role.LEADER, Role.OFFICER),
    ACCEPT_REQUESTS(Role.LEADER, Role.OFFICER),
    CLAIM_REWARDS(Role.LEADER, Role.OFFICER, Role.MEMBER),
    /**
     * Тратить казну команды в магазине квестов (RYSQuests). Класть в казну можно без права. Проверяется по UUID без открытого
     * экрана: блоки магазина тратят казну от имени владельца блока.
     */
    SPEND_TREASURY(Role.LEADER, Role.OFFICER),
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

    /** Право по имени или null. Таблица собрана один раз: {@code TeamsApi.can} зовут проверки привата на каждое действие. */
    public static Permission parse(String text) {
        return text == null ? null : BY_KEY.get(text);
    }

    private static final java.util.Map<String, Permission> BY_KEY;

    static {
        java.util.Map<String, Permission> map = new java.util.HashMap<>();
        for (Permission p : values()) map.put(p.key(), p);
        BY_KEY = java.util.Map.copyOf(map);
    }
}
