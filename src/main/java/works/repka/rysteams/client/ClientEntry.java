package works.repka.rysteams.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.action.Answers;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Permission;

/** Клиентская часть: клавиши, слой поверх мира, сброс при выходе. */
public final class ClientEntry {
    private static final String CATEGORY = "key.categories.rysteams";
    /** В игре T открывает чат, поэтому экран команды на G. */
    public static final KeyMapping OPEN = new KeyMapping("key.rysteams.open", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
    /** Средняя кнопка занята выбором блока, поэтому пинг на V. */
    public static final KeyMapping PING = new KeyMapping("key.rysteams.ping", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    /*
     * «Принять» и «Отказать» живут в ядре (RYSCore 1.14, Answers.ACCEPT и DECLINE, умолчания Y и N): они отвечают на приглашение
     * кнопками уведомления, а когда отвечать не на что, «Принять» зовёт в команду игрока под прицелом (запасной обработчик).
     */
    public static final KeyMapping FRESH = new KeyMapping("key.rysteams.fresh", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY);
    public static final String[] PING_KINDS = {"here", "danger", "resource", "help", "mark"};

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_HUD;
    public static final ModConfigSpec.IntValue PING_SECONDS;
    /** Варианты срока жизни меток в настройках, по клику по кругу. */
    public static final int[] PING_CHOICES = {5, 10, 15, 30, 60};

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SHOW_HUD = b.comment("Show team members over the world").define("showHud", true);
        PING_SECONDS = b.comment("How long team markers stay on your screen, in seconds").defineInRange("pingSeconds", 5, 1, 600);
        CLIENT_SPEC = b.build();
    }

    public static int pingChoice;
    public static boolean pingHeld;
    private static int pingHeldTicks;

    private ClientEntry() {}

    public static void init(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.register(OPEN);
            e.register(PING);
            e.register(FRESH);
        });
        modBus.addListener((RegisterGuiLayersEvent e) ->
            e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "hud"), TeamHud::render));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            TeamClient.reset();
            ChatChannels.reset();
        });
        ChatChannels.init();
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RegisterClientCommandsEvent e) ->
            e.getDispatcher().register(net.minecraft.commands.Commands.literal("rysteams_open").executes(context -> {
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> mc.setScreen(new TeamScreen()));
                return 1;
            })));
        NeoForge.EVENT_BUS.addListener(ClientEntry::onScroll);
        Answers.onAccept(ClientEntry::inviteLooked);
        // слой «Команда», подписи в мире и пункты пинга на карте семьи (Т2); без RYSMap ядро их просто хранит
        TeamMap.register();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (OPEN.consumeClick()) mc.setScreen(new TeamScreen());
        while (FRESH.consumeClick()) answer("fresh");
        TeamNotices.tick();
        long now = System.currentTimeMillis();
        TeamClient.PINGS.removeIf(p -> p.until() < now);
        if (TeamClient.waypoint != null && TeamClient.waypoint.until() < now) TeamClient.waypoint = null;
        boolean down = PING.isDown() && mc.screen == null;
        if (down) {
            pingHeldTicks++;
            pingHeld = pingHeldTicks > 4;
        } else {
            if (pingHeldTicks > 0) sendPing();
            pingHeldTicks = 0;
            pingHeld = false;
        }
    }

    /** Отпускание клавиши: короткое нажатие даёт «Сюда», при удержании выбранную подпись. */
    private static void sendPing() {
        Minecraft mc = Minecraft.getInstance();
        if (TeamClient.team == null || !TeamClient.can(Permission.PING) || mc.player == null) return;
        String kind = pingHeld ? PING_KINDS[pingChoice] : PING_KINDS[0];
        if (kind.equals("mark")) {
            pingChoice = 0;
            mc.setScreen(TeamScreen.onMarks());
            return;
        }
        HitResult hit = mc.player.pick(256, 1f, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() == HitResult.Type.MISS) return;
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        t.putInt("x", block.getBlockPos().getX());
        t.putInt("y", block.getBlockPos().getY());
        t.putInt("z", block.getBlockPos().getZ());
        TeamClient.action("ping", t);
        pingChoice = 0;
    }

    private static void onScroll(InputEvent.MouseScrollingEvent e) {
        if (!pingHeld) return;
        pingChoice = Math.floorMod(pingChoice + (e.getScrollDeltaY() < 0 ? 1 : -1), PING_KINDS.length);
        e.setCanceled(true);
    }

    /**
     * «Принять» ядра, когда нет уведомления с ответом: зовёт в команду игрока под прицелом.
     * @return съедено ли нажатие
     */
    private static boolean inviteLooked() {
        if (Minecraft.getInstance().player == null) return false;
        Player target = TeamHud.lookedAtPlayer();
        if (target == null) return false;
        if (TeamClient.can(Permission.INVITE)) TeamClient.action("invite", "player", target.getUUID());
        return true;
    }

    /** Клавиши ответа срабатывают, только пока плашка приглашения на экране: иначе их нажимали бы случайно. */
    static void answer(String answer) {
        if (!TeamClient.inviteVisible()) return;
        CompoundTag t = new CompoundTag();
        t.putUUID("team", TeamClient.invites.get(0).id());
        t.putString("answer", answer);
        TeamClient.action("invite_reply", t);
        // плашка гаснет до ответа сервера; следующее приглашение она покажет, когда это пропадёт из состояния
        TeamClient.inviteShownUntil = 0;
        TeamClient.inviteAnswered = true;
    }
}
