package works.repka.rysteams;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;
import works.repka.rysteams.api.Permission;

/** Настройки сервера: {@code world/serverconfig/rysteams-server.toml}. */
public final class TeamsConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<String> MODE;
    private static final ModConfigSpec.IntValue MAX_MEMBERS;
    private static final ModConfigSpec.ConfigValue<String> SERVER_TEAM;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> LOCKED;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        MODE = b.comment("normal: players create teams; open: new teams are open to join; server: everyone is in one team")
            .define("mode", "normal");
        MAX_MEMBERS = b.comment("Team size limit, 0 means no limit").defineInRange("maxMembers", 0, 0, 1000);
        SERVER_TEAM = b.comment("Team name in server mode").define("serverTeamName", "Server");
        LOCKED = b.comment("Permissions team leaders cannot change, for example [\"see_alerts\"]")
            .defineListAllowEmpty("lockedPermissions", List.of(), () -> "invite", o -> o instanceof String);
        SPEC = b.build();
    }

    private TeamsConfig() {}

    public static boolean isServerMode() {
        return SPEC.isLoaded() && "server".equals(MODE.get());
    }

    public static boolean isOpenMode() {
        return SPEC.isLoaded() && "open".equals(MODE.get());
    }

    public static int maxMembers() {
        return SPEC.isLoaded() ? MAX_MEMBERS.get() : 0;
    }

    public static String serverTeamName() {
        return SPEC.isLoaded() ? SERVER_TEAM.get() : "Server";
    }

    public static boolean isLocked(Permission permission) {
        return SPEC.isLoaded() && LOCKED.get().contains(permission.key());
    }
}
