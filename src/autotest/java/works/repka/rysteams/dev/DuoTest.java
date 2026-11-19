package works.repka.rysteams.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Permission;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.client.TeamClient;
import works.repka.rysteams.client.TeamScreen;
import works.repka.rysteams.network.ModNetwork;

/** Настоящий сервер и два настоящих клиента: приглашение, клавиша Y, права, присутствие, чат. Запуск: {@code duo.sh}. */
@EventBusSubscriber(modid = RYSTeams.MODID, value = Dist.CLIENT)
public final class DuoTest {
    private record Step(String name, BooleanSupplier ready, Runnable action, int pause) {}

    private static final String ROLE = System.getProperty("rysteams.duo");
    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> CHAT = new ArrayList<>();
    private static boolean built;
    private static int index, wait, waited, shots = 1, failures;

    private DuoTest() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (ROLE == null) return;
        if (!built) {
            built = true;
            if (ROLE.equals("alpha")) alpha();
            else beta();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (index >= STEPS.size()) return;
        Step s = STEPS.get(index);
        if (!s.ready.getAsBoolean()) {
            if (++waited % 600 == 0) RYSTeams.LOG.info("[duo] жду шаг {}", s.name);
            if (waited > 4800) {
                RYSTeams.LOG.error("[duo] FAILED: не дождались шага {}", s.name);
                Minecraft.getInstance().stop();
            }
            return;
        }
        waited = 0;
        try {
            RYSTeams.LOG.info("[duo] шаг {}", s.name);
            s.action.run();
        } catch (Throwable t) {
            failures++;
            RYSTeams.LOG.error("[duo] шаг {} упал", s.name, t);
        }
        wait = s.pause;
        index++;
    }

    @SubscribeEvent
    public static void chat(ClientChatReceivedEvent e) {
        if (ROLE != null) CHAT.add(e.getMessage().getString());
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void step(String name, BooleanSupplier ready, Runnable action, int pause) {
        STEPS.add(new Step(name, ready, action, pause));
    }

    private static void step(String name, Runnable action, int pause) {
        step(name, () -> true, action, pause);
    }

    private static void shot(String name) {
        // night-1119: clear() убирал и карточку приглашения, а Y ядра отвечает только на видимую карточку
        step("clean", () -> { if (!name.contains("invite")) mc().getToasts().clear(); }, 2);
        step("shot " + name, () -> Screenshot.grab(mc().gameDirectory, "duo_" + ROLE + "_" + (shots++) + "_" + name + ".png", mc().getMainRenderTarget(), m -> {}), 4);
    }

    private static void check(String what, boolean ok) {
        if (!ok) failures++;
        RYSTeams.LOG.info("[duo] {} {}", ok ? "OK  " : "FAIL", what);
    }

    private static boolean heard(String part) {
        return CHAT.stream().anyMatch(s -> s.contains(part));
    }

    private static void connect() {
        step("title", () -> mc().screen != null && mc().level == null && mc().getOverlay() == null, () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            ThemeManager.select("ryscore:slate");
            ConnectScreen.startConnecting(new TitleScreen(), mc(), ServerAddress.parseString("localhost:25577"),
                new ServerData("duo", "localhost:25577", ServerData.Type.OTHER), false, null);
        }, 20);
        step("in world", () -> mc().player != null && mc().level != null && mc().screen == null, () -> {}, 40);
    }

    private static void finish() {
        RYSTeams.LOG.info(failures == 0 ? "[duo] PASS" : "[duo] FAILED: {}", failures);
    }

    private static void beta() {
        connect();
        step("invited", () -> !TeamClient.invites.isEmpty(), () -> check("приглашение дошло до второго игрока", TeamClient.invites.get(0).by().equals("Alpha")), 10);
        step("no chat cmd needed", () -> check("в чате есть ссылка-приглашение", heard("Alpha")), 2);
        shot("invite_toast");
        step("press Y", () -> {
            long window = mc().getWindow().getWindow();
            // настоящий путь клавиши: от окна до обработчика мода
            mc().keyboardHandler.keyPress(window, GLFW.GLFW_KEY_Y, 21, GLFW.GLFW_PRESS, 0);
            mc().keyboardHandler.keyPress(window, GLFW.GLFW_KEY_Y, 21, GLFW.GLFW_RELEASE, 0);
        }, 10);
        step("joined", () -> TeamClient.team != null, () -> {
            check("клавиша Y приняла приглашение", TeamClient.team.name.equals("Duo"));
            check("роль участника", TeamClient.team.members.get(mc().player.getUUID()).role == Role.MEMBER);
            check("участник не может приглашать", !TeamClient.can(Permission.INVITE));
        }, 20);
        step("forbidden action", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("perm", "invite");
            a.putString("role", "guest");
            ModNetwork.action("set_perm", a);
            ModNetwork.action("disband", new CompoundTag());
        }, 20);
        step("still team", () -> {
            check("сервер отверг чужие настройки", !TeamClient.team.can(Role.GUEST, Permission.INVITE));
            check("сервер отверг роспуск участником", TeamClient.team != null);
        }, 2);
        step("presence", () -> !TeamClient.PRESENCE.isEmpty(), () -> check("присутствие лидера дошло", TeamClient.PRESENCE.containsKey(TeamClient.team.leader().id)), 2);
        step("wait team chat", () -> heard("привет команде"), () -> check("чат команды дошёл", heard("[Duo] Alpha")), 2);
        step("wait ping", () -> !TeamClient.PINGS.isEmpty(), () -> check("пинг дошёл", TeamClient.PINGS.get(0).by().equals("Alpha")), 10);
        shot("hud_with_ping");
        step("open chat", () -> mc().setScreen(new ChatScreen("")), 6);
        step("tab", () -> {
            mc().keyboardHandler.keyPress(mc().getWindow().getWindow(), GLFW.GLFW_KEY_TAB, 15, GLFW.GLFW_PRESS, 0);
            mc().keyboardHandler.keyPress(mc().getWindow().getWindow(), GLFW.GLFW_KEY_TAB, 15, GLFW.GLFW_RELEASE, 0);
        }, 6);
        shot("chat_team_channel");
        step("send via channel", () -> {
            ((ChatScreen) mc().screen).handleChatInput("ответ по Tab", true);
            mc().setScreen(null);
        }, 20);
        step("note", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("text", "заметка второго игрока");
            ModNetwork.action("note_add", a);
        }, 20);
        step("done", () -> ModNetwork.action("leave", new CompoundTag()), 20);
        step("left", () -> check("уход из команды", TeamClient.team == null), 2);
        step("finish", () -> {
            finish();
            mc().player.connection.sendChat("beta-finished");
        }, 20);
        step("quit", () -> mc().stop(), 0);
    }

    private static void alpha() {
        connect();
        step("beta online", () -> TeamClient.online.stream().anyMatch(o -> o.name().equals("Beta") && o.free()), () -> {}, 5);
        step("create", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("name", "Duo");
            a.putInt("color", 0x3A7BD5);
            a.putString("icon", "minecraft:beacon");
            ModNetwork.action("create", a);
        }, 10);
        step("invite", () -> TeamClient.team != null, () -> TeamClient.action("invite", "player",
            TeamClient.online.stream().filter(o -> o.name().equals("Beta")).findFirst().orElseThrow().id()), 5);
        step("beta joined", () -> TeamClient.team != null && TeamClient.team.members.size() == 2, () -> check("второй игрок в команде у лидера", true), 60);
        step("presence", () -> !TeamClient.PRESENCE.isEmpty(), () -> check("присутствие второго игрока дошло", true), 2);
        step("screen", () -> mc().setScreen(new TeamScreen()), 15);
        shot("team_two_players");
        step("close", () -> mc().setScreen(null), 5);
        step("team chat", () -> mc().player.connection.sendChat("!привет команде"), 20);
        step("public hidden", () -> check("своё сообщение пришло в канал команды", heard("[Duo] Alpha")), 2);
        step("ping", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("kind", "danger");
            a.putInt("x", mc().player.getBlockX() + 6);
            a.putInt("y", mc().player.getBlockY());
            a.putInt("z", mc().player.getBlockZ() + 6);
            ModNetwork.action("ping", a);
        }, 10);
        step("tab reply", () -> heard("ответ по Tab"), () -> check("канал Tab отправил в чат команды", heard("[Duo] Beta")), 2);
        step("wait note", () -> !TeamClient.team.notes.isEmpty(), () -> check("заметка второго игрока синхронизирована", TeamClient.team.notes.get(0).by.equals("Beta")), 2);
        step("beta left", () -> TeamClient.team.members.size() == 1, () -> check("уход второго игрока виден лидеру", true), 2);
        step("wait beta end", () -> heard("beta-finished"), () -> {
            finish();
            mc().player.connection.sendCommand("stop");
        }, 40);
        step("quit", () -> mc().stop(), 0);
    }
}
