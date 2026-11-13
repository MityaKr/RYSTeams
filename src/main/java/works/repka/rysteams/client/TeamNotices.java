package works.repka.rysteams.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Notice;

/**
 * Приглашение и строки команд в общих уведомлениях семьи (RYSCore {@link Notice}, макет {@code _audit/notify}, вариант A): справа
 * вверху, в колонке тостов игры, поверх окон. Строка («Lena_R вошла в команду») живёт 3,5 с, новая заменяет прежнюю; ошибка красная.
 * Приглашение — карточка с кнопками: она висит, пока виден таймер приглашения, клавиши Y, U и N в мире работают как раньше.
 */
final class TeamNotices {
    static final long ROW_MS = 3500;
    private static final String ROW = "row";
    /** Какое приглашение сейчас в уведомлениях: команда и срок, чтобы повторное с новым сроком встало заново. */
    private static String shown = "";

    private TeamNotices() {}

    static void row(Component text, boolean bad) {
        Notice row = Notice.row(Notice.TEAMS, ROW).icon(Icons.Shape.USERS).title(text.getString()).millis(ROW_MS);
        if (bad) row.bad();
        row.show();
    }

    /** Каждый тик клиента: новое приглашение встаёт в уведомления. */
    static void tick() {
        if (!TeamClient.inviteVisible()) {
            shown = "";
            return;
        }
        TeamClient.Info invite = TeamClient.invites.get(0);
        String key = invite.id() + "/" + invite.until();
        if (key.equals(shown)) return;
        shown = key;
        UUID team = invite.id();
        Notice card = Notice.buttons(Notice.TEAMS, "invite:" + team)
            .title(invite.by())
            .lines(tr("rysteams.notice.invite_keys", invite.name(), key(ClientEntry.ACCEPT), key(ClientEntry.DECLINE)),
                tr("rysteams.notice.invite", invite.name()))
            .item(TeamClient.icon(invite.icon()))
            .button(tr("rysteams.notice.accept"), true, () -> ClientEntry.answer("accept"))
            .button(tr("rysteams.invite.fresh"), false, () -> ClientEntry.answer("fresh"))
            .button(tr("rysteams.notice.no"), false, () -> ClientEntry.answer("no"))
            .alive(() -> TeamClient.inviteVisible() && TeamClient.invites.get(0).id().equals(team))
            .sound(Notice.Sound.INVITE);
        if (invite.until() > 0) {
            card.timer(() -> {
                long left = Math.max(0, (invite.until() - System.currentTimeMillis()) / 1000);
                return left / 60 + ":" + String.format("%02d", left % 60);
            });
        }
        UUID inviter = inviterId(invite.by());
        if (inviter != null) card.head(inviter);
        card.show();
    }

    /** Голова пригласившего, если он в сети; иначе значок команды. */
    private static UUID inviterId(String name) {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(name);
        return info == null ? null : info.getProfile().getId();
    }

    /** Буква клавиши латиницей, как на клавиатуре, при любой раскладке: «Y», а не «Н». */
    private static String key(net.minecraft.client.KeyMapping mapping) {
        String name = mapping.getKey().getName(), prefix = "key.keyboard.";
        boolean letter = name.startsWith(prefix) && name.length() == prefix.length() + 1;
        return letter ? name.substring(prefix.length()).toUpperCase(java.util.Locale.ROOT) : mapping.getTranslatedKeyMessage().getString();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
