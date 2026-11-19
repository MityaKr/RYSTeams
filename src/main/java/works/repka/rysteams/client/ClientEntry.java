package works.repka.rysteams.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import works.repka.ryscore.action.Action;
import works.repka.ryscore.action.Actions;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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
import works.repka.ryscore.action.Ring;
import works.repka.ryscore.ui.Icons;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Permission;

/** Клиентская часть: клавиши, слой поверх мира, сброс при выходе. */
public final class ClientEntry {
    private static final String CATEGORY = "key.categories.rysteams";
    /** В игре T открывает чат, поэтому экран команды на G. */
    public static final KeyMapping OPEN = new KeyMapping("key.rysteams.open", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
    /**
     * Пинг без клавиши по умолчанию (разбор логики 19.11, раздел 5): V делят десять модов сборки, NeoForge жмёт все действия на
     * клавише. Пинг есть в колесе ({@code rysteams:ping}) и на карте; кто назначит клавишу, получит прежнее «держать и листать».
     */
    public static final KeyMapping PING = new KeyMapping("key.rysteams.ping", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);
    /*
     * «Принять» и «Отказать» живут в ядре (RYSCore 1.14, Answers.ACCEPT и DECLINE, умолчания Y и N): они отвечают на приглашение
     * кнопками уведомления. Запасного «позвать игрока под прицелом» на Y больше нет (разбор логики 19.11): Y жмут ради других
     * модов, глядя на соседа; пригласить можно в окне команды.
     */
    /** «С нуля» без клавиши по умолчанию: ответ остаётся кнопкой на уведомлении приглашения. */
    public static final KeyMapping FRESH = new KeyMapping("key.rysteams.fresh", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);
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
        // слой «Команда», подписи в мире и пункты пинга на карте семьи (Т2); без RYSMap ядро их просто хранит
        TeamMap.register();
        registerActions();
    }

    /** Пинг в колесе семьи: сектор открывает список видов, как держать клавишу и листать (разбор логики 19.11, раздел 5). */
    private static void registerActions() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "ping");
        Action.builder(id).label(() -> Component.translatable("rysteams.action.ping")).icon(Icons.Shape.BELL).group(Action.Group.TEAM).order(0)
            .state(ClientEntry::pingState)
            .list(() -> {
                List<Action> kinds = new ArrayList<>();
                for (int i = 0; i < PING_KINDS.length; i++) {
                    String kind = PING_KINDS[i];
                    kinds.add(Action.builder(ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "ping_" + kind))
                        .label(() -> Component.translatable("rysteams.ping." + kind)).icon(kind.equals("danger") ? Icons.Shape.WARN : kind.equals("mark") ? Icons.Shape.FLAG : Icons.Shape.BELL)
                        .group(Action.Group.TEAM).order(i).state(ClientEntry::pingState).run(() -> sendPing(kind)).notBindable().build());
                }
                return kinds;
            })
            .register();
        Actions.suggest(Ring.MINE, id);
    }

    private static Action.State pingState() {
        if (TeamClient.team == null) return Action.State.off(Component.translatable("rysteams.action.no_team").getString());
        if (!TeamClient.can(Permission.PING)) return Action.State.off(Component.translatable("rysteams.action.no_right").getString());
        return Action.State.ready();
    }

    /** Имя назначенной клавиши или null, если клавиша не назначена (подсказки на экране её тогда не называют). */
    public static @org.jetbrains.annotations.Nullable String keyName(KeyMapping key) {
        return key.isUnbound() ? null : key.getTranslatedKeyMessage().getString();
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
        sendPing(pingHeld ? PING_KINDS[pingChoice] : PING_KINDS[0]);
    }

    /** Пинг туда, куда смотрит игрок: клавиша и колесо ({@code rysteams:ping}). «Метка…» открывает вкладку меток. */
    static void sendPing(String kind) {
        Minecraft mc = Minecraft.getInstance();
        if (TeamClient.team == null || !TeamClient.can(Permission.PING) || mc.player == null) return;
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
