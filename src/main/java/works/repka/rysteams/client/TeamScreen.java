package works.repka.rysteams.client;

import works.repka.ryscore.ui.Anchors;
import works.repka.ryscore.ui.ScaledScreen;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import works.repka.ryscore.team.TeamCards;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Scroller;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysteams.api.Permission;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.data.Team;

/**
 * Экран команды, размеры из {@code rysteams/design}: панель 1200×728 макета, в единицах GUI 600×364.
 * Рисуется целиком каждый кадр и записывает кнопки, клик ищет запись под курсором.
 */
public final class TeamScreen extends ScaledScreen {
    private enum Tab { TEAM, BOARD, MARKS, ALLIES, SETTINGS }

    private static final int W = 600, H = 364, HEADER = 32, LEFT = 150, RIGHT = 160;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] SOURCES = {"quests", "pulse", "scope", "teams"};

    private static Tab tab = Tab.TEAM;
    private final Kit.Hits hits = new Kit.Hits();
    private int px, py, pw, ph, mx, my;
    private int feedScroll, feedContent, bodyScroll, bodyContent;
    private final Scroller feedScroller = new Scroller(), bodyScroller = new Scroller(), lobbyScroller = new Scroller();
    private int lobbyContent;
    /** Переносы строк ленты и заметок: замер ширины дорогой, а текст между кадрами почти не меняется. */
    private final java.util.Map<String, List<String>> wraps = new java.util.HashMap<>();
    private String wrapsFor = "";
    private Team missedFor;
    private List<String> missed = List.of();
    private String filter = "";
    private UUID menuFor;
    private boolean invitePopup;
    private String armed = "";
    private long armedUntil;
    private int newColor = Team.COLORS[0];
    private String newIcon = "minecraft:white_banner";
    private Field nameBox, noteBox, todoBox, markBox;
    private final List<Field> boxes = new ArrayList<>();
    private List<Component> tooltip;

    public TeamScreen() {
        super(Component.translatable("rysteams.title"));
    }

    /** Экран, открытый на вкладке меток: из панели пинга. */
    static TeamScreen onMarks() {
        tab = Tab.MARKS;
        return new TeamScreen();
    }

    private Field box(int max, String hint, Field old) {
        Field b = new Field(100, 10);
        b.setMaxLength(max);
        b.setHint(Component.translatable(hint));
        b.setSpans(List.of(), new int[0]);
        if (old != null) b.setValue(old.getValue());
        addWidget(b);
        boxes.add(b);
        return b;
    }

    @Override
    protected void init() {
        super.init();
        boxes.clear();
        nameBox = box(24, "rysteams.hint.name", nameBox);
        noteBox = box(400, "rysteams.hint.note", noteBox);
        todoBox = box(160, "rysteams.hint.todo", todoBox);
        markBox = box(48, "rysteams.hint.mark", markBox);
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

    private UUID me() {
        return Minecraft.getInstance().player.getUUID();
    }

    static String plural(String key, int n) {
        int a = Math.abs(n) % 100, b = a % 10;
        String form = a >= 11 && a <= 14 ? "many" : b == 1 ? "one" : b >= 2 && b <= 4 ? "few" : "many";
        return tr(key + "." + form, n);
    }

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Theme t = ThemeManager.get();
        mx = mouseX;
        my = mouseY;
        hits.clear();
        tooltip = null;
        for (Field b : boxes) b.setVisible(false);
        renderBackground(g, mouseX, mouseY, partialTick);
        // ширина текста зависит от темы и масштаба: при их смене переносы считаются заново
        String sig = System.identityHashCode(t) + ":" + works.repka.ryscore.ui.Draw.scale();
        if (!sig.equals(wrapsFor) || wraps.size() > 2000) {
            wraps.clear();
            wrapsFor = sig;
        }
        Team team = TeamClient.team;
        if (team == null) {
            lobby(g, t);
        } else {
            pw = Math.min(width - 8, W);
            py = Math.max(4, height * 18 / 400);
            ph = Math.min(height - py * 2, H);
            px = (width - pw) / 2;
            panel(g, t, px, py, pw, ph, 7f);
            header(g, t, team);
            int bodyY = py + HEADER, bodyH = ph - HEADER;
            switch (tab) {
                case TEAM -> teamTab(g, t, team, bodyY, bodyH);
                case BOARD -> scrolled(g, bodyY, bodyH, y -> boardTab(g, t, team, y));
                case MARKS -> scrolled(g, bodyY, bodyH, y -> marksTab(g, t, team, y));
                case ALLIES -> scrolled(g, bodyY, bodyH, y -> alliesTab(g, t, y));
                case SETTINGS -> scrolled(g, bodyY, bodyH, y -> settingsTab(g, t, team, y));
            }
            undoBar(g, t);
            if (invitePopup) invitePopup(g, t);
            if (menuFor != null) memberMenu(g, t, team);
        }
        if (tooltip != null) g.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
    }

    private List<String> wrapped(String text, float width, float px_, Weight weight, int lines) {
        String key = text + '\u0001' + width + '\u0001' + px_ + '\u0001' + weight + '\u0001' + lines;
        return wraps.computeIfAbsent(key, k -> Draw.wrapLabel(text, width, px_, weight, lines));
    }

    private interface Content {
        int draw(int y);
    }

    private void scrolled(GuiGraphics g, int y, int h, Content content) {
        bodyScroll = Math.round(bodyScroller.update(Math.max(0, bodyContent - h)));
        hits.clip(y, y + h);
        Draw.scissor(g, px + 1, y, px + pw - 1, y + h - 1);
        bodyContent = content.draw(y - bodyScroll) - (y - bodyScroll);
        g.disableScissor();
        hits.unclip();
    }

    // --- общие элементы ---

    private void panel(GuiGraphics g, Theme t, int x, int y, int w, int h, float radius) {
        if (t.beveled()) {
            t.panel(g, x, y, w, h);
            return;
        }
        if (t.shadow) Draw.shadow(g, x, y, w, h, radius, 1f);
        Draw.roundBox(g, x, y, w, h, radius, t.c("panel.bg"), t.c("panel.border"));
    }

    private boolean hover(int x, int y, int w, int h) {
        return Kit.over(mx, my, x, y, w, h);
    }

    private void caps(GuiGraphics g, Theme t, String text, float x, float cy) {
        Kit.caps(g, t, text, x, cy, 11, 0.44f);
    }

    /** Малая кнопка строки: кегль 11, высота 13 или 14. */
    private int smallButton(GuiGraphics g, Theme t, int right, int y, int h, String label, Runnable action) {
        int w = (int) Math.ceil(Draw.labelWidth(label, 11, Weight.REGULAR)) + 10;
        int x = right - w;
        boolean hot = hover(x, y, w, h);
        Draw.roundBox(g, x, y, w, h, 3.5f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
        Draw.label(g, label, x + 5, y + h / 2f, 11, Weight.REGULAR, t.c("text.primary"));
        hits.add(x, y, w, h, b -> action.run());
        return w;
    }

    /** Кнопка необратимого действия: второе нажатие в течение трёх секунд выполняет его. */
    private void confirm(GuiGraphics g, Theme t, int x, int y, int w, int h, String label, String key, Runnable action) {
        boolean ready = armed.equals(key) && System.currentTimeMillis() < armedUntil;
        boolean hot = hover(x, y, w, h);
        Draw.roundBox(g, x, y, w, h, 4f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
        String shown = ready ? tr("rysteams.confirm") : label;
        Draw.label(g, shown, x + (w - Draw.labelWidth(shown, 12, Weight.REGULAR)) / 2f, y + h / 2f, 12, Weight.REGULAR, ready ? t.c("state.bad") : t.c("text.primary"));
        hits.add(x, y, w, h, b -> {
            if (ready) {
                armed = "";
                action.run();
            } else {
                armed = key;
                armedUntil = System.currentTimeMillis() + 3000;
            }
        });
    }

    private void input(GuiGraphics g, Theme t, Field b, int x, int y, int w, int h) {
        if (t.beveled()) t.input(g, x, y, w, h, b.isFocused());
        else Draw.roundBox(g, x, y, w, h, 4f, t.c("slot.bg"), b.isFocused() ? t.c("accent") : t.c("card.border"));
        b.setX(x + 5);
        b.setY(y + (h - 10) / 2);
        b.setWidth(w - 10);
        b.setVisible(true);
        b.setTextColor(t.c("input.fg") & 0xFFFFFF);
        b.render(g, mx, my, 0);
    }

    static void teamTile(GuiGraphics g, int color, String icon, int x, int y, int size, float radius) {
        Draw.round(g, x, y, size, size, radius, TeamHud.rgb(color));
        ItemStack stack = TeamClient.icon(icon);
        if (stack.isEmpty()) return;
        float inner = size * 0.75f, scale = inner / 16f, pad = (size - inner) / 2f;
        g.pose().pushPose();
        g.pose().translate(x + pad, y + pad, 0);
        g.pose().scale(scale, scale, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    private int chip(GuiGraphics g, String label, float px_, int x, float cy, float hText, int bg, int fg, Weight weight, float padX, float radius) {
        int w = (int) Math.ceil(Draw.labelWidth(label, px_, weight) + padX * 2);
        Draw.round(g, x, cy - hText / 2f, w, hText, radius, bg);
        Draw.label(g, label, x + padX, cy, px_, weight, fg);
        return w;
    }

    private int sourceChip(GuiGraphics g, Theme t, String source, int x, float cy) {
        return chip(g, tr("rysteams.source." + source), 10.5f, x, cy, 8f, t.c("src." + known(source) + ".bg"), sourceFg(t, source), Weight.SEMIBOLD, 3.5f, 2.5f);
    }

    private static String known(String source) {
        for (String s : SOURCES) if (s.equals(source)) return s;
        return "teams";
    }

    private int sourceFg(Theme t, String source) {
        return switch (known(source)) {
            case "quests" -> t.c("accent");
            case "pulse" -> t.c("state.bad");
            case "scope" -> t.c("chip.mod.fg");
            default -> t.c("state.ok");
        };
    }

    private int roleChip(GuiGraphics g, Theme t, Role role, int x, float cy) {
        String label = tr("rysteams.role." + role.key());
        return switch (role) {
            case LEADER -> chip(g, label, 10f, x, cy, 6.5f, t.c("src.quests.bg"), t.c("accent"), Weight.MEDIUM, 3f, 3.25f);
            case OFFICER -> chip(g, label, 10f, x, cy, 6.5f, t.c("chip.mod.bg"), t.c("chip.mod.fg"), Weight.MEDIUM, 3f, 3.25f);
            case MEMBER -> chip(g, label, 10f, x, cy, 6.5f, t.c("button.bg"), t.c("button.fg"), Weight.MEDIUM, 3f, 3.25f);
            case GUEST -> chip(g, label, 10f, x, cy, 6.5f, t.c("button.bg"), t.c("text.muted"), Weight.MEDIUM, 3f, 3.25f);
        };
    }

    /** Значок: рамка, две фигуры и линза. Рисуется по координатам svg из макета (поле 56×56). */
    static void logo(GuiGraphics g, Theme t, int x, int y, float size) {
        float s = size / 56f;
        Draw.roundBox(g, x + 6 * s, y + 6 * s, 44 * s, 44 * s, 8 * s, t.c("slot.bg"), t.c("card.border"));
        int figure = 0xFF5B6273;
        Draw.round(g, x + 14 * s, y + 18 * s, 12 * s, 12 * s, 6 * s, figure);
        Draw.round(g, x + 26 * s, y + 18 * s, 12 * s, 12 * s, 6 * s, figure);
        Draw.round(g, x + 11 * s, y + 31 * s, 18 * s, 9 * s, 4.5f * s, figure);
        Draw.round(g, x + 23 * s, y + 31 * s, 18 * s, 9 * s, 4.5f * s, figure);
        Draw.round(g, x + 27 * s, y + 25 * s, 20 * s, 20 * s, 10 * s, Draw.alpha(t.c("accent"), 0.18f));
        Draw.roundBorder(g, x + 27 * s, y + 25 * s, 20 * s, 20 * s, 10 * s, t.c("accent"), 1.5f * size / 26f);
        for (int i = 0; i < 3; i++) Draw.round(g, x + (44 + i * 2.5f) * s, y + (42 + i * 2.5f) * s, 3.5f * s, 3.5f * s, 1f * s, t.c("accent"));
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
        // верхние углы как у панели, снизу прямой край
        // заливка шапки срезается по её низу: второй слой панели в полупрозрачной теме дал бы полосу
        Draw.scissor(g, px, py, px + pw, py + HEADER);
        Draw.round(g, px + 0.5f, py + 0.5f, pw - 1, HEADER + 14, 6.5f, t.c("sidebar.bg"));
        g.disableScissor();
        Draw.round(g, px + 0.5f, py + HEADER - 0.5f, pw - 1, 0.5f, 0.02f, t.c("panel.border"));
        int x = px + 9;
        logo(g, t, x, py + 9, 13f);
        x += 13 + 5;
        float rys = Draw.labelWidth("RYS", 16, Weight.DISPLAY), teams = Draw.labelWidth("Teams", 16, Weight.DISPLAY);
        if (pw >= 520) {
            Draw.label(g, "RYS", x, py + 16, 16, Weight.DISPLAY, t.c("accent"));
            Draw.label(g, "Teams", x + rys, py + 16, 16, Weight.DISPLAY, t.c("text.primary"));
            x += (int) Math.ceil(rys + teams) + 7;
        }
        if (tab != Tab.SETTINGS) {
            int online = 0;
            for (UUID id : team.members.keySet()) if (isOnline(id)) online++;
            String sub = plural("rysteams.count.members", team.members.size()) + " · " + tr("rysteams.count.online", online);
            float nameW = Math.min(Draw.labelWidth(team.name, 13, Weight.SEMIBOLD), 90);
            int chipW = (int) Math.ceil(3 + 13 + 5 + Math.max(nameW, Draw.labelWidth(sub, 10.5f, Weight.REGULAR)) + 5);
            int chipY = py + 6;
            Draw.roundBox(g, x, chipY, chipW, 19, 4.5f, t.c("button.bg"), t.c("button.border"));
            teamTile(g, team.color, team.icon, x + 3, chipY + 3, 13, 3.5f);
            Draw.label(g, Draw.ellipsize(team.name, 90, 13, Weight.SEMIBOLD), x + 21, chipY + 6.25f, 13, Weight.SEMIBOLD, t.c("text.primary"));
            Draw.label(g, sub, x + 21, chipY + 13.25f, 10.5f, Weight.REGULAR, t.c("text.muted"));
            x += chipW + 10;
        } else {
            x += 3;
        }
        for (Tab each : Tab.values()) {
            String label = tr("rysteams.tab." + each.name().toLowerCase(java.util.Locale.ROOT));
            x += Kit.sectionTab(g, t, hits, x, py + 8, HEADER - 8, label, tab == each, mx, my, () -> {
                tab = each;
                bodyScroller.reset();
                invitePopup = false;
                menuFor = null;
            }) + 2;
        }
        int closeX = px + pw - 9 - 17, closeY = py + 7;
        Kit.iconButton(g, t, closeX, closeY, 17, hover(closeX, closeY, 17, 17));
        Draw.cross(g, closeX + 5, closeY + 5, hover(closeX, closeY, 17, 17) ? t.c("accent") : t.c("button.fg"));
        hits.add(closeX, closeY, 17, 17, b -> onClose());
        int right = closeX - 7;
        if (tab != Tab.SETTINGS && TeamClient.can(Permission.INVITE)) {
            String label = tr("rysteams.invite.button");
            int w = (int) Math.ceil(Draw.labelWidth(label, 12.5f, Weight.SEMIBOLD)) + 12, bx = right - w;
            if (bx > x) {
                boolean hot = hover(bx, closeY, w, 17);
                Draw.round(g, bx, closeY, w, 17, 4.5f, hot ? Draw.mix(t.c("accent"), 0xFFFFFFFF, 0.15f) : t.c("accent"));
                Draw.label(g, label, bx + 6, closeY + 8.5f, 12.5f, Weight.SEMIBOLD, t.c("accent.text"));
                hits.add(bx, closeY, w, 17, b -> {
                    invitePopup = !invitePopup;
                    menuFor = null;
                });
                right = bx - 7;
            }
        }
        if (tab == Tab.SETTINGS) {
            String note = tr("rysteams.settings.note");
            float nw = Draw.labelWidth(note, 11.5f, Weight.REGULAR);
            if (px + pw - 9 - 17 - 7 - nw > x) Draw.label(g, note, px + pw - 9 - 17 - 7 - nw, py + 16, 11.5f, Weight.REGULAR, t.c("text.muted"));
        }
    }

    private void invitePopup(GuiGraphics g, Theme t) {
        List<TeamClient.Online> free = new ArrayList<>();
        for (TeamClient.Online o : TeamClient.online) if (o.free() && !o.id().equals(me())) free.add(o);
        int w = 160, rowH = 20, h = 8 + Math.max(1, free.size()) * rowH + 12;
        int x = px + pw - 9 - w, y = py + HEADER + 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        t.tooltipBox(g, x, y, w, h);
        caps(g, t, tr("rysteams.section.online"), x + 7, y + 8);
        int ry = y + 14;
        if (free.isEmpty()) Draw.label(g, tr("rysteams.online.empty"), x + 7, ry + 8, 12, Weight.REGULAR, t.c("text.dim"));
        for (TeamClient.Online o : free) {
            TeamHud.face(g, o.id(), x + 7, ry + 3, 12);
            String label = tr("rysteams.online.invite");
            int bw = smallButton(g, t, x + w - 6, ry + 3, 14, label, () -> {
                TeamClient.action("invite", "player", o.id());
                invitePopup = false;
            });
            Draw.label(g, Draw.ellipsize(o.name(), w - 34 - bw, 12.5f, Weight.REGULAR), x + 24, ry + 10, 12.5f, Weight.REGULAR, t.c("text.primary"));
            ry += rowH;
        }
        g.pose().popPose();
    }

    private void undoBar(GuiGraphics g, Theme t) {
        long left = (TeamClient.undoUntil - System.currentTimeMillis()) / 1000;
        if (TeamClient.undoUntil == 0 || left < 0 || !TeamClient.can(Permission.MANAGE)) return;
        String text = tr("rysteams.merge.done");
        String label = tr("rysteams.merge.undo", left);
        int bw = (int) Math.ceil(Draw.labelWidth(label, 12, Weight.SEMIBOLD)) + 12;
        int w = (int) Math.ceil(Draw.labelWidth(text, 12, Weight.REGULAR)) + bw + 34;
        int x = px + (pw - w) / 2, y = py + ph - 28;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Draw.roundBox(g, x, y, w, 21, 5f, t.c("status.bg"), t.c("ok.border"));
        Draw.dot(g, x + 6, y + 5, 11, t.c("state.ok"));
        Icons.draw(g, Icons.Shape.CHECK, x + 7.5f, y + 6.5f, 8f, t.c("accent.text"));
        Draw.label(g, text, x + 22, y + 10.5f, 12, Weight.REGULAR, t.c("button.fg"));
        int bx = x + w - bw - 5;
        Draw.roundBox(g, bx, y + 3, bw, 15, 4f, t.c("button.bg"), t.c("button.border"));
        Draw.label(g, label, bx + 6, y + 10.5f, 12, Weight.SEMIBOLD, t.c("text.primary"));
        hits.add(bx, y + 3, bw, 15, b -> TeamClient.action("merge_undo"));
        g.pose().popPose();
    }

    // --- вкладка команды ---

    private void column(GuiGraphics g, Theme t, int x, int y, int w, int h, boolean leftSide) {
        // нижний наружный угол скруглён вместе с панелью
        Draw.round(g, x, y, w, h, 7f, t.c("column.bg"));
        Draw.rect(g, x, y, w, h - 7, t.c("column.bg"));
        Draw.rect(g, leftSide ? x + 7 : x, y, w - 7, h, t.c("column.bg"));
        Draw.round(g, leftSide ? x + w - 0.5f : x, y, 0.5f, h, 0.02f, t.c("panel.border"));
    }

    private void teamTab(GuiGraphics g, Theme t, Team team, int y, int h) {
        boolean narrow = pw < 520;
        int leftW = narrow ? 118 : LEFT, rightW = narrow ? 100 : RIGHT;
        column(g, t, px + 1, y, leftW, h - 1, true);
        column(g, t, px + pw - rightW - 1, y, rightW, h - 1, false);
        members(g, t, team, px + 1 + 8, y + 8, leftW - 16, h - 16);
        feed(g, t, team, px + 1 + leftW + 9, y + 8, pw - 2 - leftW - rightW - 18, h - 16);
        summary(g, t, px + pw - rightW - 1 + 8, y + 8, rightW - 16, h - 16);
    }

    private void members(GuiGraphics g, Theme t, Team team, int x, int y, int w, int h) {
        caps(g, t, tr("rysteams.section.members"), x, y + 3.5f);
        String count = Integer.toString(team.members.size());
        Kit.caps(g, t, count, x + w - Draw.labelWidth(count, 11, Weight.MEDIUM) - 0.44f, y + 3.5f, 11, 0.44f);
        int cy = y + 11, bottom = y + h;
        boolean requests = !team.requests.isEmpty() && TeamClient.can(Permission.ACCEPT_REQUESTS);
        if (requests) bottom -= 35;
        for (Team.Member m : team.members.values()) {
            if (cy + 30 > bottom) break;
            memberCard(g, t, team, m, x, cy, w);
            cy += 34;
        }
        if (requests) requests(g, t, team, x, y + h - 31, w);
    }

    private void memberCard(GuiGraphics g, Theme t, Team team, Team.Member m, int x, int y, int w) {
        boolean online = isOnline(m.id);
        float dim = online ? 1f : 0.6f;
        Draw.roundBox(g, x, y, w, 30, 5f, t.c("card.bg"), t.c("panel.border"));
        int ax = x + 5, ay = y + 5;
        Draw.roundBorder(g, ax, ay, 19, 19, 4f, Draw.alpha(TeamHud.rgb(team.color), dim), 1f);
        TeamHud.face(g, m.id, ax + 1, ay + 1, 17);
        if (!online) Draw.rect(g, ax + 1, ay + 1, 17, 17, Draw.alpha(t.c("card.bg"), 0.4f));
        Draw.dot(g, ax + 14, ay + 14, 7, t.c("card.bg"));
        Draw.dot(g, ax + 15, ay + 15, 5, t.c(online ? "state.ok" : "text.dim"));
        int nameX = ax + 19 + 5;
        float avail = x + w - 12 - nameX;
        float roleW = Draw.labelWidth(tr("rysteams.role." + m.role.key()), 10, Weight.MEDIUM) + 6 + 3;
        String name = Draw.ellipsize(m.name, avail - roleW, 13, Weight.SEMIBOLD);
        Draw.label(g, name, nameX, y + 10.5f, 13, Weight.SEMIBOLD, Draw.alpha(t.c("text.primary"), dim));
        roleChip(g, t, m.role, (int) (nameX + Draw.labelWidth(name, 13, Weight.SEMIBOLD) + 3), y + 10.5f);
        TeamClient.Pres p = TeamClient.PRESENCE.get(m.id);
        String line;
        if (m.id.equals(me())) {
            line = tr("rysteams.status.you");
        } else if (p == null) {
            line = tr("rysteams.status.offline_for", ago(m.lastSeen));
        } else {
            line = TeamHud.dimensionName(p.dimension());
            if (p.hasPos()) line += " · " + p.x() + " " + p.y() + " " + p.z();
            if (!p.status().isEmpty()) line += " · " + p.status();
        }
        Draw.label(g, Draw.ellipsize(line, x + w - 12 - nameX, 11, Weight.REGULAR), nameX, y + 20f, 11, Weight.REGULAR, t.c("text.muted"));
        if (TeamClient.can(Permission.MANAGE) && !m.id.equals(me())) {
            int dx = x + w - 11;
            int dots = hover(dx - 2, y + 8, 12, 14) ? t.c("accent") : t.c("text.dim");
            for (int i = 0; i < 3; i++) Draw.dot(g, dx + i * 3, y + 15, 1, dots);
            hits.add(dx - 3, y, 14, 30, b -> {
                menuFor = m.id.equals(menuFor) ? null : m.id;
                invitePopup = false;
                menuX = dx + 8;
                menuY = y + 8;
            });
        }
    }

    private int menuX, menuY;

    private void memberMenu(GuiGraphics g, Theme t, Team team) {
        Team.Member m = team.members.get(menuFor);
        if (m == null) {
            menuFor = null;
            return;
        }
        int w = 110, rowH = 15;
        List<Role> roles = new ArrayList<>();
        for (Role r : new Role[] {Role.OFFICER, Role.MEMBER, Role.GUEST}) if (r != m.role) roles.add(r);
        // в режиме server сервер не исключает, а делает гостем: для этого уже есть пункт «Гость»
        boolean canKick = !TeamClient.serverMode;
        int h = 6 + roles.size() * rowH + (canKick ? 2 : 1) * rowH + 2;
        int x = Math.min(menuX, px + pw - w - 4), y = Math.min(menuY, py + ph - h - 4);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        t.tooltipBox(g, x, y, w, h);
        int ry = y + 3;
        for (Role role : roles) {
            menuRow(g, t, x, ry, w, rowH, tr("rysteams.role." + role.key()), t.c("text.body"), () -> {
                CompoundTag a = new CompoundTag();
                a.putUUID("player", m.id);
                a.putString("role", role.key());
                TeamClient.action("set_role", a);
                menuFor = null;
            });
            ry += rowH;
        }
        boolean transferReady = armed.equals("transfer") && System.currentTimeMillis() < armedUntil;
        boolean kickReady = armed.equals("kick") && System.currentTimeMillis() < armedUntil;
        menuRow(g, t, x, ry, w, rowH, transferReady ? tr("rysteams.confirm") : tr("rysteams.action.transfer"), t.c("text.body"), () -> arm("transfer", transferReady, () -> TeamClient.action("transfer", "player", m.id)));
        ry += rowH;
        if (canKick) menuRow(g, t, x, ry, w, rowH, kickReady ? tr("rysteams.confirm") : tr("rysteams.action.kick"), t.c("state.bad"), () -> arm("kick", kickReady, () -> TeamClient.action("kick", "player", m.id)));
        g.pose().popPose();
    }

    private void arm(String key, boolean ready, Runnable action) {
        if (ready) {
            armed = "";
            action.run();
            menuFor = null;
        } else {
            armed = key;
            armedUntil = System.currentTimeMillis() + 3000;
        }
    }

    private void menuRow(GuiGraphics g, Theme t, int x, int y, int w, int h, String label, int color, Runnable action) {
        if (hover(x + 2, y, w - 4, h)) Draw.round(g, x + 2, y, w - 4, h, 3f, Draw.alpha(t.c("slot.hover"), 0.7f));
        Draw.label(g, label, x + 7, y + h / 2f, 12, Weight.REGULAR, color);
        hits.add(x + 2, y, w - 4, h, b -> action.run());
    }

    private void requests(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        Draw.roundBox(g, x, y, w, 31, 5f, t.c("slot.bg"), t.c("slot.border"));
        Draw.label(g, tr("rysteams.section.requests", team.requests.size()), x + 6, y + 9, 11, Weight.REGULAR, t.c("text.muted"));
        var first = team.requests.entrySet().iterator().next();
        TeamHud.face(g, first.getKey(), x + 6, y + 16, 9);
        Draw.label(g, Draw.ellipsize(first.getValue(), w - 56, 12, Weight.REGULAR), x + 19, y + 20.5f, 12, Weight.REGULAR, t.c("button.fg"));
        Kit.button(g, t, hits, x + w - 37, y + 14, 15, 13, "", true, mx, my, () -> TeamClient.action("accept_request", "player", first.getKey()));
        Icons.draw(g, Icons.Shape.CHECK, x + w - 33, y + 16.5f, 8f, t.c("accent.text"));
        Kit.button(g, t, hits, x + w - 20, y + 14, 15, 13, "", false, mx, my, () -> TeamClient.action("decline_request", "player", first.getKey()));
        Icons.draw(g, Icons.Shape.CROSS, x + w - 16, y + 16.5f, 8f, t.c("button.fg"));
    }

    private void feed(GuiGraphics g, Theme t, Team team, int x, int y, int w, int h) {
        caps(g, t, tr("rysteams.section.feed"), x, y + 5);
        int cx = x + w;
        for (int i = SOURCES.length - 1; i >= -1; i--) {
            String source = i < 0 ? "" : SOURCES[i];
            String label = i < 0 ? tr("rysteams.filter.all") : tr("rysteams.source." + source);
            int cw = (int) Math.ceil(Draw.labelWidth(label, 11, Weight.REGULAR)) + 9 + (i < 0 ? 0 : 1);
            cx -= cw;
            boolean active = filter.equals(source);
            if (i < 0 || active) Draw.round(g, cx, y, cw, 10, 5f, active ? t.c("button.bg") : 0);
            else Draw.roundBorder(g, cx, y, cw, 10, 5f, t.c("src." + source + ".border"));
            if (i >= 0 && active) Draw.roundBorder(g, cx, y, cw, 10, 5f, t.c("src." + source + ".border"));
            Draw.label(g, label, cx + cw / 2f - Draw.labelWidth(label, 11, Weight.REGULAR) / 2f, y + 5, 11, Weight.REGULAR, i < 0 ? t.c("text.primary") : sourceFg(t, source));
            hits.add(cx, y, cw, 10, b -> {
                filter = source;
                feedScroller.reset();
            });
            cx -= 2;
        }
        if (team != missedFor) {
            // новое состояние приходит новым экземпляром команды
            missedFor = team;
            Team.Member self = team.members.get(me());
            long since = self == null ? 0 : self.lastSeen;
            List<String> list = new ArrayList<>();
            if (since > 0) {
                for (Team.Feed f : team.feed) {
                    if (f.time() <= since) break;
                    list.add(TeamClient.text(f.text()).getString());
                }
            }
            missed = list;
        }
        int summaryH = missed.isEmpty() ? 0 : 26;
        int top = y + 15, listH = h - 15 - (summaryH > 0 ? summaryH + 5 : 0);
        feedScroll = Math.round(feedScroller.update(Math.max(0, feedContent - listH)));
        hits.clip(top, top + listH);
        Draw.scissor(g, x, top, x + w, top + listH);
        int ry = top - feedScroll;
        for (Team.Feed f : team.feed) {
            if (!filter.isEmpty() && !filter.equals(known(f.source()))) continue;
            ry += feedRow(g, t, f, x, ry, w, top, top + listH) + 3;
        }
        if (team.feed.isEmpty()) Draw.label(g, tr("rysteams.feed.empty"), x, top + 6, 12.5f, Weight.REGULAR, t.c("text.dim"));
        feedContent = ry + feedScroll - top;
        g.disableScissor();
        hits.unclip();
        if (summaryH > 0) {
            int sy = y + h - summaryH;
            Draw.roundBox(g, x, sy, w, summaryH, 5f, t.c("status.bg"), t.c("status.border"));
            int headW = 62;
            List<String> head = Draw.wrapLabel(tr("rysteams.feed.missed", missed.size()), headW, 12, Weight.REGULAR, 2);
            List<String> tail = Draw.wrapLabel(String.join(", ", missed.subList(0, Math.min(3, missed.size()))), w - 12 - headW - 6, 12, Weight.REGULAR, 2);
            float ly = sy + 13 - (head.size() - 1) * 4f;
            for (String line : head) {
                Draw.label(g, line, x + 6, ly, 12, Weight.REGULAR, t.c("button.fg"));
                ly += 8;
            }
            ly = sy + 13 - (tail.size() - 1) * 4f;
            for (String line : tail) {
                Draw.label(g, line, x + 6 + headW + 6, ly, 12, Weight.REGULAR, t.c("text.muted"));
                ly += 8;
            }
        }
    }

    private int feedRow(GuiGraphics g, Theme t, Team.Feed f, int x, int y, int w, int top, int bottom) {
        boolean action = f.action().startsWith("waypoint:");
        String actionLabel = tr("rysteams.feed.waypoint");
        int actionW = action ? (int) Math.ceil(Draw.labelWidth(actionLabel, 11, Weight.REGULAR)) + 10 + 5 : 0;
        String src = known(f.source());
        int chipW = (int) Math.ceil(Draw.labelWidth(tr("rysteams.source." + src), 10.5f, Weight.SEMIBOLD) + 7);
        int textX = x + 6 + 19 + 5 + chipW + 5 + 10 + 5;
        int textW = x + w - 6 - actionW - textX;
        List<String> lines = wrapped(TeamClient.text(f.text()).getString(), textW, 12.5f, Weight.REGULAR, 2);
        int h = lines.size() > 1 ? 25 : 23;
        if (y + h < top || y > bottom) return h;
        Draw.roundBox(g, x, y, w, h, 5f, t.c("card.bg"), f.alert() ? t.c("src.pulse.border") : t.c("panel.border"));
        float cy = y + h / 2f;
        Instant at = Instant.ofEpochMilli(f.time());
        LocalDate date = at.atZone(ZoneId.systemDefault()).toLocalDate();
        String stamp = date.equals(LocalDate.now()) ? TIME.withZone(ZoneId.systemDefault()).format(at)
            : date.equals(LocalDate.now().minusDays(1)) ? tr("rysteams.time.yesterday") : date.getDayOfMonth() + "." + date.getMonthValue();
        Draw.label(g, stamp, x + 6, cy, 11, Weight.REGULAR, t.c("text.dim"));
        sourceChip(g, t, src, x + 6 + 19 + 5, cy);
        int iconX = x + 6 + 19 + 5 + chipW + 5;
        ItemStack icon = f.icon().isEmpty() ? ItemStack.EMPTY : TeamClient.icon(f.icon());
        Draw.round(g, iconX, cy - 5, 10, 10, 2f, Draw.alpha(t.c("slot.bg"), 0.8f));
        if (!icon.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(iconX + 1, cy - 4, 0);
            g.pose().scale(0.5f, 0.5f, 1f);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        float ly = cy - (lines.size() - 1) * 3.75f;
        for (String line : lines) {
            Draw.label(g, line, textX, ly, 12.5f, Weight.REGULAR, t.c("text.body"));
            ly += 7.5f;
        }
        if (action) smallButton(g, t, x + w - 6, (int) cy - 7, 13, actionLabel, () -> setWaypoint(f.action()));
        return h;
    }

    /** Формат: waypoint:пространство:имя:x:y:z. Имя измерения само содержит двоеточие, поэтому разбор с конца. */
    private void setWaypoint(String action) {
        String[] parts = action.split(":");
        if (parts.length < 6) return;
        int n = parts.length;
        String dim = String.join(":", java.util.Arrays.copyOfRange(parts, 1, n - 3));
        int color = TeamClient.team == null ? 0xFFFFFF : TeamClient.team.color;
        TeamClient.waypoint = new TeamClient.Waypoint(tr("rysteams.feed.waypoint"), color, dim,
            Integer.parseInt(parts[n - 3]), Integer.parseInt(parts[n - 2]), Integer.parseInt(parts[n - 1]), System.currentTimeMillis() + 120_000);
        onClose();
    }

    private void summary(GuiGraphics g, Theme t, int x, int y, int w, int h) {
        caps(g, t, tr("rysteams.section.summary"), x, y + 3.5f);
        int ry = y + 12;
        for (TeamCards.Detailed detailed : TeamCards.currentDetailed()) {
            TeamCards.Card card = detailed.card();
            List<String> lines = new ArrayList<>();
            for (Component line : card.lines()) lines.addAll(Draw.wrapLabel(line.getString(), w - 12, 11.5f, Weight.REGULAR, 3));
            boolean bar = detailed.progress() >= 0;
            int cardH = 6 + 8 + (bar ? 4 + 3 : 0) + 4 + lines.size() * 7 + (card.button() != null ? 4 + 14 : 0) + 6;
            String src = known(card.source().getPath());
            Draw.roundBox(g, x, ry, w, cardH, 5f, t.c("card.bg"), t.c(card.alert() ? "src.pulse.border" : "src." + src + ".border"));
            String title = card.title().getString();
            float tx = x + 6, cy = ry + 10;
            if (title.startsWith("RYS")) {
                Draw.label(g, "RYS", tx, cy, 12.5f, Weight.SEMIBOLD, t.c("accent"));
                tx += Draw.labelWidth("RYS", 12.5f, Weight.SEMIBOLD);
                title = title.substring(3);
            }
            Draw.label(g, title, tx, cy, 12.5f, Weight.SEMIBOLD, t.c("text.primary"));
            if (detailed.tag() != null) {
                String tag = detailed.tag().getString();
                float tagW = Draw.labelWidth(tag, 11, Weight.REGULAR);
                if (card.alert()) {
                    float fw = Draw.labelWidth(tag, 10.5f, Weight.SEMIBOLD) + 7;
                    Draw.round(g, x + w - 6 - fw, cy - 4, fw, 8, 4f, t.c("src.pulse.bg"));
                    Draw.label(g, tag, x + w - 6 - fw + 3.5f, cy, 10.5f, Weight.SEMIBOLD, t.c("state.bad"));
                } else {
                    Draw.label(g, tag, x + w - 6 - tagW, cy, 11, Weight.REGULAR, t.c("text.muted"));
                }
            }
            int lineTop = ry + 6 + 8 + 4;
            if (bar) {
                Draw.round(g, x + 6, lineTop, w - 12, 2.5f, 1.25f, t.c("slot.bg"));
                Draw.round(g, x + 6, lineTop, Math.max(2f, (w - 12) * Math.min(1f, detailed.progress())), 2.5f, 1.25f, t.c("accent"));
                lineTop += 3 + 4;
            }
            float ly = lineTop + 3.5f;
            for (String line : lines) {
                Draw.label(g, line, x + 6, ly, 11.5f, Weight.REGULAR, t.c("button.fg"));
                ly += 7;
            }
            if (card.button() != null && card.action() != null) {
                String label = card.button().getString();
                int bw = (int) Math.ceil(Draw.labelWidth(label, 11, Weight.REGULAR)) + 10, by = ry + cardH - 6 - 14;
                boolean hot = hover(x + 6, by, bw, 14);
                Draw.roundBox(g, x + 6, by, bw, 14, 3.5f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
                Draw.label(g, label, x + 11, by + 7, 11, Weight.REGULAR, t.c("text.primary"));
                hits.add(x + 6, by, bw, 14, b -> card.action().run());
            }
            ry += cardH + 5;
        }
        String pingKey = ClientEntry.keyName(ClientEntry.PING);
        String keys = tr("rysteams.help.keys", ClientEntry.OPEN.getTranslatedKeyMessage().getString()) + (pingKey == null ? "" : " · " + tr("rysteams.hud.ping", pingKey));
        List<String> hint = Draw.wrapLabel(keys, w, 11, Weight.REGULAR, 3);
        float hy = y + h - (hint.size() - 1) * 8.25f - 4;
        for (String line : hint) {
            Draw.label(g, line, x, hy, 11, Weight.REGULAR, t.c("text.dim"));
            hy += 8.25f;
        }
    }

    // --- доска ---

    private int boardTab(GuiGraphics g, Theme t, Team team, int y) {
        int inset = 10, gap = 8;
        int w = (pw - inset * 2 - gap) / 2, left = px + inset, right = left + w + gap;
        int top = y + 9;
        boolean edit = TeamClient.can(Permission.EDIT_BOARD);
        heading(g, t, tr("rysteams.section.notes"), "", left, w, top);
        int ly = top + 12;
        if (edit) {
            String add = tr("rysteams.action.add");
            int bw = (int) Math.ceil(Kit.buttonWidth(add, 13));
            input(g, t, noteBox, left, ly, w - bw - 4, 17);
            Kit.button(g, t, hits, left + w - bw, ly, bw, 17, add, true, mx, my, () -> addText("note_add", noteBox));
            ly += 22;
        }
        for (Team.Note n : team.notes) {
            List<String> lines = wrapped(n.text, w - 26, 12.5f, Weight.REGULAR, 8);
            int h = lines.size() * 8 + 18;
            Draw.roundBox(g, left, ly, w, h, 5f, t.c("card.bg"), t.c("panel.border"));
            float ty = ly + 8;
            for (String line : lines) {
                Draw.label(g, line, left + 7, ty, 12.5f, Weight.REGULAR, t.c("text.body"));
                ty += 8;
            }
            Draw.label(g, n.by + " · " + ago(n.time), left + 7, ty + 1, 11, Weight.REGULAR, t.c("text.dim"));
            if (edit) smallDelete(g, t, left + w - 13, ly + 3, () -> byId("note_del", n.id));
            ly += h + 3;
        }
        if (team.notes.isEmpty()) Draw.label(g, tr("rysteams.empty.notes"), left, ly + 6, 12, Weight.REGULAR, t.c("text.dim"));

        heading(g, t, tr("rysteams.section.todos"), "", right, w, top);
        int ry = top + 12;
        if (edit) {
            String add = tr("rysteams.action.add");
            int bw = (int) Math.ceil(Kit.buttonWidth(add, 13));
            input(g, t, todoBox, right, ry, w - bw - 4, 17);
            Kit.button(g, t, hits, right + w - bw, ry, bw, 17, add, true, mx, my, () -> addText("todo_add", todoBox));
            ry += 22;
        }
        String self = Minecraft.getInstance().player.getGameProfile().getName();
        for (Team.Todo x : team.todos) {
            Draw.roundBox(g, right, ry, w, 20, 5f, t.c("card.bg"), t.c("panel.border"));
            int box = right + 6;
            Draw.roundBox(g, box, ry + 4, 12, 12, 3f, x.done ? t.c("state.ok") : t.c("slot.bg"), x.done ? 0 : t.c("card.border"));
            if (x.done) Icons.draw(g, Icons.Shape.CHECK, box + 2, ry + 6, 8f, t.c("accent.text"));
            if (edit) hits.add(box, ry + 2, 16, 16, b -> byId("todo_toggle", x.id));
            float rightEdge = right + w - 18;
            if (!x.assignee.isEmpty()) {
                float aw = Draw.labelWidth(x.assignee, 11, Weight.REGULAR);
                Draw.label(g, x.assignee, rightEdge - aw, ry + 10, 11, Weight.REGULAR, x.assignee.equals(self) ? t.c("accent") : t.c("text.muted"));
                rightEdge -= aw + 6;
            } else if (edit) {
                String take = tr("rysteams.action.take");
                float tw = Draw.labelWidth(take, 11, Weight.REGULAR);
                int tx = (int) (rightEdge - tw);
                Draw.label(g, take, tx, ry + 10, 11, Weight.REGULAR, hover(tx, ry + 4, (int) tw, 12) ? t.c("accent") : t.c("text.dim"));
                hits.add(tx, ry + 4, (int) tw + 1, 12, b -> byId("todo_assign", x.id));
                rightEdge -= tw + 6;
            }
            Draw.label(g, Draw.ellipsize(x.text, rightEdge - box - 16, 12.5f, Weight.REGULAR), box + 16, ry + 10, 12.5f, Weight.REGULAR, x.done ? t.c("text.dim") : t.c("text.body"));
            if (edit) smallDelete(g, t, right + w - 14, ry + 5, () -> byId("todo_del", x.id));
            ry += 23;
        }
        if (team.todos.isEmpty()) Draw.label(g, tr("rysteams.empty.todos"), right, ry + 6, 12, Weight.REGULAR, t.c("text.dim"));
        return Math.max(ly, ry) + 10;
    }

    private void heading(GuiGraphics g, Theme t, String title, String note, int x, int w, int y) {
        Draw.label(g, title, x, y + 3, 16, Weight.DISPLAY, t.c("text.primary"));
        if (!note.isEmpty()) {
            float nw = Draw.labelWidth(note, 11.5f, Weight.REGULAR);
            Draw.label(g, note, x + w - nw, y + 3, 11.5f, Weight.REGULAR, t.c("text.muted"));
        }
    }

    private void addText(String action, Field box) {
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
        smallDelete(g, t, x, y, false, action);
    }

    private void smallDelete(GuiGraphics g, Theme t, int x, int y, boolean armedNow, Runnable action) {
        Icons.draw(g, Icons.Shape.CROSS, x, y, 9f, armedNow || hover(x, y, 11, 11) ? t.c("state.bad") : t.c("text.dim"));
        hits.add(x, y, 11, 11, b -> action.run());
    }

    // --- метки ---

    private int marksTab(GuiGraphics g, Theme t, Team team, int y) {
        int x = px + 10, w = pw - 20, top = y + 9;
        boolean edit = TeamClient.can(Permission.EDIT_MARKS);
        heading(g, t, tr("rysteams.section.marks"), "", x, w, top);
        int ry = top + 12;
        if (edit) {
            String add = tr("rysteams.mark.add");
            int bw = (int) Math.ceil(Kit.buttonWidth(add, 13));
            input(g, t, markBox, x, ry, w - bw - 4, 17);
            Kit.button(g, t, hits, x + w - bw, ry, bw, 17, add, true, mx, my, () -> {
                String name = markBox.getValue().strip();
                if (name.isEmpty()) return;
                CompoundTag a = new CompoundTag();
                a.putString("name", name);
                TeamClient.action("mark_add", a);
                markBox.setValue("");
            });
            ry += 22;
        }
        String self = Minecraft.getInstance().player.getGameProfile().getName();
        for (Team.Mark m : team.marks) {
            boolean del = edit || m.by().equals(self);
            String key = "mark:" + m.id();
            boolean ready = armed.equals(key) && System.currentTimeMillis() < armedUntil;
            Draw.roundBox(g, x, ry, w, 24, 5f, t.c("card.bg"), m.death() ? t.c("state.bad") : t.c("panel.border"));
            ItemStack icon = TeamClient.icon(m.icon());
            if (!icon.isEmpty()) g.renderItem(icon, x + 5, ry + 4);
            String title = TeamHud.markTitle(m);
            Draw.label(g, Draw.ellipsize(title, w / 2f, 13, Weight.SEMIBOLD), x + 26, ry + 8, 13, Weight.SEMIBOLD, t.c("text.primary"));
            String place = TeamHud.dimensionName(m.dimension()) + " · " + m.x() + " " + m.y() + " " + m.z() + " · " + m.by();
            Draw.label(g, place, x + 26, ry + 17, 11, Weight.REGULAR, t.c("text.muted"));
            String sure = tr("rysteams.confirm");
            int right = x + w - 5 - (del ? 14 : 0) - (ready ? (int) Math.ceil(Draw.labelWidth(sure, 11, Weight.REGULAR)) + 4 : 0);
            smallButton(g, t, right, ry + 5, 14, tr("rysteams.feed.waypoint"), () -> {
                TeamClient.waypoint = new TeamClient.Waypoint(title, team.color, m.dimension(), m.x(), m.y(), m.z(), System.currentTimeMillis() + 120_000);
                Minecraft.getInstance().setScreen(null);
            });
            if (del) {
                if (ready) Draw.label(g, sure, x + w - 16 - Draw.labelWidth(sure, 11, Weight.REGULAR), ry + 12, 11, Weight.REGULAR, t.c("state.bad"));
                smallDelete(g, t, x + w - 14, ry + 7, ready, () -> arm(key, ready, () -> byId("mark_del", m.id())));
            }
            ry += 27;
        }
        if (team.marks.isEmpty()) Draw.label(g, tr("rysteams.empty.marks"), x, ry + 6, 12, Weight.REGULAR, t.c("text.dim"));
        return ry + 10;
    }

    // --- союзы ---

    private int alliesTab(GuiGraphics g, Theme t, int y) {
        int w = (pw - 28) / 2, left = px + 10, right = left + w + 8, top = y + 9;
        boolean manage = TeamClient.can(Permission.MANAGE);
        heading(g, t, tr("rysteams.section.allies"), "", left, w, top);
        int ly = top + 12;
        for (TeamClient.Info offer : TeamClient.allyOffers) {
            teamRow(g, t, offer, left, ly, w, tr("rysteams.ally.offered"));
            if (manage) {
                Kit.button(g, t, hits, left + w - 36, ly + 6, 15, 14, "", true, mx, my, () -> TeamClient.action("ally_accept", "team", offer.id()));
                Icons.draw(g, Icons.Shape.CHECK, left + w - 32.5f, ly + 9, 8f, t.c("accent.text"));
                Kit.button(g, t, hits, left + w - 19, ly + 6, 15, 14, "", false, mx, my, () -> TeamClient.action("ally_decline", "team", offer.id()));
                Icons.draw(g, Icons.Shape.CROSS, left + w - 15.5f, ly + 9, 8f, t.c("button.fg"));
            }
            ly += 29;
        }
        for (TeamClient.Info ally : TeamClient.allies) {
            teamRow(g, t, ally, left, ly, w, plural("rysteams.count.members", ally.count()));
            if (manage) {
                String label = tr("rysteams.ally.remove");
                int bw = (int) Math.ceil(Kit.buttonWidth(label, 12));
                confirm(g, t, left + w - bw - 5, ly + 6, bw, 14, label, "ally:" + ally.id(), () -> TeamClient.action("ally_remove", "team", ally.id()));
            }
            ly += 29;
        }
        if (TeamClient.allies.isEmpty() && TeamClient.allyOffers.isEmpty()) Draw.label(g, tr("rysteams.empty.allies"), left, ly + 6, 12, Weight.REGULAR, t.c("text.dim"));
        heading(g, t, tr("rysteams.section.others"), "", right, w, top);
        int ry = top + 12;
        for (TeamClient.Info other : TeamClient.teams) {
            if (isAlly(other.id())) continue;
            teamRow(g, t, other, right, ry, w, plural("rysteams.count.members", other.count()));
            if (manage) smallButton(g, t, right + w - 5, ry + 6, 14, tr("rysteams.ally.offer"), () -> TeamClient.action("ally_offer", "team", other.id()));
            ry += 29;
        }
        return Math.max(ly, ry) + 10;
    }

    private boolean isAlly(UUID id) {
        for (TeamClient.Info a : TeamClient.allies) if (a.id().equals(id)) return true;
        return false;
    }

    private void teamRow(GuiGraphics g, Theme t, TeamClient.Info info, int x, int y, int w, String sub) {
        Draw.roundBox(g, x, y, w, 26, 6f, t.c("card.bg"), t.c("panel.border"));
        teamTile(g, info.color(), info.icon(), x + 5, y + 4, 18, 4.5f);
        Draw.label(g, Draw.ellipsize(info.name(), w - 90, 13, Weight.SEMIBOLD), x + 28, y + 9, 13, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, sub, x + 28, y + 18, 11, Weight.REGULAR, t.c("text.muted"));
    }

    // --- настройки ---

    private int settingsTab(GuiGraphics g, Theme t, Team team, int y) {
        int total = pw - 20 - 8, leftW = total * 11 / 21;
        int left = px + 10, right = left + leftW + 8, rightW = total - leftW, top = y + 9;
        int ly = permissions(g, t, team, left, top, leftW);
        ly = rules(g, t, team, left, ly + 5, leftW);
        int ry = mergeBlock(g, t, team, right, top, rightW);
        ry = identity(g, t, team, right, ry + 8, rightW);
        ry = danger(g, t, right, ry + 6, rightW);
        return Math.max(ly, ry) + 10;
    }

    private int permissions(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        boolean manage = TeamClient.can(Permission.MANAGE);
        heading(g, t, tr("rysteams.section.perms"), tr("rysteams.perm.click"), x, w, y);
        int cardY = y + 12, inner = w - 14, cellW = 38, gap = 3;
        // свои права и права чужих модов (TeamsApi.registerPermission): строка мода подписана самим модом
        record PermRow(String key, String label, boolean locked, java.util.function.Predicate<Role> on) {}
        List<PermRow> list = new ArrayList<>();
        for (Permission p : Permission.values()) list.add(new PermRow(p.key(), tr("rysteams.perm." + p.key()), p.locked(), role -> team.can(role, p)));
        for (works.repka.ryscore.team.TeamsApi.ModPermission mp : works.repka.ryscore.team.TeamsApi.permissions()) {
            list.add(new PermRow(mp.id(), mp.label().getString(), false, role -> team.modRoles(mp.id()).contains(role)));
        }
        int rows = list.size();
        int cardH = 7 + 13 + rows * 19 + (rows - 1) * 2 + 4 + 16 + 7;
        Draw.roundBox(g, x, cardY, w, cardH, 6f, t.c("card.bg"), t.c("panel.border"));
        int gridX = x + 7 + 3, colsRight = x + 7 + inner - 3;
        float headY = cardY + 7 + 5;
        Kit.caps(g, t, tr("rysteams.perm.right"), gridX, headY, 10.5f, 0.32f);
        for (Role role : Role.values()) {
            int cx = colsRight - (Role.values().length - role.ordinal()) * cellW - (Role.values().length - role.ordinal() - 1) * gap;
            String label = tr("rysteams.role." + role.key() + ".title");
            int color = switch (role) {
                case LEADER -> t.c("accent");
                case OFFICER -> t.c("chip.mod.fg");
                case MEMBER -> t.c("button.fg");
                case GUEST -> t.c("text.dim");
            };
            float lw = Kit.capsWidth(label, 10.5f, 0.32f);
            Kit.capsColored(g, label, cx + (cellW - lw) / 2f, headY, 10.5f, 0.32f, color);
        }
        int ry = cardY + 7 + 13;
        int index = 0;
        for (PermRow p : list) {
            // как в макете: чередуются строки начиная с третьей
            if (index % 2 == 0 && index >= 2) Draw.round(g, x + 7, ry, inner, 19, 4f, t.c("row.alt"));
            index++;
            float cy = ry + 9.5f;
            Draw.label(g, Draw.ellipsize(p.label(), colsRight - 4 * cellW - 3 * gap - gridX - 3, 12.5f, Weight.REGULAR), gridX, cy, 12.5f, Weight.REGULAR, t.c("text.body"));
            for (Role role : Role.values()) {
                int cx = colsRight - (Role.values().length - role.ordinal()) * cellW - (Role.values().length - role.ordinal() - 1) * gap + (cellW - 12) / 2;
                int cellY = ry + 3;
                boolean on = p.on().test(role);
                boolean locked = p.locked() || role == Role.LEADER;
                if (p.locked() && on) {
                    Draw.roundBox(g, cx, cellY, 12, 12, 3f, t.c("cell.mark.border"), t.c("card.border"));
                    Draw.lock(g, cx + 3, cellY + 3, t.c("state.warn"));
                } else {
                    Draw.roundBox(g, cx, cellY, 12, 12, 3f, on ? t.c("state.ok") : t.c("slot.bg"), t.c("card.border"));
                    if (on) Icons.draw(g, Icons.Shape.CHECK, cx + 2, cellY + 2, 8f, t.c("accent.text"));
                }
                if (manage && !locked) {
                    hits.add(cx - 2, cellY - 1, 16, 14, b -> {
                        CompoundTag a = new CompoundTag();
                        a.putString("perm", p.key());
                        a.putString("role", role.key());
                        TeamClient.action("set_perm", a);
                    });
                }
            }
            ry += 21;
        }
        int by = cardY + cardH - 7 - 16;
        if (manage) {
            String reset = tr("rysteams.perm.reset");
            int bw = (int) Math.ceil(Kit.buttonWidth(reset, 12));
            Kit.button(g, t, hits, x + 7, by, bw, 16, reset, false, mx, my, () -> TeamClient.action("reset_perms"));
        }
        String note = tr("rysteams.perm.locked_note");
        float nw = Draw.labelWidth(note, 11, Weight.REGULAR);
        Draw.label(g, note, x + w - 7 - nw, by + 8, 11, Weight.REGULAR, t.c("text.muted"));
        return cardY + cardH;
    }

    private int rules(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        String days = team.autoTransferDays == 0 ? tr("rysteams.rule.never") : tr("rysteams.rule.days", team.autoTransferDays);
        String[][] rows = {
            {"join", tr("rysteams.rule.join"), tr("rysteams.join." + team.join.name().toLowerCase(java.util.Locale.ROOT))},
            {"keep", tr("rysteams.rule.keep"), tr(team.keepCopyOnLeave ? "rysteams.keep.copy" : "rysteams.keep.none")},
            {"auto", tr("rysteams.rule.auto"), days},
        };
        boolean manage = TeamClient.can(Permission.MANAGE);
        int cardH = 6 + 8 + 4 + 15 + 4 + 15 + 6;
        Draw.roundBox(g, x, y, w, cardH, 6f, t.c("card.bg"), t.c("panel.border"));
        caps(g, t, tr("rysteams.section.rules"), x + 7, y + 10);
        int colW = (w - 14 - 4) / 2;
        for (int i = 0; i < rows.length; i++) {
            String[] row = rows[i];
            int cx = x + 7 + (i % 2) * (colW + 4), cy = y + 18 + (i / 2) * 19;
            boolean hot = manage && hover(cx, cy, colW, 15);
            Draw.round(g, cx, cy, colW, 15, 4f, hot ? Draw.mix(t.c("status.bg"), t.c("text.primary"), 0.06f) : t.c("status.bg"));
            String value = row[2] + " ▾";
            float vw = Draw.labelWidth(value, 12, Weight.REGULAR);
            Draw.label(g, Draw.ellipsize(row[1], colW - 12 - vw, 12, Weight.REGULAR), cx + 5, cy + 7.5f, 12, Weight.REGULAR, t.c("button.fg"));
            Draw.label(g, value, cx + colW - 5 - vw, cy + 7.5f, 12, Weight.REGULAR, t.c("text.primary"));
            if (manage) {
                hits.add(cx, cy, colW, 15, b -> {
                    CompoundTag a = new CompoundTag();
                    a.putString("rule", row[0]);
                    TeamClient.action("set_rule", a);
                });
            }
        }
        return y + cardH;
    }

    private int mergeBlock(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        heading(g, t, tr("rysteams.section.merge"), tr("rysteams.merge.both"), x, w, y);
        int ry = y + 12;
        boolean manage = TeamClient.can(Permission.MANAGE);
        TeamClient.MergeInfo merge = TeamClient.merge;
        if (merge == null) {
            if (!manage) return ry;
            for (TeamClient.Info other : TeamClient.teams) {
                teamRow(g, t, other, x, ry, w, plural("rysteams.count.members", other.count()));
                String label = tr("rysteams.merge.propose");
                int bw = (int) Math.ceil(Kit.buttonWidth(label, 12));
                confirm(g, t, x + w - bw - 5, ry + 6, bw, 14, label, "merge:" + other.id(), () -> TeamClient.action("merge_propose", "team", other.id()));
                ry += 29;
            }
            if (TeamClient.teams.isEmpty()) {
                Draw.label(g, tr("rysteams.empty.merge"), x, ry + 6, 12, Weight.REGULAR, t.c("text.dim"));
                ry += 14;
            }
            return ry;
        }
        int half = (w - 16) / 2;
        mergeTeam(g, t, merge.into(), x, ry, half);
        Draw.label(g, "+", x + half + 5, ry + 15, 18, Weight.REGULAR, t.c("text.muted"));
        mergeTeam(g, t, merge.from(), x + half + 16, ry, half);
        ry += 35;
        int cardTop = ry;
        int inner = w - 14;
        // высота зависит от текста: сначала считаем, потом рисуем
        List<List<String>> wrapped = new ArrayList<>();
        int linesH = 0;
        for (TeamClient.MergeLine line : merge.lines()) {
            List<String> w2 = Draw.wrapLabel(line.text().getString(), inner - 70, 12.5f, Weight.REGULAR, 3);
            wrapped.add(w2);
            linesH += Math.max(16, w2.size() * 7 + 8) + 4;
        }
        List<String> head = Draw.wrapLabel(tr("rysteams.merge.head", merge.into().name()), inner - 12, 12, Weight.SEMIBOLD, 2);
        List<String> note = Draw.wrapLabel(tr("rysteams.merge.note"), inner - 12, 12, Weight.REGULAR, 3);
        int calloutH = 10 + (head.size() + note.size()) * 8 + 2;
        int cardH = 7 + 7 + 4 + linesH + calloutH + 4 + 19 + 7;
        Draw.roundBox(g, x, cardTop, w, cardH, 6f, t.c("card.bg"), t.c("cell.mark.border"));
        caps(g, t, tr("rysteams.merge.preview"), x + 7, cardTop + 10);
        int ly = cardTop + 7 + 7 + 4;
        for (int i = 0; i < merge.lines().size(); i++) {
            TeamClient.MergeLine line = merge.lines().get(i);
            List<String> w2 = wrapped.get(i);
            int rowH = Math.max(16, w2.size() * 7 + 8);
            Draw.roundBox(g, x + 7, ly, inner, rowH, 4f, t.c("status.bg"), t.c("panel.border"));
            float cy = ly + rowH / 2f;
            int cw = sourceChip(g, t, line.source(), x + 12, cy);
            float ty = cy - (w2.size() - 1) * 3.5f;
            for (String s : w2) {
                Draw.label(g, s, x + 12 + cw + 5, ty, 12.5f, Weight.REGULAR, t.c("text.body"));
                ty += 7;
            }
            String ok = tr(line.question() ? "rysteams.merge.question" : "rysteams.merge.ready");
            Draw.label(g, ok, x + 7 + inner - 5 - Draw.labelWidth(ok, 11, Weight.REGULAR), cy, 11, Weight.REGULAR, t.c(line.question() ? "accent" : "state.ok"));
            ly += rowH + 4;
        }
        Draw.roundBox(g, x + 7, ly, inner, calloutH, 4f, t.c("callout.bg"), t.c("callout.border"));
        float cy = ly + 8;
        for (String s : head) {
            Draw.label(g, s, x + 13, cy, 12, Weight.SEMIBOLD, t.c("text.primary"));
            cy += 8;
        }
        for (String s : note) {
            Draw.label(g, s, x + 13, cy, 12, Weight.REGULAR, t.c("button.fg"));
            cy += 8;
        }
        ly += calloutH + 4;
        if (manage) {
            boolean mine = team.id.equals(merge.from().id()) ? merge.fromOk() : merge.intoOk();
            int bx = x + 7;
            if (!mine) {
                String yes = tr("rysteams.merge.confirm");
                int bw = (int) Math.ceil(Draw.labelWidth(yes, 13, Weight.SEMIBOLD)) + 16;
                Kit.button(g, t, hits, bx, ly, bw, 19, yes, true, mx, my, () -> TeamClient.action("merge_confirm"));
                bx += bw + 4;
            }
            String no = tr("rysteams.merge.cancel");
            int bw2 = (int) Math.ceil(Draw.labelWidth(no, 12.5f, Weight.REGULAR)) + 12;
            Kit.button(g, t, hits, bx, ly, bw2, 19, no, false, mx, my, () -> TeamClient.action("merge_cancel"));
            if (mine) {
                String wait = tr("rysteams.merge.waiting");
                Draw.label(g, wait, x + w - 7 - Draw.labelWidth(wait, 11.5f, Weight.REGULAR), ly + 9.5f, 11.5f, Weight.REGULAR, t.c("text.muted"));
            }
        }
        return cardTop + cardH;
    }

    private void mergeTeam(GuiGraphics g, Theme t, TeamClient.Info info, int x, int y, int w) {
        Draw.roundBox(g, x, y, w, 31, 6f, t.c("card.bg"), t.c("panel.border"));
        teamTile(g, info.color(), info.icon(), x + 6, y + 6, 18, 4.5f);
        Draw.label(g, Draw.ellipsize(info.name(), w - 34, 13, Weight.SEMIBOLD), x + 29, y + 11, 13, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, plural("rysteams.count.members", info.count()), x + 29, y + 20, 11, Weight.REGULAR, t.c("text.muted"));
    }

    private int identity(GuiGraphics g, Theme t, Team team, int x, int y, int w) {
        boolean manage = TeamClient.can(Permission.MANAGE);
        int cardH = manage ? 133 : 77;
        Draw.roundBox(g, x, y, w, cardH, 6f, t.c("card.bg"), t.c("panel.border"));
        caps(g, t, tr("rysteams.section.identity"), x + 7, y + 10);
        int ry = y + 18;
        if (manage) {
            String save = tr("rysteams.action.rename");
            int bw = (int) Math.ceil(Kit.buttonWidth(save, 12));
            input(g, t, nameBox, x + 7, ry, w - 14 - bw - 4, 15);
            Kit.button(g, t, hits, x + w - 7 - bw, ry, bw, 15, save, false, mx, my, () -> {
                CompoundTag a = new CompoundTag();
                a.putString("name", nameBox.getValue());
                TeamClient.action("set_identity", a);
                nameBox.setValue("");
            });
            ry += 19;
            ry = swatches(g, t, x + 7, ry, w - 14, team.color, color -> {
                CompoundTag a = new CompoundTag();
                a.putInt("color", color);
                TeamClient.action("set_identity", a);
            });
        }
        ry = toggleRow(g, t, x + 7, ry, w - 14, tr("rysteams.personal.hide"), TeamClient.hideCoords, () -> {
            CompoundTag a = new CompoundTag();
            a.putBoolean("on", !TeamClient.hideCoords);
            TeamClient.action("hide", a);
        });
        ry = toggleRow(g, t, x + 7, ry, w - 14, tr("rysteams.personal.death"), TeamClient.deathNotice, () -> {
            CompoundTag a = new CompoundTag();
            a.putBoolean("on", !TeamClient.deathNotice);
            TeamClient.action("death_notice", a);
        });
        ry = toggleRow(g, t, x + 7, ry, w - 14, tr("rysteams.personal.hud"), ClientEntry.SHOW_HUD.get(), () -> {
            ClientEntry.SHOW_HUD.set(!ClientEntry.SHOW_HUD.get());
            ClientEntry.CLIENT_SPEC.save();
        });
        // только для себя: сколько живут метки и в каком углу стоит карточка команды
        int seconds = ClientEntry.PING_SECONDS.get();
        ry = choiceRow(g, t, x + 7, ry, w - 14, tr("rysteams.personal.pings"), tr("rysteams.personal.seconds", seconds), () -> {
            int next = ClientEntry.PING_CHOICES[0];
            for (int i = 0; i < ClientEntry.PING_CHOICES.length; i++) {
                if (ClientEntry.PING_CHOICES[i] > seconds) {
                    next = ClientEntry.PING_CHOICES[i];
                    break;
                }
            }
            ClientEntry.PING_SECONDS.set(next);
            ClientEntry.CLIENT_SPEC.save();
        });
        Anchors.Corner corner = Anchors.corner(Anchors.Element.TEAM_CARD);
        choiceRow(g, t, x + 7, ry, w - 14, tr("rysteams.personal.card"), tr("rysteams.corner." + corner.name().toLowerCase(java.util.Locale.ROOT)), () -> {
            Anchors.Corner[] order = {Anchors.Corner.BOTTOM_RIGHT, Anchors.Corner.BOTTOM_LEFT, Anchors.Corner.TOP_LEFT, Anchors.Corner.TOP_RIGHT};
            int at = 0;
            for (int i = 0; i < order.length; i++) if (order[i] == corner) at = i;
            Anchors.setCorner(Anchors.Element.TEAM_CARD, order[(at + 1) % order.length]);
        });
        return y + cardH;
    }

    /** Строка выбора: подпись слева, значение со стрелкой справа, клик переключает по кругу. */
    private int choiceRow(GuiGraphics g, Theme t, int x, int y, int w, String label, String value, Runnable action) {
        boolean hot = hover(x, y - 2, w, 12);
        Draw.label(g, label, x, y + 5, 12, Weight.REGULAR, t.c("button.fg"));
        String shown = value + " ▾";
        float vw = Draw.labelWidth(shown, 12, Weight.REGULAR);
        Draw.label(g, shown, x + w - vw, y + 5, 12, Weight.REGULAR, hot ? t.c("accent") : t.c("text.primary"));
        hits.add(x, y - 2, w, 12, b -> action.run());
        return y + 13;
    }

    private int toggleRow(GuiGraphics g, Theme t, int x, int y, int w, String label, boolean on, Runnable action) {
        boolean hot = hover(x, y - 2, w, 12);
        Draw.label(g, label, x, y + 5, 12, Weight.REGULAR, t.c("button.fg"));
        Kit.toggle(g, t, x + w - 18, y, on, hot);
        hits.add(x, y - 2, w, 12, b -> action.run());
        return y + 13;
    }

    private interface ColorPick {
        void pick(int color);
    }

    /** Образцы цветов: 22 пикселя и рамка 2 по макету, шаг 16 пикселей GUI. */
    private int swatches(GuiGraphics g, Theme t, int x, int y, int w, int current, ColorPick pick) {
        int size = 13, gap = 3, perRow = Math.max(1, (w + gap) / (size + gap));
        for (int i = 0; i < Team.COLORS.length; i++) {
            int color = Team.COLORS[i];
            int cx = x + (i % perRow) * (size + gap), cy = y + (i / perRow) * (size + gap);
            Draw.round(g, cx + 1, cy + 1, size - 2, size - 2, 3f, TeamHud.rgb(color));
            if (color == current) Draw.roundBorder(g, cx, cy, size, size, 3.5f, t.c("text.primary"), 1f);
            hits.add(cx, cy, size, size, b -> pick.pick(color));
        }
        return y + ((Team.COLORS.length - 1) / perRow + 1) * (size + gap) + 2;
    }

    private int danger(GuiGraphics g, Theme t, int x, int y, int w) {
        if (TeamClient.serverMode) return y;
        String leave = tr("rysteams.action.leave");
        int lw = (int) Math.ceil(Kit.buttonWidth(leave, 12));
        confirm(g, t, x, y, lw, 16, leave, "leave", () -> TeamClient.action("leave"));
        if (TeamClient.can(Permission.MANAGE)) {
            String disband = tr("rysteams.action.disband");
            int bw = (int) Math.ceil(Kit.buttonWidth(disband, 12));
            confirm(g, t, x + w - bw, y, bw, 16, disband, "disband", () -> TeamClient.action("disband"));
        }
        return y + 20;
    }

    // --- нет команды ---

    private void lobby(GuiGraphics g, Theme t) {
        pw = Math.min(width - 8, 250);
        ph = Math.min(height - 8, 259);
        px = width >= 640 ? 20 : (width - pw) / 2;
        py = Math.max(4, Math.min(height * 60 / 400, height - ph - 4));
        panel(g, t, px, py, pw, ph, 8f);
        // шапка
        Draw.round(g, px + 0.5f, py + 0.5f, pw - 1, 33, 7.5f, t.c("sidebar.bg"));
        Draw.rect(g, px + 1, py + 18, pw - 2, 8, t.c("sidebar.bg"));
        Draw.rect(g, px + 1, py + 26, pw - 2, 8, t.c("panel.bg"));
        Draw.round(g, px + 0.5f, py + 25.5f, pw - 1, 0.5f, 0.02f, t.c("panel.border"));
        logo(g, t, px + 9, py + 7, 11f);
        Draw.label(g, tr("rysteams.lobby.title"), px + 25, py + 13, 16, Weight.DISPLAY, t.c("text.primary"));
        String sub = tr("rysteams.lobby.sub");
        Draw.label(g, sub, px + pw - 9 - Draw.labelWidth(sub, 11, Weight.REGULAR), py + 13, 11, Weight.REGULAR, t.c("text.muted"));
        // две колонки
        int body = py + 26 + 8, colW = (pw - 18 - 6) / 2, colH = ph - 26 - 16 - 22;
        int left = px + 9, right = left + colW + 6;
        Draw.roundBox(g, left, body, colW, colH, 6f, t.c("card.bg"), t.c("cell.mark.border"));
        Draw.roundBox(g, right, body, colW, colH, 6f, t.c("card.bg"), t.c("panel.border"));
        createForm(g, t, left + 7, body + 7, colW - 14, body + colH);
        joinList(g, t, right + 7, body + 7, colW - 14, body + colH);
        // подвал
        int fy = py + ph - 21;
        Draw.round(g, px + 0.5f, fy, pw - 1, 0.5f, 0.02f, t.c("panel.border"));
        String link = tr("rysteams.lobby.link");
        float lw = Draw.labelWidth(link, 11.5f, Weight.REGULAR);
        Draw.label(g, link, px + 9, fy + 10.5f, 11.5f, Weight.REGULAR, t.c("text.muted"));
        String sample = tr("rysteams.lobby.link_sample");
        chip(g, sample, 11.5f, (int) (px + 9 + lw + 4), fy + 10.5f, 10f, t.c("chip.mod.bg"), t.c("chip.mod.fg"), Weight.REGULAR, 4f, 3f);
    }

    private void createForm(GuiGraphics g, Theme t, int x, int y, int w, int bottom) {
        Draw.label(g, tr("rysteams.lobby.create"), x, y + 4, 13.5f, Weight.SEMIBOLD, t.c("text.primary"));
        int ry = y + 13;
        Draw.label(g, tr("rysteams.lobby.name"), x, ry + 3, 11, Weight.REGULAR, t.c("text.muted"));
        input(g, t, nameBox, x, ry + 8, w, 17);
        ry += 30;
        Draw.label(g, tr("rysteams.lobby.color"), x, ry + 3, 11, Weight.REGULAR, t.c("text.muted"));
        ry = swatches(g, t, x, ry + 8, w, newColor, color -> newColor = color) + 1;
        Draw.label(g, tr("rysteams.lobby.icon"), x, ry + 3, 11, Weight.REGULAR, t.c("text.muted"));
        ry += 8;
        var inv = Minecraft.getInstance().player.getInventory();
        int ix = x, shown = 0;
        for (int i = 0; i < 9 && shown < 5; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean selected = id.equals(newIcon);
            Draw.round(g, ix, ry, 18, 18, 4f, t.c("slot.bg"));
            Draw.roundBorder(g, ix, ry, 18, 18, 4f, selected ? t.c("accent") : hover(ix, ry, 18, 18) ? t.c("text.dim") : t.c("card.border"), selected ? 1f : 0.5f);
            g.renderItem(stack, ix + 1, ry + 1);
            hits.add(ix, ry, 18, 18, b -> newIcon = id);
            ix += 21;
            shown++;
        }
        if (shown == 0) Draw.label(g, tr("rysteams.lobby.no_items"), x, ry + 9, 11, Weight.REGULAR, t.c("text.dim"));
        int by = Math.min(ry + 18 + 7, bottom - 7 - 19);
        String label = tr("rysteams.lobby.create_button");
        boolean hot = hover(x, by, w, 19);
        Draw.round(g, x, by, w, 19, 5f, hot ? Draw.mix(t.c("accent"), 0xFFFFFFFF, 0.15f) : t.c("accent"));
        Draw.label(g, label, x + (w - Draw.labelWidth(label, 13, Weight.SEMIBOLD)) / 2f, by + 9.5f, 13, Weight.SEMIBOLD, t.c("accent.text"));
        hits.add(x, by, w, 19, b -> {
            CompoundTag a = new CompoundTag();
            a.putString("name", nameBox.getValue());
            a.putInt("color", newColor);
            a.putString("icon", newIcon);
            TeamClient.action("create", a);
        });
    }

    private void joinList(GuiGraphics g, Theme t, int x, int y, int w, int bottom) {
        Draw.label(g, tr("rysteams.lobby.join"), x, y + 4, 13.5f, Weight.SEMIBOLD, t.c("text.primary"));
        List<String> note = Draw.wrapLabel(tr("rysteams.lobby.closed"), w, 11, Weight.REGULAR, 2);
        int top = y + 13, listBottom = (int) (bottom - 7 - note.size() * 7.5f - 3);
        int scroll = Math.round(lobbyScroller.update(Math.max(0, lobbyContent - (listBottom - top))));
        hits.clip(top, listBottom);
        Draw.scissor(g, x - 1, top, x + w + 1, listBottom);
        int ry = top - scroll;
        if (!TeamClient.restore.isEmpty()) {
            Kit.button(g, t, hits, x, ry, w, 15, Draw.ellipsize(tr("rysteams.lobby.restore", TeamClient.restore), w - 8, 12, Weight.REGULAR), false, mx, my, () -> TeamClient.action("restore"));
            ry += 19;
        }
        for (TeamClient.Info invite : TeamClient.invites) {
            Draw.roundBox(g, x, ry, w, 36, 4.5f, Draw.alpha(t.c("accent"), 0.1f), Draw.alpha(t.c("accent"), 0.5f));
            teamTile(g, invite.color(), invite.icon(), x + 4, ry + 4, 12, 3f);
            Draw.label(g, Draw.ellipsize(tr("rysteams.invite.title", invite.by(), invite.name()), w - 22, 11, Weight.REGULAR), x + 19, ry + 10, 11, Weight.REGULAR, t.c("text.primary"));
            int bw = (w - 12) / 3, by = ry + 19;
            answerButton(g, t, x + 4, by, bw, tr("rysteams.lobby.accept"), true, invite.id(), "accept");
            answerButton(g, t, x + 6 + bw, by, bw, tr("rysteams.invite.fresh"), false, invite.id(), "fresh");
            answerButton(g, t, x + 8 + bw * 2, by, bw, tr("rysteams.lobby.no"), false, invite.id(), "no");
            ry += 39;
        }
        int rows = 0;
        for (TeamClient.Info info : TeamClient.teams) {
            String join = info.join().toLowerCase(java.util.Locale.ROOT);
            boolean open = info.join().equals(Team.JoinMode.OPEN.name()), closed = info.join().equals(Team.JoinMode.INVITE.name());
            if (closed) continue;
            String sub = plural("rysteams.count.members", info.count()) + " · " + (info.pending() ? tr("rysteams.lobby.pending") : tr("rysteams.join." + join));
            Draw.roundBox(g, x, ry, w, 22, 4.5f, t.c("status.bg"), info.pending() ? t.c("cell.mark.border") : t.c("panel.border"));
            teamTile(g, info.color(), info.icon(), x + 4, ry + 4, 14, 3.5f);
            int bw = info.pending()
                ? smallButton(g, t, x + w - 4, ry + 4, 14, tr("rysteams.lobby.cancel"), () -> TeamClient.action("cancel_request", "team", info.id()))
                : smallButton(g, t, x + w - 4, ry + 4, 14, tr(open ? "rysteams.lobby.enter" : "rysteams.lobby.request"), () -> TeamClient.action(open ? "join" : "request", "team", info.id()));
            Draw.label(g, Draw.ellipsize(info.name(), w - 28 - bw, 12.5f, Weight.REGULAR), x + 22, ry + 7, 12.5f, Weight.REGULAR, t.c("text.primary"));
            Draw.label(g, Draw.ellipsize(sub, w - 28 - bw, 11, Weight.REGULAR), x + 22, ry + 15.5f, 11, Weight.REGULAR, t.c("text.muted"));
            ry += 25;
            rows++;
        }
        if (rows == 0 && TeamClient.invites.isEmpty()) {
            Draw.label(g, tr("rysteams.lobby.none"), x, ry + 5, 11, Weight.REGULAR, t.c("text.dim"));
            ry += 12;
        }
        lobbyContent = ry + scroll - top;
        g.disableScissor();
        hits.unclip();
        float ny = bottom - 7 - (note.size() - 1) * 7.5f - 3.5f;
        for (String line : note) {
            Draw.label(g, line, x, ny, 11, Weight.REGULAR, t.c("text.dim"));
            ny += 7.5f;
        }
    }

    private void answerButton(GuiGraphics g, Theme t, int x, int y, int w, String label, boolean accent, UUID team, String answer) {
        Kit.button(g, t, hits, x, y, w, 14, Draw.ellipsize(label, w - 4, 12, Weight.REGULAR), accent, mx, my, () -> {
            CompoundTag a = new CompoundTag();
            a.putUUID("team", team);
            a.putString("answer", answer);
            TeamClient.action("invite_reply", a);
        });
    }

    // --- ввод ---

    @Override
    protected boolean onClick(double x, double y, int button) {
        if (hits.click(x, y, button)) return true;
        if (invitePopup || menuFor != null) {
            invitePopup = false;
            menuFor = null;
            return true;
        }
        boolean used = super.onClick(x, y, button);
        if (!used) {
            setFocused(null);
            for (Field b : boxes) b.setFocused(false);
        }
        return used;
    }

    @Override
    protected boolean onScroll(double x, double y, double dx, double dy) {
        if (TeamClient.team == null) lobbyScroller.by(-(float) dy * 18);
        else if (tab == Tab.TEAM) feedScroller.by(-(float) dy * 18);
        else bodyScroller.by(-(float) dy * 18);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        for (Field b : boxes) {
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
