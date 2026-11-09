package works.repka.rysteams.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.rysteams.data.Team;

/** Всё, что видно поверх мира: участники, метки и пинги, приглашение, карточка игрока, панель подписей. */
public final class TeamHud {
    private static final int HUD_W = 140;
    private static final int HUD_ROWS = 6;
    private static final int MARK_RANGE = 512;

    private TeamHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Theme theme = ThemeManager.get();
        if (mc.screen == null) {
            worldMarkers(g, theme, mc);
            if (TeamClient.team != null && ClientEntry.SHOW_HUD.get()) members(g, theme, mc, TeamClient.team);
            playerCard(g, theme, mc);
            if (ClientEntry.pingHeld) pingBar(g, theme, mc);
        }
        invite(g, theme, mc);
        toast(g, theme, mc);
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** Лицо игрока: у того, кого клиент не знает, обычный скин. */
    public static void face(GuiGraphics g, UUID id, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        ResourceLocation texture = info != null ? info.getSkin().texture() : DefaultPlayerSkin.get(id).texture();
        PlayerFaceRenderer.draw(g, texture, x, y, size);
    }

    public static int rgb(int color) {
        return 0xFF000000 | color;
    }

    public static String dimensionName(String id) {
        return switch (id) {
            case "minecraft:overworld" -> tr("rysteams.dim.overworld");
            case "minecraft:the_nether" -> tr("rysteams.dim.nether");
            case "minecraft:the_end" -> tr("rysteams.dim.end");
            default -> id.substring(id.indexOf(':') + 1).replace('_', ' ');
        };
    }

    // --- участники ---

    /** Карта в углу занимает правый верх, панель встаёт под неё. */
    private static int hudTop() {
        ModList mods = ModList.get();
        return mods.isLoaded("ftbchunks") || mods.isLoaded("journeymap") || mods.isLoaded("xaerominimap") ? 90 : 6;
    }

    private static void members(GuiGraphics g, Theme theme, Minecraft mc, Team team) {
        List<Team.Member> shown = new ArrayList<>();
        int online = 0;
        for (Team.Member m : team.members.values()) {
            if (m.id.equals(mc.player.getUUID())) continue;
            if (TeamClient.PRESENCE.containsKey(m.id)) {
                online++;
                shown.add(0, m);
            } else {
                shown.add(m);
            }
        }
        if (shown.isEmpty()) return;
        int visible = Math.min(HUD_ROWS, shown.size());
        int more = shown.size() - visible;
        int h = 16 + visible * 20 + (more > 0 ? 10 : 0) + 2;
        int x = g.guiWidth() - HUD_W - 6, y = hudTop();
        Draw.roundBox(g, x, y, HUD_W, h, theme.beveled() ? 0 : theme.r("panel.radius"), Draw.alpha(theme.c("hud.bg"), 0.92f), theme.c("hud.border"));
        int headerColor = theme.beveled() ? 0xFFFFFFFF : theme.c("text.primary");
        int muted = theme.beveled() ? 0xFFC0C0C0 : theme.c("text.muted");
        Draw.round(g, x + 5, y + 5, 7, 7, 2f, rgb(team.color));
        Draw.text(g, Draw.ellipsize(team.name, HUD_W - 62), x + 15, y + 5, headerColor);
        String count = tr("rysteams.hud.online", online + 1);
        Draw.textScaled(g, count, x + HUD_W - 6 - mc.font.width(count) * 0.75f, y + 6, 0.75f, muted);
        int rowY = y + 16;
        for (int i = 0; i < visible; i++) {
            Team.Member m = shown.get(i);
            row(g, theme, mc, team, m, x + 3, rowY, HUD_W - 6, headerColor, muted);
            rowY += 20;
        }
        if (more > 0) Draw.textScaled(g, "+" + more, x + 8, rowY + 1, 0.75f, muted);
    }

    private static void row(GuiGraphics g, Theme theme, Minecraft mc, Team team, Team.Member m, int x, int y, int w, int textColor, int muted) {
        TeamClient.Pres p = TeamClient.PRESENCE.get(m.id);
        Draw.round(g, x, y, w, 18, 3f, Draw.alpha(theme.c("slot.bg"), 0.7f));
        Draw.roundBorder(g, x + 3, y + 2, 14, 14, 2f, rgb(team.color), 1f);
        face(g, m.id, x + 4, y + 3, 12);
        Draw.dot(g, x + 13, y + 12, 5, theme.c(p != null ? "state.ok" : "text.dim"));
        String line;
        String right = "";
        if (p == null) {
            line = tr("rysteams.status.offline");
        } else {
            line = p.status().isEmpty() ? dimensionName(p.dimension()) : p.status();
            right = distance(mc, p);
        }
        float rightW = mc.font.width(right) * 0.75f;
        Draw.text(g, Draw.ellipsize(m.name, w - 26), x + 21, y + 2, p != null ? textColor : muted);
        Draw.textScaled(g, Draw.ellipsize(line, (int) ((w - 26 - rightW - 4) / 0.75f)), x + 21, y + 11, 0.75f, muted);
        if (!right.isEmpty()) Draw.textScaled(g, right, x + w - 3 - rightW, y + 11, 0.75f, muted);
    }

    /** Расстояние и стрелка: относительно того, куда смотрит игрок. */
    private static String distance(Minecraft mc, TeamClient.Pres p) {
        if (!p.hasPos() || !p.dimension().equals(mc.level.dimension().location().toString())) return "";
        double dx = p.x() + 0.5 - mc.player.getX(), dz = p.z() + 0.5 - mc.player.getZ();
        int metres = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        double angle = Math.toDegrees(Math.atan2(dz, dx)) - 90 - mc.player.getYRot();
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        return tr("rysteams.unit.m", metres) + " " + arrows[Math.floorMod(Math.round((float) (angle / 45)), 8)];
    }

    // --- метки и пинги в мире ---

    private static void worldMarkers(GuiGraphics g, Theme theme, Minecraft mc) {
        String dim = mc.level.dimension().location().toString();
        long now = System.currentTimeMillis();
        Team team = TeamClient.team;
        if (team != null) {
            for (Team.Mark m : team.marks) {
                if (!m.dimension().equals(dim) || dist(mc, m.x(), m.z()) > MARK_RANGE) continue;
                int[] s = project(mc, g, m.x() + 0.5, m.y() + 1.5, m.z() + 0.5);
                if (s == null) continue;
                label(g, theme, mc, s, m.name() + " · " + tr("rysteams.unit.m", dist(mc, m.x(), m.z())), "", theme.c("card.border"), theme.c("text.muted"), TeamClient.icon(m.icon()));
            }
        }
        for (TeamClient.Ping p : TeamClient.PINGS) {
            if (!p.dimension().equals(dim)) continue;
            int[] s = project(mc, g, p.x() + 0.5, p.y() + 1.2, p.z() + 0.5);
            if (s == null) continue;
            String title = p.by() + ": " + tr("rysteams.ping." + p.kind());
            String sub = tr("rysteams.unit.m", dist(mc, p.x(), p.z())) + " · " + tr("rysteams.unit.s", (p.until() - now) / 1000);
            Draw.roundBorder(g, s[0] - 14, s[1] + 4, 28, 8, 4f, Draw.alpha(rgb(p.color()), 0.6f), 1f);
            label(g, theme, mc, s, title, sub, rgb(p.color()), rgb(p.color()), null);
        }
        TeamClient.Waypoint w = TeamClient.waypoint;
        if (w != null && w.dimension().equals(dim)) {
            int[] s = project(mc, g, w.x() + 0.5, w.y() + 1.2, w.z() + 0.5);
            if (s != null) label(g, theme, mc, s, w.label() + " · " + tr("rysteams.unit.m", dist(mc, w.x(), w.z())), "", rgb(w.color()), rgb(w.color()), null);
        }
    }

    private static int dist(Minecraft mc, int x, int z) {
        double dx = x + 0.5 - mc.player.getX(), dz = z + 0.5 - mc.player.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    private static void label(GuiGraphics g, Theme theme, Minecraft mc, int[] s, String title, String sub,
                              int border, int arrow, @Nullable net.minecraft.world.item.ItemStack icon) {
        Font font = mc.font;
        int iconW = icon != null && !icon.isEmpty() ? 11 : 0;
        int w = Math.max(font.width(title), (int) (font.width(sub) * 0.75f)) + 10 + iconW;
        int h = sub.isEmpty() ? 13 : 21;
        int x = s[0] - w / 2, y = s[1] - h - 5;
        Draw.roundBox(g, x, y, w, h, 3f, Draw.alpha(theme.c("hud.bg"), 0.92f), border);
        int text = theme.beveled() ? 0xFFFFFFFF : theme.c("text.primary");
        if (iconW > 0) {
            g.pose().pushPose();
            g.pose().translate(x + 3, y + 2, 0);
            g.pose().scale(0.56f, 0.56f, 1f);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        Draw.text(g, title, x + 5 + iconW, y + 3, text);
        if (!sub.isEmpty()) Draw.textScaled(g, sub, x + 5, y + 12, 0.75f, theme.beveled() ? 0xFFC0C0C0 : theme.c("text.muted"));
        for (int i = 0; i < 4; i++) Draw.rect(g, s[0] - 3 + i, y + h + i, 7 - i * 2, 1, arrow);
    }

    /** Точка мира в координатах GUI; за спиной null, за краем прижимается к краю. */
    @Nullable
    private static int[] project(Minecraft mc, GuiGraphics g, double x, double y, double z) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        Vector3f view = new Vector3f((float) (x - eye.x), (float) (y - eye.y), (float) (z - eye.z));
        new Quaternionf(camera.rotation()).conjugate().transform(view);
        if (view.z >= -0.1f) return null;
        float halfH = g.guiHeight() / 2f;
        float focal = halfH / (float) Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
        int sx = Math.round(g.guiWidth() / 2f + view.x / -view.z * focal);
        int sy = Math.round(halfH - view.y / -view.z * focal);
        return new int[] {Math.max(40, Math.min(g.guiWidth() - 40, sx)), Math.max(30, Math.min(g.guiHeight() - 20, sy))};
    }

    // --- карточка игрока под прицелом (Alt) ---

    private static boolean altDown(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    /** Игрок, на которого смотрят с зажатым Alt, или null. */
    @Nullable
    public static Player lookedAtPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !altDown(mc)) return null;
        return mc.crosshairPickEntity instanceof Player p && p != mc.player ? p : null;
    }

    private static void playerCard(GuiGraphics g, Theme theme, Minecraft mc) {
        Player target = lookedAtPlayer();
        if (target == null) return;
        boolean free = false;
        for (TeamClient.Online o : TeamClient.online) if (o.id().equals(target.getUUID())) free = o.free();
        String title = target.getGameProfile().getName() + (free ? " · " + tr("rysteams.card.free") : "");
        String sub = tr("rysteams.card.hint", (int) mc.player.distanceTo(target));
        String hint = free && TeamClient.can(works.repka.rysteams.api.Permission.INVITE) ? tr("rysteams.card.invite") : "";
        int w = Math.max(mc.font.width(title), mc.font.width(sub)) + 14 + (hint.isEmpty() ? 0 : mc.font.width(hint) + 18);
        int x = (g.guiWidth() - w) / 2, y = g.guiHeight() / 2 + 14;
        Draw.roundBox(g, x, y, w, 25, theme.r("panel.radius"), Draw.alpha(theme.c("hud.bg"), 0.94f), theme.c("hud.border"));
        Draw.text(g, title, x + 7, y + 4, theme.beveled() ? 0xFFFFFFFF : theme.c("text.primary"));
        Draw.textScaled(g, sub, x + 7, y + 14, 0.75f, theme.beveled() ? 0xFFC0C0C0 : theme.c("text.muted"));
        if (!hint.isEmpty()) {
            int bw = mc.font.width(hint) + 10, bx = x + w - bw - 6;
            theme.button(g, bx, y + 5, bw, 15, false, true);
            Draw.text(g, hint, bx + 5, y + 9, theme.c("accent.text"));
        }
    }

    // --- панель подписей пинга ---

    private static void pingBar(GuiGraphics g, Theme theme, Minecraft mc) {
        Font font = mc.font;
        String caption = tr("rysteams.ping.bar");
        int w = font.width(caption) + 18;
        String[] kinds = ClientEntry.PING_KINDS;
        int[] widths = new int[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            widths[i] = font.width(tr("rysteams.ping." + kinds[i])) + 12;
            w += widths[i] + 3;
        }
        int x = (g.guiWidth() - w) / 2, y = g.guiHeight() - 62;
        Draw.roundBox(g, x, y, w, 20, 10f, Draw.alpha(theme.c("hud.bg"), 0.94f), theme.c("hud.border"));
        Draw.text(g, caption, x + 8, y + 6, theme.beveled() ? 0xFFC0C0C0 : theme.c("text.muted"));
        int bx = x + 12 + font.width(caption);
        for (int i = 0; i < kinds.length; i++) {
            boolean active = i == ClientEntry.pingChoice;
            theme.button(g, bx, y + 3, widths[i], 14, false, active);
            Draw.text(g, tr("rysteams.ping." + kinds[i]), bx + 6, y + 6, active ? theme.c("accent.text") : theme.c("text.body"));
            bx += widths[i] + 3;
        }
    }

    // --- приглашение и уведомления ---

    private static void invite(GuiGraphics g, Theme theme, Minecraft mc) {
        if (TeamClient.invites.isEmpty() || mc.screen instanceof TeamScreen) return;
        TeamClient.Info invite = TeamClient.invites.get(0);
        Font font = mc.font;
        int w = 170, x = g.guiWidth() - w - 6, y = 6 + (mc.screen == null ? hudTop() - 6 : 0);
        int textW = w - 16;
        List<FormattedCharSequence> body = font.split(Component.translatable("rysteams.invite.progress"), (int) (textW / 0.75f));
        int h = 24 + body.size() * 8 + 26;
        g.pose().pushPose();
        g.pose().translate(0, 0, 350);
        theme.panel(g, x, y, w, h);
        Draw.round(g, x + 7, y + 6, 14, 14, 3f, rgb(invite.color()));
        g.pose().pushPose();
        g.pose().translate(x + 9, y + 8, 0);
        g.pose().scale(0.625f, 0.625f, 1f);
        g.renderItem(TeamClient.icon(invite.icon()), 0, 0);
        g.pose().popPose();
        Draw.text(g, Draw.ellipsize(tr("rysteams.invite.title", invite.by(), invite.name()), w - 34), x + 25, y + 5, theme.c("text.primary"));
        long left = Math.max(0, (invite.until() - System.currentTimeMillis()) / 1000);
        Draw.textScaled(g, tr("rysteams.invite.sub", invite.count(), left / 60, String.format("%02d", left % 60)), x + 25, y + 15, 0.75f, theme.c("text.muted"));
        int ly = y + 25;
        for (FormattedCharSequence line : body) {
            g.pose().pushPose();
            g.pose().translate(x + 8, ly, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(font, line, 0, 0, theme.c("text.body"), false);
            g.pose().popPose();
            ly += 8;
        }
        int by = y + h - 21;
        String accept = tr("rysteams.invite.accept"), no = tr("rysteams.invite.no");
        int aw = font.width(accept) + 14, nw = font.width(no) + 14;
        theme.button(g, x + 8, by, aw, 15, false, true);
        Draw.textCentered(g, accept, x + 8 + aw / 2, by + 4, theme.c("accent.text"));
        theme.button(g, x + 8 + aw + 4, by, nw, 15, false, false);
        Draw.textCentered(g, no, x + 8 + aw + 4 + nw / 2, by + 4, theme.c("text.body"));
        g.pose().popPose();
    }

    public static void toast(GuiGraphics g, Theme theme, Minecraft mc) {
        TeamClient.Toast t = TeamClient.toast;
        if (t == null) return;
        if (t.until() < System.currentTimeMillis()) {
            TeamClient.toast = null;
            return;
        }
        int w = mc.font.width(t.text()) + 16;
        int x = (g.guiWidth() - w) / 2, y = g.guiHeight() - 76;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        theme.tooltipBox(g, x, y, w, 16);
        Draw.text(g, t.text(), x + 8, y + 4, t.bad() ? theme.c("state.bad") : theme.c("text.primary"));
        g.pose().popPose();
    }
}
