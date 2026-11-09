package works.repka.rysteams.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;

/** Каналы чата: Tab перебирает «общий, команда, союз», сервер различает их по префиксу сообщения. */
public final class ChatChannels {
    private static final int GENERAL = 0, TEAM = 1, ALLIES = 2;
    private static int channel = GENERAL;

    private ChatChannels() {}

    static void init() {
        NeoForge.EVENT_BUS.addListener(ChatChannels::onKey);
        NeoForge.EVENT_BUS.addListener(ChatChannels::onRender);
        NeoForge.EVENT_BUS.addListener(ChatChannels::onSend);
    }

    public static void reset() {
        channel = GENERAL;
    }

    /** Поле ввода чата закрыто для других классов, зато лежит среди виджетов экрана. */
    private static EditBox input(ChatScreen screen) {
        for (GuiEventListener child : screen.children()) if (child instanceof EditBox box) return box;
        return null;
    }

    private static int channels() {
        if (TeamClient.team == null) return 1;
        return TeamClient.allies.isEmpty() ? 2 : 3;
    }

    private static void onKey(ScreenEvent.KeyPressed.Pre e) {
        if (!(e.getScreen() instanceof ChatScreen screen) || e.getKeyCode() != GLFW.GLFW_KEY_TAB || channels() < 2) return;
        EditBox box = input(screen);
        // в командах Tab дополняет текст, это поведение игры не трогаем
        if (box == null || box.getValue().startsWith("/")) return;
        channel = (Math.min(channel, channels() - 1) + 1) % channels();
        e.setCanceled(true);
    }

    private static void onRender(ScreenEvent.Render.Post e) {
        if (!(e.getScreen() instanceof ChatScreen screen) || channels() < 2) return;
        channel = Math.min(channel, channels() - 1);
        GuiGraphics g = e.getGuiGraphics();
        Theme theme = ThemeManager.get();
        Minecraft mc = Minecraft.getInstance();
        String label = Component.translatable(channel == TEAM ? "rysteams.chat.team" : channel == ALLIES ? "rysteams.chat.allies" : "rysteams.chat.general").getString();
        int w = mc.font.width(label) + 10, y = screen.height - 27;
        int fill = channel == GENERAL ? theme.c("button.bg") : TeamHud.rgb(TeamClient.team.color);
        Draw.round(g, 2, y, w, 11, 3f, fill);
        Draw.text(g, label, 7, y + 2, channel == GENERAL ? theme.c("text.body") : 0xFFFFFFFF);
        Draw.textScaled(g, Component.translatable("rysteams.chat.hint").getString(), w + 7, y + 3, 0.75f, 0xFFB0B0B0);
    }

    private static void onSend(ClientChatEvent e) {
        String message = e.getMessage();
        if (channel == GENERAL || TeamClient.team == null || message.startsWith("/") || message.startsWith("!")) return;
        e.setMessage((channel == ALLIES ? "!!" : "!") + message);
    }
}
