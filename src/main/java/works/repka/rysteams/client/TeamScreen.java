package works.repka.rysteams.client;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import works.repka.ryscore.team.TeamCards;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.rysteams.api.Permission;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.data.Team;

/**
 * Экран команды. Каждый кадр рисует всё заново и записывает, где какая кнопка:
 * раскладка живёт в одном месте, а клик просто ищет запись под курсором.
 */
public final class TeamScreen extends Screen {
    private enum Tab { TEAM, BOARD, MARKS, ALLIES, SETTINGS }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean over(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final int HEADER_H = 30;
    private static final int PAD = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static Tab tab = Tab.TEAM;
    private final List<Hit> hits = new ArrayList<>();
    private int px, py, pw, ph;
    private int mx, my;
    private int feedScroll, bodyScroll, bodyContent, feedContent;
    private int clipTop, clipBottom;
    private String filter = "";
    private UUID selected;
    private String armed = "";
    private long armedUntil;
    private int newColor = Team.COLORS[0];
    private String newIcon = "minecraft:white_banner";
    private EditBox nameBox, noteBox, todoBox, markBox;
    private final List<EditBox> boxes = new ArrayList<>();
    private List<Component> tooltip;

    public TeamScreen() {
        super(Component.translatable("rysteams.title"));
    }

    private EditBox box(int max, String hint) {
        EditBox b = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 10, Component.empty());
        b.setBordered(false);
        b.setMaxLength(max);
        b.setHint(Component.translatable(hint));
        addWidget(b);
        boxes.add(b);
        return b;
    }

    @Override
    protected void init() {
        boxes.clear();
        String name = nameBox == null ? "" : nameBox.getValue();
        String note = noteBox == null ? "" : noteBox.getValue();
        String todo = todoBox == null ? "" : todoBox.getValue();
        String mark = markBox == null ? "" : markBox.getValue();
        nameBox = box(24, "rysteams.hint.name");
        noteBox = box(400, "rysteams.hint.note");
        todoBox = box(160, "rysteams.hint.todo");
        markBox = box(48, "rysteams.hint.mark");
        nameBox.setValue(name);
        noteBox.setValue(note);
        todoBox.setValue(todo);
        markBox.setValue(mark);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private Font font() {
        return Minecraft.getInstance().font;
    }

    private UUID me() {
        return Minecraft.getInstance().player.getUUID();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Theme t = ThemeManager.get();
        mx = mouseX;
        my = mouseY;
        hits.clear();
        tooltip = null;
        for (EditBox b : boxes) b.setVisible(false);
        renderBackground(g, mouseX, mouseY, partialTick);
        Team team = TeamClient.team;
        if (team == null) {
            lobby(g, t);
        } else {
            pw = Math.min(width - 8, 640);
            ph = Math.min(height - 8, 400);
            px = (width - pw) / 2;
            py = Math.max(4, Math.min(height / 18, height - ph - 4));
            t.panel(g, px, py, pw, ph);
            header(g, t, team);
            int bodyY = py + HEADER_H + 4, bodyH = ph - HEADER_H - 8;
            switch (tab) {
                case TEAM -> teamTab(g, t, team, bodyY, bodyH);
                case BOARD -> scrolled(g, bodyY, bodyH, y -> boardTab(g, t, team, y));
                case MARKS -> scrolled(g, bodyY, bodyH, y -> marksTab(g, t, team, y));
                case ALLIES -> scrolled(g, bodyY, bodyH, y -> alliesTab(g, t, y));
                case SETTINGS -> scrolled(g, bodyY, bodyH, y -> settingsTab(g, t, team, y));
            }
            undoBar(g, t);
        }
        TeamHud.toast(g, t, Minecraft.getInstance());
        if (tooltip != null) g.renderComponentTooltip(font(), tooltip, mouseX, mouseY);
    }

    private interface Content {
        int draw(int y);
    }

    private void scrolled(GuiGraphics g, int y, int h, Content content) {
        bodyScroll = Math.max(0, Math.min(bodyScroll, Math.max(0, bodyContent - h)));
        clipTop = y;
        clipBottom = y + h;
        g.enableScissor(px + 1, y, px + pw - 1, y + h);
        bodyContent = content.draw(y - bodyScroll) - (y - bodyScroll);
        g.disableScissor();
        clipTop = clipBottom = 0;
    }

    // --- общие элементы ---

    private void hit(int x, int y, int w, int h, Runnable action) {
        if (clipBottom != 0) {
            int top = Math.max(y, clipTop), bottom = Math.min(y + h, clipBottom);
            if (bottom <= top) return;
            hits.add(new Hit(x, top, w, bottom - top, action));
            return;
        }
        hits.add(new Hit(x, y, w, h, action));
    }

    private boolean hover(int x, int y, int w, int h) {
        if (clipBottom != 0 && (my < clipTop || my >= clipBottom)) return false;
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void button(GuiGraphics g, Theme t, int x, int y, int w, int h, String label, boolean accent, Runnable action) {
        t.button(g, x, y, w, h, hover(x, y, w, h), accent);
        Draw.textCentered(g, label, x + w / 2, y + (h - 8) / 2, accent ? t.c("accent.text") : t.c("text.body"));
        hit(x, y, w, h, action);
    }

    /** Кнопка необратимого действия: второе нажатие в течение трёх секунд выполняет его. */
    private void confirm(GuiGraphics g, Theme t, int x, int y, int w, int h, String label, String key, Runnable action) {
        boolean ready = armed.equals(key) && System.currentTimeMillis() < armedUntil;
        t.button(g, x, y, w, h, hover(x, y, w, h), false);
        Draw.textCentered(g, ready ? tr("rysteams.confirm") : label, x + w / 2, y + (h - 8) / 2, t.c(ready ? "state.bad" : "text.body"));
        hit(x, y, w, h, () -> {
            if (ready) {
                armed = "";
                action.run();
            } else {
                armed = key;
                armedUntil = System.currentTimeMillis() + 3000;
            }
        });
    }

    private int buttonW(String label) {
        return font().width(label) + 12;
    }

    private void section(GuiGraphics g, Theme t, String label, int x, int y) {
        Draw.textScaled(g, label.toUpperCase(java.util.Locale.ROOT), x, y, 0.75f, t.c("text.dim"));
    }

    private void input(GuiGraphics g, Theme t, EditBox b, int x, int y, int w) {
        t.input(g, x, y, w, 15, b.isFocused());
        b.setX(x + 5);
        b.setY(y + 4);
        b.setWidth(w - 10);
        boolean inView = clipBottom == 0 || (y >= clipTop && y + 15 <= clipBottom);
        b.setVisible(inView);
        b.setTextColor(t.c("input.fg") & 0xFFFFFF);
        if (inView) b.render(g, mx, my, 0);
    }

    private void teamTile(GuiGraphics g, int color, String icon, int x, int y, int size) {
        Draw.round(g, x, y, size, size, size / 4f, TeamHud.rgb(color));
        ItemStack stack = TeamClient.icon(icon);
        if (stack.isEmpty()) return;
        float scale = (size - 4) / 16f;
        g.pose().pushPose();
        g.pose().translate(x + 2, y + 2, 0);
        g.pose().scale(scale, scale, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    private int chip(GuiGraphics g, String label, int x, int y, int bg, int fg) {
        int w = Math.round(font().width(label) * 0.75f) + 7;
        Draw.round(g, x, y, w, 9, 4.5f, bg);
        Draw.textScaled(g, label, x + 3.5f, y + 1.5f, 0.75f, fg);
        return w;
    }

    private int sourceChip(GuiGraphics g, Theme t, String source, int x, int y) {
        int[] c = sourceColors(t, source);
        return chip(g, tr("rysteams.source." + source), x, y, c[0], c[1]);
    }

    private int[] sourceColors(Theme t, String source) {
        return switch (source) {
            case "quests" -> new int[] {Draw.alpha(t.c("accent"), 0.2f), t.c("accent")};
            case "pulse" -> new int[] {Draw.alpha(t.c("state.bad"), 0.2f), t.c("state.bad")};
            case "scope" -> new int[] {t.c("chip.mod.bg"), t.c("chip.mod.fg")};
            default -> new int[] {t.c("chip.tag.bg"), t.c("chip.tag.fg")};
        };
    }

    private int roleChip(GuiGraphics g, Theme t, Role role, int x, int y) {
        String label = tr("rysteams.role." + role.key());
        return switch (role) {
            case LEADER -> chip(g, label, x, y, Draw.alpha(t.c("accent"), 0.2f), t.c("accent"));
            case OFFICER -> chip(g, label, x, y, t.c("chip.mod.bg"), t.c("chip.mod.fg"));
            case MEMBER -> chip(g, label, x, y, t.c("button.bg"), t.c("text.body"));
            case GUEST -> chip(g, label, x, y, 0, t.c("text.dim"));
        };
    }

    private void avatar(GuiGraphics g, Theme t, Team team, UUID id, int x, int y, boolean online) {
        Draw.roundBorder(g, x, y, 18, 18, 3.5f, TeamHud.rgb(team.color), 1f);
        TeamHud.face(g, id, x + 2, y + 2, 14);
        if (!online) Draw.rect(g, x + 2, y + 2, 14, 14, Draw.alpha(t.c("panel.bg"), 0.55f));
        Draw.dot(g, x + 13, y + 13, 6, t.c("card.bg"));
        Draw.dot(g, x + 14, y + 14, 4, t.c(online ? "state.ok" : "text.dim"));
    }

    private boolean isOnline(UUID id) {
        return id.equals(me()) || TeamClient.PRESENCE.containsKey(id);
    }

    private String ago(long time) {
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (minutes < 60) return tr("rysteams.ago.min", minutes);
        if (minutes < 1440) return tr("rysteams.ago.hour", minutes / 60);
        return tr("rysteams.ago.day", minutes / 1440);
    }

    // --- шапка ---

    private void header(GuiGraphics g, Theme t, Team team) {
        Font f = font();
        int x = px + PAD, cy = py + 7;
        if (pw >= 500) {
            Draw.text(g, "RYS", x, py + 11, t.c("accent"));
            Draw.text(g, "Teams", x + f.width("RYS"), py + 11, t.c("text.primary"));
            x += f.width("RYSTeams") + 8;
        }
        int online = 0;
        for (UUID id : team.members.keySet()) if (isOnline(id)) online++;
        String sub = tr("rysteams.header.sub", team.members.size(), online);
        int cardW = Math.max(Math.min(f.width(team.name), 90), Math.round(f.width(sub) * 0.75f)) + 28;
        Draw.roundBox(g, x, cy - 3, cardW, 22, 4f, t.c("button.bg"), t.c("button.border"));
        teamTile(g, team.color, team.icon, x + 3, cy, 16);
        Draw.text(g, Draw.ellipsize(team.name, 90), x + 23, cy - 1, t.c("text.primary"));
        Draw.textScaled(g, sub, x + 23, cy + 9, 0.75f, t.c("text.muted"));
        x += cardW + 8;
        for (Tab each : Tab.values()) {
            String label = tr("rysteams.tab." + each.name().toLowerCase(java.util.Locale.ROOT));
            int w = f.width(label) + 12;
            boolean active = tab == each, hot = hover(x, py + 4, w, HEADER_H - 5);
            t.tabLine(g, x, py + 4, w, HEADER_H - 5, active, hot);
            Draw.text(g, label, x + 6, py + 11, t.tabLineFg(active, hot));
            hit(x, py + 4, w, HEADER_H - 5, () -> {
                tab = each;
                bodyScroll = 0;
            });
            x += w + 2;
        }
        int closeX = px + pw - PAD - 15;
        t.button(g, closeX, py + 7, 15, 15, hover(closeX, py + 7, 15, 15), false);
        Draw.cross(g, closeX + 4, py + 11, t.c("text.muted"));
        hit(closeX, py + 7, 15, 15, this::onClose);
        int infoX = closeX - 14;
        Draw.info(g, infoX, py + 10, hover(infoX - 1, py + 9, 12, 12) ? t.c("accent") : t.c("text.dim"));
        if (hover(infoX - 1, py + 9, 12, 12)) {
            tooltip = new ArrayList<>();
            for (String line : tr("rysteams.help", ClientEntry.PING.getTranslatedKeyMessage().getString()).split(" · ")) tooltip.add(Component.literal(line));
        }
        if (TeamClient.can(Permission.INVITE)) {
            String label = tr("rysteams.invite.button");
            int w = buttonW(label);
            int bx = infoX - 6 - w;
            if (bx > x) {
                button(g, t, bx, py + 7, w, 15, label, true, () -> {
                    tab = Tab.TEAM;
                    TeamClient.toast(Component.translatable("rysteams.toast.pick_online"), false);
                });
            }
        }
        Draw.rect(g, px + 1, py + HEADER_H - 1, pw - 2, 1, t.c("panel.border"));
    }

    private void undoBar(GuiGraphics g, Theme t) {
        long left = (TeamClient.undoUntil - System.currentTimeMillis()) / 1000;
        if (TeamClient.undoUntil == 0 || left < 0 || !TeamClient.can(Permission.MANAGE)) return;
        Font f = font();
        String text = tr("rysteams.merge.done");
        String label = tr("rysteams.merge.undo", left);
        int w = f.width(text) + buttonW(label) + 26;
        int x = px + (pw - w) / 2, y = py + ph - 26;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Draw.roundBox(g, x, y, w, 20, 5f, t.c("tooltip.bg"), t.c("state.ok"));
        Draw.dot(g, x + 6, y + 7, 6, t.c("state.ok"));
        Draw.text(g, text, x + 16, y + 6, t.c("text.primary"));
        int bw = buttonW(label);
        button(g, t, x + w - bw - 4, y + 3, bw, 14, label, false, () -> TeamClient.action("merge_undo"));
        g.pose().popPose();
    }

    // --- вкладка команды ---

    private void teamTab(GuiGraphics g, Theme t, Team team, int y, int h) {
        int leftW = Math.max(112, pw / 4), rightW = Math.max(100, pw / 5);
        boolean three = pw >= 460;
        int left = px + PAD, feedX = left + leftW + 8;
        int feedW = pw - PAD * 2 - leftW - 8 - (three ? rightW + 8 : 0);
        membersColumn(g, t, team, left, y, leftW, h);
        feedColumn(g, t, team, feedX, y, feedW, h);
        if (three) onlineColumn(g, t, px + pw - PAD - rightW, y, rightW, h);
    }

    private void membersColumn(GuiGraphics g, Theme t, Team team, int x, int y, int w, int h) {
        section(g, t, tr("rysteams.section.members"), x + 1, y + 2);
        String count = Integer.toString(team.members.size());
        Draw.textScaled(g, count, x + w - font().width(count) * 0.75f, y + 2, 0.75f, t.c("text.dim"));
        int cy = y + 12;
        int bottom = y + h;
        for (Team.Member m : team.members.values()) {
            boolean open = m.id.equals(selected) && TeamClient.can(Permission.MANAGE) && !m.id.equals(me());
            int cardH = open ? 44 : 26;
            if (cy + cardH > bottom - (team.requests.isEmpty() ? 0 : 30)) break;
            memberCard(g, t, team, m, x, cy, w, cardH, open);
            cy += cardH + 3;
        }
        if (!team.requests.isEmpty() && TeamClient.can(Permission.ACCEPT_REQUESTS)) requests(g, t, team, x, bottom - 28, w);
    }

    private void memberCard(GuiGraphics g, Theme t, Team team, Team.Member m, int x, int y, int w, int h, boolean open) {
        t.card(g, x, y, w, h);
        boolean online = isOnline(m.id);
        avatar(g, t, team, m.id, x + 4, y + 4, online);
        int nameX = x + 26;
        String name = Draw.ellipsize(m.name, w - 72);
        Draw.text(g, name, nameX, y + 5, online ? t.c("text.primary") : t.c("text.muted"));
        roleChip(g, t, m.role, nameX + font().width(name) + 4, y + 5);
        TeamClient.Pres p = TeamClient.PRESENCE.get(m.id);
        String line;
        if (m.id.equals(me())) line = tr("rysteams.status.you");
        else if (p == null) line = tr("rysteams.status.offline_for", ago(m.lastSeen));
        else {
            line = TeamHud.dimensionName(p.dimension());
            if (p.hasPos()) line += " · " + p.x() + " " + p.y() + " " + p.z();
            if (!p.status().isEmpty()) line = p.status();
        }
        Draw.textScaled(g, Draw.ellipsize(line, (int) ((w - 30) / 0.75f)), nameX, y + 15, 0.75f, t.c("text.muted"));
        hit(x, y, w, 26, () -> selected = m.id.equals(selected) ? null : m.id);
        if (!open) return;
        int bx = x + 4, by = y + 27;
        for (Role role : new Role[] {Role.OFFICER, Role.MEMBER, Role.GUEST}) {
            if (role == m.role) continue;
            String label = tr("rysteams.role." + role.key());
            int bw = Math.round(font().width(label) * 0.75f) + 8;
            t.button(g, bx, by, bw, 13, hover(bx, by, bw, 13), false);
            Draw.textScaled(g, label, bx + 4, by + 3.5f, 0.75f, t.c("text.body"));
            hit(bx, by, bw, 13, () -> {
                CompoundTag a = new CompoundTag();
                a.putUUID("player", m.id);
                a.putString("role", role.key());
                TeamClient.action("set_role", a);
            });
            bx += bw + 2;
        }
        int rest = x + w - 4 - bx;
        if (rest >= 54) {
            int bw = rest / 2 - 1;
            confirm(g, t, bx, by, bw, 13, "★", "transfer:" + m.id, () -> TeamClient.action("transfer", "player", m.id));
            if (hover(bx, by, bw, 13)) tooltip = List.of(Component.translatable("rysteams.action.transfer"));
            confirm(g, t, bx + bw + 2, by, bw, 13, "✕", "kick:" + m.id, () -> TeamClient.action("kick", "player", m.id));
            if (hover(bx + bw + 2, by, bw, 13)) tooltip = List.of(Component.translatable("rysteams.action.kick"));
        }
    }

    private void requests(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        t.card(g, x, y, w, 28);
        Draw.textScaled(g, tr("rysteams.section.requests", team.requests.size()), x + 5, y + 3, 0.75f, t.c("text.muted"));
        var first = team.requests.entrySet().iterator().next();
        Draw.text(g, Draw.ellipsize(first.getValue(), w - 50), x + 5, y + 14, t.c("text.primary"));
        button(g, t, x + w - 38, y + 11, 16, 14, "✓", true, () -> TeamClient.action("accept_request", "player", first.getKey()));
        button(g, t, x + w - 20, y + 11, 16, 14, "✕", false, () -> TeamClient.action("decline_request", "player", first.getKey()));
    }

    private void feedColumn(GuiGraphics g, Theme t, Team team, int x, int y, int w, int h) {
        section(g, t, tr("rysteams.section.feed"), x + 1, y + 2);
        List<String> sources = new ArrayList<>();
        sources.add("");
        for (Team.Feed f : team.feed) if (!sources.contains(f.source())) sources.add(f.source());
        int cx = x + w;
        for (int i = sources.size() - 1; i >= 0; i--) {
            String source = sources.get(i);
            String label = source.isEmpty() ? tr("rysteams.filter.all") : tr("rysteams.source." + source);
            int cw = Math.round(font().width(label) * 0.75f) + 8;
            cx -= cw;
            int[] c = source.isEmpty() ? new int[] {t.c("button.bg"), t.c("text.body")} : sourceColors(t, source);
            boolean active = filter.equals(source);
            Draw.round(g, cx, y, cw, 10, 5f, active ? c[0] : 0);
            if (!active) Draw.roundBorder(g, cx, y, cw, 10, 5f, Draw.alpha(c[1], 0.45f));
            Draw.textScaled(g, label, cx + 4, y + 2, 0.75f, c[1]);
            hit(cx, y, cw, 10, () -> {
                filter = source;
                feedScroll = 0;
            });
            cx -= 3;
        }
        Team.Member self = team.members.get(me());
        long since = self == null ? 0 : self.lastSeen;
        int missed = 0;
        Component firstMissed = null;
        if (since > 0) {
            for (Team.Feed f : team.feed) {
                if (f.time() <= since) break;
                missed++;
                firstMissed = TeamClient.text(f.text());
            }
        }
        int summaryH = missed > 0 ? 24 : 0;
        int top = y + 13, listH = h - 13 - (summaryH > 0 ? summaryH + 3 : 0);
        feedScroll = Math.max(0, Math.min(feedScroll, Math.max(0, feedContent - listH)));
        clipTop = top;
        clipBottom = top + listH;
        g.enableScissor(x, top, x + w, top + listH);
        int ry = top - feedScroll;
        for (Team.Feed f : team.feed) {
            if (!filter.isEmpty() && !filter.equals(f.source())) continue;
            ry += feedRow(g, t, f, x, ry, w, top, top + listH) + 3;
        }
        if (team.feed.isEmpty()) Draw.text(g, tr("rysteams.feed.empty"), x + 4, top + 4, t.c("text.dim"));
        feedContent = ry + feedScroll - top;
        g.disableScissor();
        clipTop = clipBottom = 0;
        if (summaryH > 0) {
            int sy = y + h - summaryH;
            t.card(g, x, sy, w, summaryH);
            Draw.textScaled(g, tr("rysteams.feed.missed", missed), x + 6, sy + 4, 0.75f, t.c("text.primary"));
            Draw.textScaled(g, Draw.ellipsize(firstMissed.getString(), (int) ((w - 12) / 0.75f)), x + 6, sy + 13, 0.75f, t.c("text.muted"));
        }
    }

    private int feedRow(GuiGraphics g, Theme t, Team.Feed f, int x, int y, int w, int top, int bottom) {
        Font font = font();
        boolean action = f.action().startsWith("waypoint:");
        String actionLabel = tr("rysteams.feed.waypoint");
        int actionW = action ? buttonW(actionLabel) + 4 : 0;
        String chipLabel = tr("rysteams.source." + f.source());
        int chipW = Math.round(font.width(chipLabel) * 0.75f) + 7;
        boolean hasIcon = !f.icon().isEmpty() && !TeamClient.icon(f.icon()).isEmpty();
        int textX = x + 30 + chipW + (hasIcon ? 17 : 4);
        int textW = x + w - 6 - actionW - textX;
        List<FormattedCharSequence> lines = font.split(TeamClient.text(f.text()), Math.max(40, textW));
        int h = Math.max(20, lines.size() * 9 + 7);
        if (y + h < top || y > bottom) return h;
        if (f.alert()) {
            Draw.roundBox(g, x, y, w, h, t.r("card.radius"), Draw.alpha(t.c("state.bad"), 0.12f), Draw.alpha(t.c("state.bad"), 0.5f));
        } else {
            t.card(g, x, y, w, h);
        }
        Instant at = Instant.ofEpochMilli(f.time());
        LocalDate date = at.atZone(ZoneId.systemDefault()).toLocalDate();
        String stamp = date.equals(LocalDate.now()) ? TIME.withZone(ZoneId.systemDefault()).format(at)
            : date.equals(LocalDate.now().minusDays(1)) ? tr("rysteams.time.yesterday") : date.getDayOfMonth() + "." + date.getMonthValue();
        Draw.textScaled(g, stamp, x + 5, y + (h - 6) / 2f, 0.75f, t.c("text.dim"));
        sourceChip(g, t, f.source(), x + 30, y + (h - 9) / 2);
        if (hasIcon) {
            g.pose().pushPose();
            g.pose().translate(x + 30 + chipW + 3, y + (h - 12) / 2f, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.renderItem(TeamClient.icon(f.icon()), 0, 0);
            g.pose().popPose();
        }
        int ly = y + (h - lines.size() * 9) / 2 + 1;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, textX, ly, t.c("text.body"), false);
            ly += 9;
        }
        if (action) {
            int bw = buttonW(actionLabel);
            button(g, t, x + w - bw - 4, y + (h - 14) / 2, bw, 14, actionLabel, false, () -> setWaypoint(f.text(), f.action()));
        }
        return h;
    }

    /** Формат: waypoint:пространство:имя:x:y:z. Имя измерения само содержит двоеточие, поэтому разбор с конца. */
    private void setWaypoint(String text, String action) {
        String[] parts = action.split(":");
        if (parts.length < 6) return;
        int n = parts.length;
        String dim = String.join(":", java.util.Arrays.copyOfRange(parts, 1, n - 3));
        int color = TeamClient.team == null ? 0xFFFFFF : TeamClient.team.color;
        TeamClient.waypoint = new TeamClient.Waypoint(tr("rysteams.feed.waypoint"), color, dim,
            Integer.parseInt(parts[n - 3]), Integer.parseInt(parts[n - 2]), Integer.parseInt(parts[n - 1]), System.currentTimeMillis() + 120_000);
        onClose();
    }

    /** Сводка от модов семьи: у каждого своя карточка, поверх списка игроков. */
    private int cards(GuiGraphics g, Theme t, int x, int y, int w) {
        List<TeamCards.Card> cards = TeamCards.current();
        if (cards.isEmpty()) return y;
        section(g, t, tr("rysteams.section.summary"), x + 1, y + 2);
        int ry = y + 12;
        Font f = font();
        for (TeamCards.Card card : cards) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (Component line : card.lines()) lines.addAll(f.split(line, (int) ((w - 12) / 0.75f)));
            if (lines.size() > 4) lines = lines.subList(0, 4);
            int h = 17 + lines.size() * 8 + (card.button() != null ? 17 : 2);
            if (card.alert()) Draw.roundBox(g, x, ry, w, h, t.r("card.radius"), Draw.alpha(t.c("state.bad"), 0.12f), Draw.alpha(t.c("state.bad"), 0.5f));
            else t.card(g, x, ry, w, h);
            Draw.text(g, Draw.ellipsize(card.title().getString(), w - 12), x + 6, ry + 5, t.c("text.primary"));
            int ly = ry + 16;
            for (FormattedCharSequence line : lines) {
                g.pose().pushPose();
                g.pose().translate(x + 6, ly, 0);
                g.pose().scale(0.75f, 0.75f, 1f);
                g.drawString(f, line, 0, 0, t.c("text.body"), false);
                g.pose().popPose();
                ly += 8;
            }
            if (card.button() != null && card.action() != null) {
                String label = card.button().getString();
                button(g, t, x + 6, ly + 1, buttonW(label), 14, label, false, card.action());
            }
            ry += h + 3;
        }
        return ry + 3;
    }

    private void onlineColumn(GuiGraphics g, Theme t, int x, int top, int w, int fullH) {
        int y = cards(g, t, x, top, w);
        int h = fullH - (y - top);
        section(g, t, tr("rysteams.section.online"), x + 1, y + 2);
        int ry = y + 12;
        if (TeamClient.online.isEmpty()) Draw.textScaled(g, tr("rysteams.online.empty"), x + 1, ry + 2, 0.75f, t.c("text.dim"));
        boolean canInvite = TeamClient.can(Permission.INVITE);
        for (TeamClient.Online o : TeamClient.online) {
            if (ry + 20 > y + h) break;
            t.card(g, x, ry, w, 20);
            TeamHud.face(g, o.id(), x + 4, ry + 4, 12);
            String label = tr("rysteams.online.invite");
            boolean show = canInvite && o.free();
            int bw = show ? buttonW(label) : 0;
            Draw.text(g, Draw.ellipsize(o.name(), w - 24 - bw), x + 20, ry + 6, t.c("text.primary"));
            if (show) button(g, t, x + w - bw - 3, ry + 3, bw, 14, label, false, () -> TeamClient.action("invite", "player", o.id()));
            else if (!o.free()) {
                String busy = tr("rysteams.online.busy");
                Draw.textScaled(g, busy, x + w - 4 - font().width(busy) * 0.75f, ry + 7, 0.75f, t.c("text.dim"));
            }
            ry += 23;
        }
    }

    // --- доска ---

    private int boardTab(GuiGraphics g, Theme t, Team team, int y) {
        int w = (pw - PAD * 3) / 2;
        int left = px + PAD, right = left + w + PAD;
        boolean edit = TeamClient.can(Permission.EDIT_BOARD);
        section(g, t, tr("rysteams.section.notes"), left + 1, y + 2);
        int ly = y + 12;
        if (edit) {
            String add = tr("rysteams.action.add");
            int bw = buttonW(add);
            input(g, t, noteBox, left, ly, w - bw - 3);
            button(g, t, left + w - bw, ly, bw, 15, add, true, () -> addText("note_add", noteBox));
            ly += 19;
        }
        Font f = font();
        for (Team.Note n : team.notes) {
            List<FormattedCharSequence> lines = f.split(Component.literal(n.text), w - 26);
            int h = lines.size() * 9 + 16;
            t.card(g, left, ly, w, h);
            int ty = ly + 5;
            for (FormattedCharSequence line : lines) {
                g.drawString(f, line, left + 6, ty, t.c("text.body"), false);
                ty += 9;
            }
            Draw.textScaled(g, n.by + " · " + ago(n.time), left + 6, ty + 1, 0.75f, t.c("text.dim"));
            if (edit) smallDelete(g, t, left + w - 13, ly + 3, () -> byId("note_del", n.id));
            ly += h + 3;
        }
        if (team.notes.isEmpty()) Draw.textScaled(g, tr("rysteams.empty.notes"), left + 1, ly + 2, 0.75f, t.c("text.dim"));

        section(g, t, tr("rysteams.section.todos"), right + 1, y + 2);
        int ry = y + 12;
        if (edit) {
            String add = tr("rysteams.action.add");
            int bw = buttonW(add);
            input(g, t, todoBox, right, ry, w - bw - 3);
            button(g, t, right + w - bw, ry, bw, 15, add, true, () -> addText("todo_add", todoBox));
            ry += 19;
        }
        String self = Minecraft.getInstance().player.getGameProfile().getName();
        for (Team.Todo x : team.todos) {
            t.card(g, right, ry, w, 20);
            int box = right + 5;
            Draw.roundBox(g, box, ry + 5, 10, 10, 2.5f, x.done ? t.c("state.ok") : t.c("slot.bg"), t.c("slot.border"));
            if (x.done) Draw.text(g, "✓", box + 2, ry + 6, t.c("accent.text"));
            if (edit) hit(box, ry + 3, 14, 14, () -> byId("todo_toggle", x.id));
            int textEnd = right + w - 18 - (x.assignee.isEmpty() ? 22 : 0);
            String who = x.assignee.isEmpty() ? "" : x.assignee;
            int whoW = who.isEmpty() ? 0 : Math.round(f.width(who) * 0.75f) + 6;
            Draw.text(g, Draw.ellipsize(x.text, textEnd - whoW - box - 14), box + 14, ry + 6, x.done ? t.c("text.dim") : t.c("text.body"));
            if (!who.isEmpty()) Draw.textScaled(g, who, right + w - 16 - whoW, ry + 7, 0.75f, x.assignee.equals(self) ? t.c("accent") : t.c("text.muted"));
            else if (edit) {
                String take = tr("rysteams.action.take");
                float tw = f.width(take) * 0.75f;
                int tx = (int) (right + w - 18 - tw);
                Draw.textScaled(g, take, tx, ry + 7, 0.75f, hover(tx, ry + 4, (int) tw, 12) ? t.c("accent") : t.c("text.dim"));
                hit(tx, ry + 4, (int) tw, 12, () -> byId("todo_assign", x.id));
            }
            if (edit) smallDelete(g, t, right + w - 13, ry + 4, () -> byId("todo_del", x.id));
            ry += 23;
        }
        if (team.todos.isEmpty()) Draw.textScaled(g, tr("rysteams.empty.todos"), right + 1, ry + 2, 0.75f, t.c("text.dim"));
        return Math.max(ly, ry);
    }

    private void addText(String action, EditBox box) {
        String text = box.getValue().strip();
        if (text.isEmpty()) return;
        CompoundTag a = new CompoundTag();
        a.putString("text", text);
        TeamClient.action(action, a);
        box.setValue("");
    }

    private void byId(String action, UUID id) {
        TeamClient.action(action, "id", id);
    }

    private void smallDelete(GuiGraphics g, Theme t, int x, int y, Runnable action) {
        Draw.cross(g, x + 2, y + 2, hover(x, y, 11, 11) ? t.c("state.bad") : t.c("text.dim"));
        hit(x, y, 11, 11, action);
    }

    // --- метки ---

    private int marksTab(GuiGraphics g, Theme t, Team team, int y) {
        int x = px + PAD, w = pw - PAD * 2;
        boolean edit = TeamClient.can(Permission.EDIT_MARKS);
        section(g, t, tr("rysteams.section.marks"), x + 1, y + 2);
        int ry = y + 12;
        if (edit) {
            String add = tr("rysteams.mark.add");
            int bw = buttonW(add);
            input(g, t, markBox, x, ry, w - bw - 3);
            button(g, t, x + w - bw, ry, bw, 15, add, true, () -> {
                String name = markBox.getValue().strip();
                if (name.isEmpty()) return;
                CompoundTag a = new CompoundTag();
                a.putString("name", name);
                TeamClient.action("mark_add", a);
                markBox.setValue("");
            });
            ry += 19;
        }
        String label = tr("rysteams.feed.waypoint");
        Minecraft mc = Minecraft.getInstance();
        for (Team.Mark m : team.marks) {
            t.card(g, x, ry, w, 24);
            ItemStack icon = TeamClient.icon(m.icon());
            if (!icon.isEmpty()) g.renderItem(icon, x + 5, ry + 4);
            Draw.text(g, Draw.ellipsize(m.name(), w / 2), x + 26, ry + 4, t.c("text.primary"));
            String place = TeamHud.dimensionName(m.dimension()) + " · " + m.x() + " " + m.y() + " " + m.z() + " · " + m.by();
            Draw.textScaled(g, place, x + 26, ry + 14, 0.75f, t.c("text.muted"));
            int bw = buttonW(label);
            int right = x + w - 4 - (edit ? 14 : 0);
            button(g, t, right - bw, ry + 5, bw, 14, label, false, () -> {
                TeamClient.waypoint = new TeamClient.Waypoint(m.name(), team.color, m.dimension(), m.x(), m.y(), m.z(), System.currentTimeMillis() + 120_000);
                mc.setScreen(null);
            });
            if (edit) smallDelete(g, t, x + w - 14, ry + 6, () -> byId("mark_del", m.id()));
            ry += 27;
        }
        if (team.marks.isEmpty()) Draw.textScaled(g, tr("rysteams.empty.marks"), x + 1, ry + 2, 0.75f, t.c("text.dim"));
        return ry;
    }

    // --- союзы ---

    private int alliesTab(GuiGraphics g, Theme t, int y) {
        int w = (pw - PAD * 3) / 2;
        int left = px + PAD, right = left + w + PAD;
        boolean manage = TeamClient.can(Permission.MANAGE);
        section(g, t, tr("rysteams.section.allies"), left + 1, y + 2);
        int ly = y + 12;
        for (TeamClient.Info offer : TeamClient.allyOffers) {
            teamRow(g, t, offer, left, ly, w, tr("rysteams.ally.offered"));
            if (manage) {
                button(g, t, left + w - 38, ly + 4, 16, 14, "✓", true, () -> TeamClient.action("ally_accept", "team", offer.id()));
                button(g, t, left + w - 20, ly + 4, 16, 14, "✕", false, () -> TeamClient.action("ally_decline", "team", offer.id()));
            }
            ly += 25;
        }
        for (TeamClient.Info ally : TeamClient.allies) {
            teamRow(g, t, ally, left, ly, w, tr("rysteams.team.count", ally.count()));
            if (manage) {
                String label = tr("rysteams.ally.remove");
                int bw = buttonW(label);
                confirm(g, t, left + w - bw - 4, ly + 4, bw, 14, label, "ally:" + ally.id(), () -> TeamClient.action("ally_remove", "team", ally.id()));
            }
            ly += 25;
        }
        if (TeamClient.allies.isEmpty() && TeamClient.allyOffers.isEmpty()) {
            Draw.textScaled(g, tr("rysteams.empty.allies"), left + 1, ly + 2, 0.75f, t.c("text.dim"));
        }
        section(g, t, tr("rysteams.section.others"), right + 1, y + 2);
        int ry = y + 12;
        for (TeamClient.Info other : TeamClient.teams) {
            if (isAlly(other.id())) continue;
            teamRow(g, t, other, right, ry, w, tr("rysteams.team.count", other.count()));
            if (manage) {
                String label = tr("rysteams.ally.offer");
                int bw = buttonW(label);
                button(g, t, right + w - bw - 4, ry + 4, bw, 14, label, false, () -> TeamClient.action("ally_offer", "team", other.id()));
            }
            ry += 25;
        }
        return Math.max(ly, ry);
    }

    private boolean isAlly(UUID id) {
        for (TeamClient.Info a : TeamClient.allies) if (a.id().equals(id)) return true;
        return false;
    }

    private void teamRow(GuiGraphics g, Theme t, TeamClient.Info info, int x, int y, int w, String sub) {
        t.card(g, x, y, w, 22);
        teamTile(g, info.color(), info.icon(), x + 4, y + 3, 16);
        Draw.text(g, Draw.ellipsize(info.name(), w - 90), x + 24, y + 3, t.c("text.primary"));
        Draw.textScaled(g, sub, x + 24, y + 13, 0.75f, t.c("text.muted"));
    }

    // --- настройки ---

    private int settingsTab(GuiGraphics g, Theme t, Team team, int y) {
        int left = px + PAD;
        int leftW = Math.min(300, (pw - PAD * 3) * 11 / 20);
        int right = left + leftW + PAD, rightW = px + pw - PAD - right;
        int ly = permissions(g, t, team, left, y, leftW);
        ly = rules(g, t, team, left, ly + 6, leftW);
        int ry = identity(g, t, team, right, y, rightW);
        ry = mergeBlock(g, t, team, right, ry + 8, rightW);
        ry = danger(g, t, team, right, ry + 8, rightW);
        return Math.max(ly, ry);
    }

    private int permissions(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        Font f = font();
        boolean manage = TeamClient.can(Permission.MANAGE);
        Draw.text(g, tr("rysteams.section.perms"), x + 1, y + 2, t.c("text.primary"));
        int cellW = 36, cols = x + w - cellW * Role.values().length - 2;
        int headY = y + 15;
        for (Role role : Role.values()) {
            int cx = cols + role.ordinal() * cellW;
            String label = tr("rysteams.role." + role.key());
            int color = switch (role) {
                case LEADER -> t.c("accent");
                case OFFICER -> t.c("chip.mod.fg");
                case MEMBER -> t.c("text.body");
                case GUEST -> t.c("text.dim");
            };
            Draw.textScaled(g, label, cx + (cellW - f.width(label) * 0.75f) / 2f, headY, 0.75f, color);
        }
        int ry = y + 26;
        for (Permission p : Permission.values()) {
            Draw.textScaled(g, Draw.ellipsize(tr("rysteams.perm." + p.key()), (int) ((cols - x - 4) / 0.75f)), x + 2, ry + 3, 0.75f, t.c("text.body"));
            for (Role role : Role.values()) {
                int cx = cols + role.ordinal() * cellW + (cellW - 11) / 2;
                boolean on = team.can(role, p);
                boolean locked = p.locked() || role == Role.LEADER;
                if (p.locked() && on) {
                    Draw.round(g, cx, ry, 11, 11, 2.5f, Draw.alpha(t.c("state.warn"), 0.25f));
                    Draw.lock(g, cx + 2, ry + 2, t.c("state.warn"));
                } else {
                    Draw.roundBox(g, cx, ry, 11, 11, 2.5f, on ? t.c("state.ok") : t.c("slot.bg"), on ? 0 : t.c("slot.border"));
                    if (on) Draw.text(g, "✓", cx + 3, ry + 2, t.c("accent.text"));
                }
                if (manage && !locked) {
                    hit(cx - 2, ry - 1, 15, 13, () -> {
                        CompoundTag a = new CompoundTag();
                        a.putString("perm", p.key());
                        a.putString("role", role.key());
                        TeamClient.action("set_perm", a);
                    });
                }
            }
            ry += 14;
        }
        if (manage) {
            String reset = tr("rysteams.perm.reset");
            button(g, t, x, ry + 2, buttonW(reset), 14, reset, false, () -> TeamClient.action("reset_perms"));
            ry += 18;
        }
        return ry;
    }

    private int rules(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        section(g, t, tr("rysteams.section.rules"), x + 1, y + 2);
        String days = team.autoTransferDays == 0 ? tr("rysteams.rule.never") : tr("rysteams.rule.days", team.autoTransferDays);
        String[][] rows = {
            {"join", tr("rysteams.rule.join"), tr("rysteams.join." + team.join.name().toLowerCase(java.util.Locale.ROOT))},
            {"rewards", tr("rysteams.rule.rewards"), tr("rysteams.rewards." + team.rewards.name().toLowerCase(java.util.Locale.ROOT))},
            {"keep", tr("rysteams.rule.keep"), tr(team.keepCopyOnLeave ? "rysteams.keep.copy" : "rysteams.keep.none")},
            {"auto", tr("rysteams.rule.auto"), days},
        };
        boolean manage = TeamClient.can(Permission.MANAGE);
        int colW = (w - 4) / 2;
        int ry = y + 12;
        for (int i = 0; i < rows.length; i++) {
            String[] row = rows[i];
            int cx = x + (i % 2) * (colW + 4);
            int cy = ry + (i / 2) * 18;
            boolean hot = manage && hover(cx, cy, colW, 16);
            Draw.roundBox(g, cx, cy, colW, 16, 3f, hot ? t.c("button.hover") : t.c("card.bg"), t.c("card.border"));
            Draw.textScaled(g, row[1], cx + 5, cy + 5, 0.75f, t.c("text.muted"));
            float vw = font().width(row[2]) * 0.75f;
            Draw.textScaled(g, row[2], cx + colW - 5 - vw, cy + 5, 0.75f, t.c("text.primary"));
            if (manage) {
                hit(cx, cy, colW, 16, () -> {
                    CompoundTag a = new CompoundTag();
                    a.putString("rule", row[0]);
                    TeamClient.action("set_rule", a);
                });
            }
        }
        return ry + 36;
    }

    private int identity(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        Draw.text(g, tr("rysteams.section.identity"), x + 1, y + 2, t.c("text.primary"));
        int ry = y + 14;
        boolean manage = TeamClient.can(Permission.MANAGE);
        if (manage) {
            String save = tr("rysteams.action.rename");
            int bw = buttonW(save);
            input(g, t, nameBox, x, ry, w - bw - 3);
            button(g, t, x + w - bw, ry, bw, 15, save, false, () -> {
                CompoundTag a = new CompoundTag();
                a.putString("name", nameBox.getValue());
                TeamClient.action("set_identity", a);
                nameBox.setValue("");
            });
            ry += 19;
            ry = swatches(g, t, x, ry, w, team.color, color -> {
                CompoundTag a = new CompoundTag();
                a.putInt("color", color);
                TeamClient.action("set_identity", a);
            });
            ItemStack held = Minecraft.getInstance().player.getMainHandItem();
            if (!held.isEmpty()) {
                String label = tr("rysteams.identity.icon");
                button(g, t, x, ry, buttonW(label), 14, label, false, () -> {
                    CompoundTag a = new CompoundTag();
                    a.putString("icon", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
                    TeamClient.action("set_identity", a);
                });
                ry += 17;
            }
        }
        ry = toggle(g, t, x, ry + 2, w, tr("rysteams.personal.hide"), TeamClient.hideCoords, () -> {
            CompoundTag a = new CompoundTag();
            a.putBoolean("on", !TeamClient.hideCoords);
            TeamClient.action("hide", a);
        });
        return toggle(g, t, x, ry, w, tr("rysteams.personal.hud"), ClientEntry.SHOW_HUD.get(), () -> {
            ClientEntry.SHOW_HUD.set(!ClientEntry.SHOW_HUD.get());
            ClientEntry.CLIENT_SPEC.save();
        });
    }

    private int toggle(GuiGraphics g, Theme t, int x, int y, int w, String label, boolean on, Runnable action) {
        Draw.textScaled(g, label, x + 1, y + 3, 0.75f, t.c("text.body"));
        int sw = 18, sx = x + w - sw;
        Draw.round(g, sx, y, sw, 10, 5f, on ? t.c("state.ok") : t.c("slot.bg"));
        Draw.dot(g, on ? sx + 9 : sx + 1, y + 1, 8, on ? t.c("accent.text") : t.c("text.dim"));
        hit(x, y - 1, w, 12, action);
        return y + 14;
    }

    private interface ColorPick {
        void pick(int color);
    }

    private int swatches(GuiGraphics g, Theme t, int x, int y, int w, int current, ColorPick pick) {
        int size = 13, gap = 3, perRow = Math.max(1, (w + gap) / (size + gap));
        for (int i = 0; i < Team.COLORS.length; i++) {
            int color = Team.COLORS[i];
            int cx = x + (i % perRow) * (size + gap), cy = y + (i / perRow) * (size + gap);
            Draw.round(g, cx, cy, size, size, 3f, TeamHud.rgb(color));
            if (color == current) Draw.roundBorder(g, cx - 1, cy - 1, size + 2, size + 2, 3.5f, t.c("text.primary"), 1f);
            hit(cx, cy, size, size, () -> pick.pick(color));
        }
        return y + ((Team.COLORS.length - 1) / perRow + 1) * (size + gap) + 2;
    }

    private int mergeBlock(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        Draw.text(g, tr("rysteams.section.merge"), x + 1, y + 2, t.c("text.primary"));
        int ry = y + 14;
        boolean manage = TeamClient.can(Permission.MANAGE);
        TeamClient.MergeInfo merge = TeamClient.merge;
        if (merge == null) {
            if (!manage) return ry;
            for (TeamClient.Info other : TeamClient.teams) {
                teamRow(g, t, other, x, ry, w, tr("rysteams.team.count", other.count()));
                String label = tr("rysteams.merge.propose");
                int bw = buttonW(label);
                confirm(g, t, x + w - bw - 4, ry + 4, bw, 14, label, "merge:" + other.id(), () -> TeamClient.action("merge_propose", "team", other.id()));
                ry += 25;
            }
            if (TeamClient.teams.isEmpty()) {
                Draw.textScaled(g, tr("rysteams.empty.merge"), x + 1, ry, 0.75f, t.c("text.dim"));
                ry += 10;
            }
            return ry;
        }
        teamRow(g, t, merge.into(), x, ry, w, tr("rysteams.team.count", merge.into().count()));
        ry += 24;
        teamRow(g, t, merge.from(), x, ry, w, tr("rysteams.team.count", merge.from().count()));
        ry += 26;
        section(g, t, tr("rysteams.merge.preview"), x + 1, ry);
        ry += 10;
        Font f = font();
        for (TeamClient.MergeLine line : merge.lines()) {
            int chipW = Math.round(f.width(tr("rysteams.source." + line.source())) * 0.75f) + 7;
            List<FormattedCharSequence> lines = f.split(line.text(), w - chipW - 14);
            int h = Math.max(16, lines.size() * 9 + 6);
            t.card(g, x, ry, w, h);
            sourceChip(g, t, line.source(), x + 4, ry + (h - 9) / 2);
            int ty = ry + (h - lines.size() * 9) / 2 + 1;
            for (FormattedCharSequence l : lines) {
                g.drawString(f, l, x + 8 + chipW, ty, t.c("text.body"), false);
                ty += 9;
            }
            ry += h + 2;
        }
        Draw.roundBox(g, x, ry + 2, w, 28, 4f, Draw.alpha(t.c("state.warn"), 0.12f), Draw.alpha(t.c("state.warn"), 0.45f));
        int ty = ry + 7;
        for (FormattedCharSequence l : f.split(Component.translatable("rysteams.merge.note"), (int) ((w - 12) / 0.75f))) {
            g.pose().pushPose();
            g.pose().translate(x + 6, ty, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(f, l, 0, 0, t.c("text.body"), false);
            g.pose().popPose();
            ty += 8;
        }
        ry += 34;
        if (manage) {
            boolean mine = team.id.equals(merge.from().id()) ? merge.fromOk() : merge.intoOk();
            if (mine) {
                Draw.textScaled(g, tr("rysteams.merge.waiting"), x + 1, ry + 4, 0.75f, t.c("text.muted"));
            } else {
                String yes = tr("rysteams.merge.confirm");
                int bw = buttonW(yes);
                button(g, t, x, ry, bw, 15, yes, true, () -> TeamClient.action("merge_confirm"));
            }
            String no = tr("rysteams.merge.cancel");
            int bw2 = buttonW(no);
            button(g, t, x + w - bw2, ry, bw2, 15, no, false, () -> TeamClient.action("merge_cancel"));
            ry += 18;
        }
        return ry;
    }

    private int danger(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        if (TeamClient.serverMode) return y;
        String leave = tr("rysteams.action.leave");
        confirm(g, t, x, y, buttonW(leave) + 4, 15, leave, "leave", () -> TeamClient.action("leave"));
        if (TeamClient.can(Permission.MANAGE)) {
            String disband = tr("rysteams.action.disband");
            int bw = buttonW(disband) + 4;
            confirm(g, t, x + w - bw, y, bw, 15, disband, "disband", () -> TeamClient.action("disband"));
        }
        return y + 18;
    }

    // --- нет команды ---

    private void lobby(GuiGraphics g, Theme t) {
        pw = Math.min(width - 8, 400);
        ph = Math.min(height - 8, 290);
        px = (width - pw) / 2;
        py = Math.max(4, Math.min(height / 12, height - ph - 4));
        t.panel(g, px, py, pw, ph);
        Font f = font();
        Draw.text(g, tr("rysteams.lobby.title"), px + PAD + 2, py + 9, t.c("text.primary"));
        String sub = tr("rysteams.lobby.sub");
        Draw.textScaled(g, sub, px + pw - PAD - 16 - f.width(sub) * 0.75f, py + 10, 0.75f, t.c("text.muted"));
        int closeX = px + pw - PAD - 13;
        t.button(g, closeX, py + 6, 13, 13, hover(closeX, py + 6, 13, 13), false);
        Draw.cross(g, closeX + 3, py + 9, t.c("text.muted"));
        hit(closeX, py + 6, 13, 13, this::onClose);
        Draw.rect(g, px + 1, py + 24, pw - 2, 1, t.c("panel.border"));
        int colW = (pw - PAD * 3) / 2, top = py + 30, colH = ph - 36;
        int left = px + PAD, right = left + colW + PAD;
        t.card(g, left, top, colW, colH);
        t.card(g, right, top, colW, colH);
        createForm(g, t, left + 7, top + 6, colW - 14, top + colH);
        joinList(g, t, right + 7, top + 6, colW - 14, top + colH);
    }

    private void createForm(GuiGraphics g, Theme t, int x, int y, int w, int bottom) {
        Draw.text(g, tr("rysteams.lobby.create"), x, y, t.c("text.primary"));
        int ry = y + 13;
        Draw.textScaled(g, tr("rysteams.lobby.name"), x, ry, 0.75f, t.c("text.muted"));
        input(g, t, nameBox, x, ry + 8, w);
        ry += 28;
        Draw.textScaled(g, tr("rysteams.lobby.color"), x, ry, 0.75f, t.c("text.muted"));
        ry = swatches(g, t, x, ry + 9, w, newColor, color -> newColor = color);
        Draw.textScaled(g, tr("rysteams.lobby.icon"), x, ry, 0.75f, t.c("text.muted"));
        ry += 9;
        var inv = Minecraft.getInstance().player.getInventory();
        int ix = x, shown = 0;
        for (int i = 0; i < 9 && shown < 7; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            t.slot(g, ix, ry, 18, hover(ix, ry, 18, 18) || id.equals(newIcon));
            g.renderItem(stack, ix + 1, ry + 1);
            hit(ix, ry, 18, 18, () -> newIcon = id);
            ix += 20;
            shown++;
        }
        if (shown == 0) Draw.textScaled(g, tr("rysteams.lobby.no_items"), x, ry + 5, 0.75f, t.c("text.dim"));
        ry += 24;
        int by = Math.min(ry, bottom - 22);
        button(g, t, x, by, w, 17, tr("rysteams.lobby.create_button"), true, () -> {
            CompoundTag a = new CompoundTag();
            a.putString("name", nameBox.getValue());
            a.putInt("color", newColor);
            a.putString("icon", newIcon);
            TeamClient.action("create", a);
        });
    }

    private void joinList(GuiGraphics g, Theme t, int x, int y, int w, int bottom) {
        Draw.text(g, tr("rysteams.lobby.join"), x, y, t.c("text.primary"));
        int ry = y + 13;
        if (!TeamClient.restore.isEmpty()) {
            String label = tr("rysteams.lobby.restore", TeamClient.restore);
            button(g, t, x, ry, w, 15, Draw.ellipsize(label, w - 8), false, () -> TeamClient.action("restore"));
            ry += 19;
        }
        for (TeamClient.Info invite : TeamClient.invites) {
            if (ry + 40 > bottom) return;
            int h = 38;
            Draw.roundBox(g, x, ry, w, h, 4f, Draw.alpha(t.c("accent"), 0.1f), Draw.alpha(t.c("accent"), 0.5f));
            teamTile(g, invite.color(), invite.icon(), x + 4, ry + 4, 12);
            Draw.textScaled(g, Draw.ellipsize(tr("rysteams.invite.title", invite.by(), invite.name()), (int) ((w - 24) / 0.75f)), x + 19, ry + 7, 0.75f, t.c("text.primary"));
            int bw = (w - 16) / 3;
            int by = ry + 20;
            CompoundTag base = new CompoundTag();
            base.putUUID("team", invite.id());
            answerButton(g, t, x + 4, by, bw, tr("rysteams.lobby.accept"), true, base, "accept");
            answerButton(g, t, x + 6 + bw, by, bw, tr("rysteams.invite.fresh"), false, base, "fresh");
            answerButton(g, t, x + 8 + bw * 2, by, bw, tr("rysteams.lobby.no"), false, base, "no");
            ry += h + 3;
        }
        int rows = 0;
        for (TeamClient.Info info : TeamClient.teams) {
            if (info.join().equals(Team.JoinMode.INVITE.name())) continue;
            if (ry + 26 > bottom - 14) break;
            boolean open = info.join().equals(Team.JoinMode.OPEN.name());
            String sub = tr("rysteams.team.count", info.count()) + " · " + tr("rysteams.join." + info.join().toLowerCase(java.util.Locale.ROOT));
            Draw.roundBox(g, x, ry, w, 24, 4f, t.c("panel.bg"), t.c("panel.border"));
            teamTile(g, info.color(), info.icon(), x + 4, ry + 4, 16);
            String label = tr(open ? "rysteams.lobby.enter" : "rysteams.lobby.request");
            int bw = buttonW(label);
            Draw.text(g, Draw.ellipsize(info.name(), w - 30 - bw), x + 24, ry + 4, t.c("text.primary"));
            Draw.textScaled(g, Draw.ellipsize(sub, (int) ((w - 30 - bw) / 0.75f)), x + 24, ry + 14, 0.75f, t.c("text.muted"));
            button(g, t, x + w - bw - 3, ry + 5, bw, 14, label, false, () -> TeamClient.action(open ? "join" : "request", "team", info.id()));
            ry += 27;
            rows++;
        }
        if (rows == 0 && TeamClient.invites.isEmpty()) Draw.textScaled(g, tr("rysteams.lobby.none"), x, ry + 2, 0.75f, t.c("text.dim"));
        Draw.textScaled(g, tr("rysteams.lobby.closed"), x, bottom - 12, 0.6f, t.c("text.dim"));
    }

    private void answerButton(GuiGraphics g, Theme t, int x, int y, int w, String label, boolean accent, CompoundTag base, String answer) {
        t.button(g, x, y, w, 14, hover(x, y, w, 14), accent);
        Draw.textScaled(g, Draw.ellipsize(label, (int) ((w - 4) / 0.75f)), x + (w - Math.min(w - 4, font().width(label) * 0.75f)) / 2f, y + 4, 0.75f, accent ? t.c("accent.text") : t.c("text.body"));
        hit(x, y, w, 14, () -> {
            CompoundTag a = base.copy();
            a.putString("answer", answer);
            TeamClient.action("invite_reply", a);
        });
    }

    // --- ввод ---

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.over(x, y)) {
                    hit.action.run();
                    return true;
                }
            }
        }
        boolean used = super.mouseClicked(x, y, button);
        if (!used) {
            setFocused(null);
            for (EditBox b : boxes) b.setFocused(false);
        }
        return used;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (tab == Tab.TEAM) feedScroll -= (int) (dy * 18);
        else bodyScroll -= (int) (dy * 18);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        for (EditBox b : boxes) {
            if (b.isFocused() && b.isVisible()) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                    b.setFocused(false);
                    setFocused(null);
                    return true;
                }
                b.keyPressed(key, scan, mods);
                return true;
            }
        }
        if (ClientEntry.OPEN.matches(key, scan) || Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
