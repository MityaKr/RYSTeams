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
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Permission;

/** Клиентская часть: клавиши, слой поверх мира, сброс при выходе. */
public final class ClientEntry {
    private static final String CATEGORY = "key.categories.rysteams";
    /** В игре T открывает чат, поэтому экран команды на G. */
    public static final KeyMapping OPEN = new KeyMapping("key.rysteams.open", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
    /** Средняя кнопка занята выбором блока, поэтому пинг на V. */
    public static final KeyMapping PING = new KeyMapping("key.rysteams.ping", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final String[] PING_KINDS = {"here", "danger", "resource", "help"};

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_HUD;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SHOW_HUD = b.comment("Show team members over the world").define("showHud", true);
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
        });
        modBus.addListener((RegisterGuiLayersEvent e) ->
            e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "hud"), TeamHud::render));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> TeamClient.reset());
        NeoForge.EVENT_BUS.addListener(ClientEntry::onKey);
        NeoForge.EVENT_BUS.addListener(ClientEntry::onScroll);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (OPEN.consumeClick()) mc.setScreen(new TeamScreen());
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

    /** Y и N отвечают на приглашение, с зажатым Alt зовут игрока, на которого смотришь. */
    private static void onKey(InputEvent.Key e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.getAction() != GLFW.GLFW_PRESS || mc.screen != null || mc.player == null) return;
        if (e.getKey() != GLFW.GLFW_KEY_Y && e.getKey() != GLFW.GLFW_KEY_N) return;
        Player target = TeamHud.lookedAtPlayer();
        if (target != null && e.getKey() == GLFW.GLFW_KEY_Y && TeamClient.can(Permission.INVITE)) {
            TeamClient.action("invite", "player", target.getUUID());
            return;
        }
        if (TeamClient.invites.isEmpty()) return;
        CompoundTag t = new CompoundTag();
        t.putUUID("team", TeamClient.invites.get(0).id());
        t.putString("answer", e.getKey() == GLFW.GLFW_KEY_Y ? "accept" : "no");
        TeamClient.action("invite_reply", t);
    }
}
