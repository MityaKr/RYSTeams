package works.repka.rysteams.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Anchors;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.UiScale;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysteams.data.Team;

/** Всё, что видно поверх мира: участники, метки и пинги, приглашение, карточка игрока, панель подписей. */
public final class TeamHud {
    private static final int HUD_W = 140;
    private static final int HUD_ROWS = 6;
    private static final int MARK_RANGE = 512;

    private TeamHud() {}

    public static void render(GuiGraphics parent, DeltaTracker delta) {
        if (!anything(Minecraft.getInstance())) return;
        UiScale.render(parent, g -> renderUi(g, delta));
    }

    /** Те же условия, что в {@link #renderUi}: когда рисовать нечего, слой не создаёт графику и не сбрасывает буфер. */
    private static boolean anything(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.options.hideGui) return false;
        if (mc.screen != null) return false;
        return TeamClient.team != null || !TeamClient.PINGS.isEmpty() || TeamClient.waypoint != null || ClientEntry.pingHeld || lookedAtPlayer() != null;
    }

    private static void renderUi(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Theme theme = ThemeManager.get();
        if (mc.screen == null) {
            camera(mc, g);
            worldMarkers(g, theme, mc);
            if (TeamClient.team != null && ClientEntry.SHOW_HUD.get()) members(g, theme, mc, TeamClient.team);
            playerCard(g, theme, mc);
            if (ClientEntry.pingHeld) pingBar(g, theme, mc);
        }
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static String key(net.minecraft.client.KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
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

    private static void members(GuiGraphics g, Theme t, Minecraft mc, Team team) {
        List<Team.Member> shown = new ArrayList<>();
        int online = 1;
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
        int h = 6 + 9 + visible * 23 + 3 + 8 + 6;
        // правый верх обычно занят картой, поэтому панель стоит в правом нижнем углу
        int[] at = Anchors.place(Anchors.Element.TEAM_CARD, HUD_W, h, 10, 10);
        int x = at[0], y = at[1];
        Draw.roundBox(g, x, y, HUD_W, h, t.beveled() ? 0 : 6f, Draw.alpha(t.c("hud.bg"), 0.92f), t.c("hud.border"));
        int text = t.beveled() ? 0xFFFFFFFF : t.c("text.primary");
        int muted = t.beveled() ? 0xFFC0C0C0 : t.c("text.muted");
        int dim = t.beveled() ? 0xFFA0A0A0 : t.c("text.dim");
        Draw.round(g, x + 6, y + 6, 9, 9, 2.5f, rgb(team.color));
        ItemStack icon = TeamClient.icon(team.icon);
        if (!icon.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + 7.5f, y + 7.5f, 0);
            g.pose().scale(0.375f, 0.375f, 1f);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        float cy = y + 10.5f;
        String name = Draw.ellipsize(team.name, HUD_W - 70, 12.5f, Weight.SEMIBOLD);
        Draw.label(g, name, x + 19, cy, 12.5f, Weight.SEMIBOLD, text);
        float nameW = Draw.labelWidth(name, 12.5f, Weight.SEMIBOLD);
        Draw.label(g, " · " + tr("rysteams.count.online", online), x + 19 + nameW, cy, 11, Weight.REGULAR, muted);
        // окно команды без клавиши по умолчанию (панель семьи и колесо): клавишу пишем, только если её назначили
        String key = ClientEntry.keyName(ClientEntry.OPEN);
        if (key != null) Draw.label(g, key, x + HUD_W - 6 - Draw.labelWidth(key, 11, Weight.REGULAR), cy, 11, Weight.REGULAR, dim);
        int rowY = y + 6 + 9 + 3;
        for (int i = 0; i < visible; i++) {
            row(g, t, mc, team, shown.get(i), x + 6, rowY, HUD_W - 12, text, muted, dim);
            rowY += 23;
        }
        String left = more > 0 ? "+" + more : key != null ? tr("rysteams.hud.open", key) : "";
        // пинг без клавиши по умолчанию (колесо и карта): подсказку пишем, только если клавишу назначили
        String pingKey = ClientEntry.keyName(ClientEntry.PING);
        float hy = rowY + 3.5f;
        Draw.label(g, left, x + 6, hy, 10.5f, Weight.REGULAR, dim);
        if (pingKey != null) {
            String right = tr("rysteams.hud.ping", pingKey);
            Draw.label(g, right, x + HUD_W - 6 - Draw.labelWidth(right, 10.5f, Weight.REGULAR), hy, 10.5f, Weight.REGULAR, dim);
        }
    }

    private static void row(GuiGraphics g, Theme t, Minecraft mc, Team team, Team.Member m, int x, int y, int w, int text, int muted, int dim) {
        TeamClient.Pres p = TeamClient.PRESENCE.get(m.id);
        float alpha = p != null ? 1f : 0.55f;
        Draw.round(g, x, y, w, 20, 4f, Draw.alpha(t.c("status.bg"), t.beveled() ? 0.8f : 1f));
        Draw.roundBorder(g, x + 4, y + 3.5f, 13, 13, 3f, Draw.alpha(rgb(team.color), alpha), 1f);
        face(g, m.id, x + 5, y + 4, 11);
        if (p == null) Draw.rect(g, x + 5, y + 4, 11, 11, Draw.alpha(t.c("status.bg"), 0.45f));
        Draw.dot(g, x + 13, y + 13, 6, t.c("status.bg"));
        Draw.dot(g, x + 14, y + 14, 4, t.c(p != null ? "state.ok" : "text.dim"));
        String line, metres = "";
        float arrow = Float.NaN;
        if (p == null) {
            line = tr("rysteams.status.offline");
        } else {
            line = p.status().isEmpty() ? dimensionName(p.dimension()) : p.status();
            if (nearby(mc, p)) {
                double dx = p.x() + 0.5 - mc.player.getX(), dz = p.z() + 0.5 - mc.player.getZ();
                metres = tr("rysteams.unit.m", (int) Math.round(Math.sqrt(dx * dx + dz * dz)));
                // значок стрелки смотрит вправо, поэтому поворот отсчитывается от правого направления
                arrow = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 180 - mc.player.getYRot());
            }
        }
        float rightW = metres.isEmpty() ? 0 : Draw.labelWidth(metres, 11.5f, Weight.REGULAR);
        Draw.label(g, Draw.ellipsize(m.name, w - 22 - rightW - 5, 12, Weight.REGULAR), x + 21, y + 6.5f, 12, Weight.REGULAR, Draw.alpha(text, alpha));
        Draw.label(g, Draw.ellipsize(line, w - 22 - rightW - 5, 10.5f, Weight.REGULAR), x + 21, y + 14f, 10.5f, Weight.REGULAR, muted);
        if (!metres.isEmpty()) Draw.label(g, metres, x + w - 4 - Draw.labelWidth(metres, 11.5f, Weight.REGULAR), y + 6.5f, 11.5f, Weight.REGULAR, t.c("button.fg"));
        if (!Float.isNaN(arrow)) Icons.drawRotated(g, Icons.Shape.ARROW, x + w - 4 - 7f, y + 10.5f, 7f, arrow, dim);
    }

    /** Расстояние и стрелка есть, только если координаты открыты и игрок в том же измерении. */
    private static boolean nearby(Minecraft mc, TeamClient.Pres p) {
        return p.hasPos() && p.dimension().equals(mc.level.dimension().location().toString());
    }

    // --- метки и пинги в мире ---

    private static void worldMarkers(GuiGraphics g, Theme t, Minecraft mc) {
        // карта рисует подписи в мире сама (Т2): метки, пинги и цель приходят к ней из TeamMap, вторые подписи не нужны
        if (works.repka.ryscore.world.Labels.present()) return;
        String dim = mc.level.dimension().location().toString();
        long now = System.currentTimeMillis();
        Team team = TeamClient.team;
        if (team != null) {
            for (Team.Mark m : team.marks) {
                if (!m.dimension().equals(dim) || dist(mc, m.x(), m.z()) > MARK_RANGE) continue;
                int[] s = project(mc, g, m.x() + 0.5, m.y() + 1.5, m.z() + 0.5);
                if (s != null) markLabel(g, t, mc, s, m);
            }
        }
        for (TeamClient.Ping p : TeamClient.PINGS) {
            if (!p.dimension().equals(dim)) continue;
            int[] s = project(mc, g, p.x() + 0.5, p.y() + 1.2, p.z() + 0.5);
            if (s != null) pingLabel(g, t, mc, s, p, now);
        }
        TeamClient.Waypoint w = TeamClient.waypoint;
        if (w != null && w.dimension().equals(dim)) {
            int[] s = project(mc, g, w.x() + 0.5, w.y() + 1.2, w.z() + 0.5);
            if (s != null) {
                String title = w.label() + " · " + tr("rysteams.unit.m", dist(mc, w.x(), w.z()));
                int width = (int) Math.ceil(Draw.labelWidth(title, 11.5f, Weight.REGULAR)) + 9;
                Draw.roundBox(g, s[0] - width / 2f, s[1] - 18, width, 12, 4f, Draw.alpha(t.c("hud.bg"), 0.9f), rgb(w.color()));
                Draw.label(g, title, s[0] - width / 2f + 4.5f, s[1] - 12, 11.5f, Weight.REGULAR, t.c("text.primary"));
                triangle(g, s[0], s[1] - 5, 7, 5, rgb(w.color()));
            }
        }
    }

    /**
     * Треугольник вершиной вниз: строки простыми заливками, они уходят одной пачкой, без отдельной отрисовки на строку.
     * Ширина строки той же чётности, что основание, чтобы он был симметричным.
     */
    private static void triangle(GuiGraphics g, int cx, int y, int w, int h, int color) {
        for (int i = 0; i < h; i++) {
            int rowW = Math.max(1, w - 2 * Math.round(i * w / (2f * h)));
            Draw.rect(g, cx - rowW / 2, y + i, rowW, 1, color);
        }
    }

    private static void pingLabel(GuiGraphics g, Theme t, Minecraft mc, int[] s, TeamClient.Ping p, long now) {
        String title = p.by() + ": " + tr("rysteams.ping." + p.kind()).toLowerCase(java.util.Locale.ROOT);
        String sub = tr("rysteams.unit.m", dist(mc, p.x(), p.z())) + " · " + tr("rysteams.unit.s", (p.until() - now) / 1000);
        int text = (int) Math.ceil(Math.max(Draw.labelWidth(title, 12, Weight.REGULAR), Draw.labelWidth(sub, 10.5f, Weight.REGULAR)));
        int width = 5 + 9 + 4 + text + 5, height = 19;
        int color = rgb(p.color());
        int ringY = s[1] + 3;
        Draw.roundBorder(g, s[0] - 10, ringY, 20, 7, 3.5f, Draw.alpha(color, 0.6f), 1f);
        triangle(g, s[0], ringY - 7, 8, 6, color);
        float x = s[0] - width / 2f, y = ringY - 8 - height;
        Draw.roundBox(g, x, y, width, height, 4f, Draw.alpha(t.c("hud.bg"), 0.94f), color);
        Draw.dot(g, (int) x + 5, (int) y + 5, 9, color);
        String initial = p.by().isEmpty() ? "?" : p.by().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        Draw.label(g, initial, x + 5 + 4.5f - Draw.labelWidth(initial, 10, Weight.SEMIBOLD) / 2f, y + 9.5f, 10, Weight.SEMIBOLD, 0xFFFFFFFF);
        Draw.label(g, title, x + 18, y + 6.5f, 12, Weight.REGULAR, t.c("text.primary"));
        Draw.label(g, sub, x + 18, y + 14f, 10.5f, Weight.REGULAR, t.c("text.muted"));
    }

    /** Подпись метки: у метки гибели в поле имени лежит ник погибшего, подпись собирается на клиенте. */
    public static String markTitle(Team.Mark m) {
        return m.death() ? tr("rysteams.mark.death", m.name()) : m.name();
    }

    private static void markLabel(GuiGraphics g, Theme t, Minecraft mc, int[] s, Team.Mark m) {
        boolean pulse = "pulse".equals(m.source());
        // метка гибели: череп и рамка цвета тревоги
        boolean death = m.death();
        String title = markTitle(m) + " · " + tr("rysteams.unit.m", dist(mc, m.x(), m.z()));
        float textW = Draw.labelWidth(title, 11.5f, Weight.REGULAR);
        String chip = pulse ? tr("rysteams.source.pulse") : "";
        float lead = pulse ? Draw.labelWidth(chip, 10, Weight.SEMIBOLD) + 5 + 4 : 7 + 4;
        int width = (int) Math.ceil(4.5f + lead + textW + 4.5f), height = 12;
        float x = s[0] - width / 2f, y = s[1] - 6 - height;
        int alpha = 230;
        int border = death ? t.c("state.bad") : pulse ? t.c("src.pulse.border") : t.c("hud.border");
        Draw.roundBox(g, x, y, width, height, 4f, Draw.alpha(t.c("hud.bg"), 0.9f), border);
        if (pulse) {
            float cw = Draw.labelWidth(chip, 10, Weight.SEMIBOLD) + 5;
            Draw.round(g, x + 4.5f, y + 2.75f, cw, 6.5f, 3.25f, t.c("src.pulse.bg"));
            Draw.label(g, chip, x + 7, y + 6, 10, Weight.SEMIBOLD, t.c("state.bad"));
        } else {
            ItemStack icon = TeamClient.icon(m.icon());
            Draw.round(g, x + 4.5f, y + 2.5f, 7, 7, 1.5f, Draw.alpha(t.c("slot.bg"), 0.9f));
            if (!icon.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(x + 4.5f, y + 2.5f, 0);
                g.pose().scale(0.4375f, 0.4375f, 1f);
                g.renderItem(icon, 0, 0);
                g.pose().popPose();
            }
        }
        Draw.label(g, title, x + 4.5f + lead, y + 6, 11.5f, Weight.REGULAR, Draw.alpha(t.c("text.primary"), alpha / 255f));
        triangle(g, s[0], (int) (y + height + 1), 7, 5, pulse || death ? t.c("state.bad") : t.c("text.muted"));
    }

    private static int dist(Minecraft mc, int x, int z) {
        double dx = x + 0.5 - mc.player.getX(), dz = z + 0.5 - mc.player.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    /** Камера кадра: поворот и фокус считаются один раз, а не на каждую метку. */
    private static final Quaternionf CAMERA = new Quaternionf();
    private static final Vector3f VIEW = new Vector3f();
    private static Vec3 eye = Vec3.ZERO;
    private static float focal;

    private static void camera(Minecraft mc, GuiGraphics g) {
        Camera camera = mc.gameRenderer.getMainCamera();
        eye = camera.getPosition();
        CAMERA.set(camera.rotation()).conjugate();
        focal = g.guiHeight() / 2f / (float) Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
    }

    /** Точка мира в координатах GUI; за спиной null, за краем прижимается к краю. */
    @Nullable
    private static int[] project(Minecraft mc, GuiGraphics g, double x, double y, double z) {
        Vector3f view = CAMERA.transform(VIEW.set((float) (x - eye.x), (float) (y - eye.y), (float) (z - eye.z)));
        if (view.z >= -0.1f) return null;
        float halfH = g.guiHeight() / 2f;
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

    private static void playerCard(GuiGraphics g, Theme t, Minecraft mc) {
        Player target = lookedAtPlayer();
        if (target == null) return;
        boolean free = false;
        for (TeamClient.Online o : TeamClient.online) if (o.id().equals(target.getUUID())) free = o.free();
        String title = target.getGameProfile().getName() + (free ? " · " + tr("rysteams.card.free") : "");
        String sub = tr("rysteams.card.hint", (int) mc.player.distanceTo(target));
        // Y больше не зовёт под прицелом (разбор логики 19.11): плашки «Пригласить · Y» нет, пригласить можно в окне команды
        // пинг без клавиши по умолчанию: плашка только у того, кто клавишу назначил
        String pingKey = ClientEntry.keyName(ClientEntry.PING);
        String pingLabel = pingKey == null ? "" : tr("rysteams.card.ping", pingKey);
        int textW = (int) Math.ceil(Math.max(Draw.labelWidth(title, 12.5f, Weight.SEMIBOLD), Draw.labelWidth(sub, 11, Weight.REGULAR)));
        int pw = pingKey == null ? 0 : (int) Math.ceil(Draw.labelWidth(pingLabel, 12, Weight.REGULAR)) + 10;
        int w = 6 + textW + 5 + pw + (pw > 0 ? 6 : 1), h = 23;
        int[] anchor = project(mc, g, target.getX(), target.getY() + target.getBbHeight() + 0.55, target.getZ());
        int x = anchor == null ? (g.guiWidth() - w) / 2 : anchor[0] - w / 2;
        int y = anchor == null ? g.guiHeight() / 2 + 14 : Math.max(10, anchor[1] - h);
        x = Math.max(4, Math.min(g.guiWidth() - w - 4, x));
        Draw.roundBox(g, x, y, w, h, 6f, Draw.alpha(t.c("hud.bg"), 0.94f), t.c("hud.border"));
        Draw.label(g, title, x + 6, y + 7.5f, 12.5f, Weight.SEMIBOLD, t.beveled() ? 0xFFFFFFFF : t.c("text.primary"));
        Draw.label(g, sub, x + 6, y + 15.5f, 11, Weight.REGULAR, t.beveled() ? 0xFFC0C0C0 : t.c("text.muted"));
        int bx = x + 6 + textW + 5;
        if (pw > 0) {
            Draw.roundBox(g, bx, y + 4, pw, 15, 4f, t.c("button.bg"), t.c("button.border"));
            Draw.label(g, pingLabel, bx + 5, y + 11.5f, 12, Weight.REGULAR, t.c("text.primary"));
        }
    }

    // --- панель подписей пинга ---

    private static void pingBar(GuiGraphics g, Theme t, Minecraft mc) {
        String caption = tr("rysteams.ping.bar"), wheel = tr("rysteams.ping.wheel");
        String[] kinds = ClientEntry.PING_KINDS;
        float w = 5 + 3 + Draw.labelWidth(caption, 11, Weight.REGULAR) + 3 + 3;
        float[] widths = new float[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            widths[i] = (float) Math.ceil(Draw.labelWidth(pingName(kinds[i]), 12, i == ClientEntry.pingChoice ? Weight.SEMIBOLD : Weight.REGULAR)) + 12;
            w += widths[i] + 3;
        }
        w += 3 + Draw.labelWidth(wheel, 10.5f, Weight.REGULAR) + 3 + 5;
        int width = (int) Math.ceil(w), height = 21;
        int x = (g.guiWidth() - width) / 2, y = g.guiHeight() - 50 - height;
        Draw.roundBox(g, x, y, width, height, 10.5f, Draw.alpha(t.c("hud.bg"), 0.94f), t.c("hud.border"));
        float cy = y + height / 2f;
        Draw.label(g, caption, x + 8, cy, 11, Weight.REGULAR, t.beveled() ? 0xFFC0C0C0 : t.c("text.muted"));
        float bx = x + 5 + 3 + Draw.labelWidth(caption, 11, Weight.REGULAR) + 6;
        for (int i = 0; i < kinds.length; i++) {
            boolean active = i == ClientEntry.pingChoice;
            if (active) Draw.round(g, bx, y + 4, widths[i], 13, 6.5f, t.c("accent"));
            else Draw.roundBox(g, bx, y + 4, widths[i], 13, 6.5f, t.c("button.bg"), t.c("button.border"));
            Draw.label(g, pingName(kinds[i]), bx + 6, cy, 12, active ? Weight.SEMIBOLD : Weight.REGULAR, active ? t.c("accent.text") : t.c("text.primary"));
            bx += widths[i] + 3;
        }
        Draw.label(g, wheel, bx + 3, cy, 10.5f, Weight.REGULAR, t.beveled() ? 0xFFA0A0A0 : t.c("text.dim"));
    }

    private static String pingName(String kind) {
        String name = tr("rysteams.ping." + kind);
        return name.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + name.substring(1);
    }

    // приглашение и строки-уведомления — общие уведомления семьи в колонке тостов игры (TeamNotices)
}
