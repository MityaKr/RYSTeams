package works.repka.rysteams.client;

import works.repka.ryscore.ui.ScaledScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Scroller;
import works.repka.ryscore.ui.Type.Weight;

/** Экран администратора: все команды, архив распущенных, импорт из FTB Teams. Открывается командой {@code /rysteams admin}. */
public final class AdminScreen extends ScaledScreen {
    private record Row(UUID id, String name, int color, String icon, int count, String leader, long time) {}

    private static final int W = 520, H = 340, HEADER = 32, ROW = 26, STEP = 29;

    private final List<Row> teams = new ArrayList<>();
    private final List<Row> archive = new ArrayList<>();
    private final Kit.Hits hits = new Kit.Hits();
    private boolean ftbAvailable;
    private String armed = "";
    private long armedUntil;
    private int mx, my, scroll;
    private final Scroller scroller = new Scroller();

    public AdminScreen(CompoundTag data) {
        super(Component.translatable("rysteams.admin.title"));
        accept(data);
    }

    public void accept(CompoundTag data) {
        teams.clear();
        archive.clear();
        ftbAvailable = data.getBoolean("ftb");
        for (Tag x : data.getList("teams", Tag.TAG_COMPOUND)) teams.add(row((CompoundTag) x));
        for (Tag x : data.getList("archive", Tag.TAG_COMPOUND)) archive.add(row((CompoundTag) x));
    }

    private static Row row(CompoundTag x) {
        return new Row(x.getUUID("id"), x.getString("name"), x.getInt("color"), x.getString("icon"), x.getInt("count"), x.getString("leader"), x.getLong("time"));
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Theme t = ThemeManager.get();
        mx = mouseX;
        my = mouseY;
        hits.clear();
        renderBackground(g, mouseX, mouseY, partialTick);
        int pw = Math.min(width - 8, W), ph = Math.min(height - 8, H);
        int px = (width - pw) / 2, py = (height - ph) / 2;
        panel(g, t, px, py, pw, ph);
        header(g, t, px, py, pw);
        int total = pw - 20 - 8, colW = total / 2, left = px + 10, right = left + colW + 8, top = py + HEADER + 9;
        heading(g, t, tr("rysteams.admin.teams", teams.size()), left, top);
        heading(g, t, tr("rysteams.admin.archive", archive.size()), right, top);
        int listTop = top + 12, listBottom = py + ph - 8;
        int content = Math.max(teams.size(), archive.size()) * STEP - (STEP - ROW);
        scroll = Math.round(scroller.update(Math.max(0, content - (listBottom - listTop))));
        hits.clip(listTop, listBottom);
        Draw.scissor(g, px + 1, listTop, px + pw - 1, listBottom);
        int y = listTop - scroll;
        for (Row r : teams) {
            String label = tr("rysteams.action.disband");
            row(g, t, r, left, y, colW, tr("rysteams.team.count", r.count) + " · " + r.leader, label, "d" + r.id, () -> TeamClient.action("admin_disband", "team", r.id));
            y += STEP;
        }
        y = listTop - scroll;
        for (Row r : archive) {
            long days = (System.currentTimeMillis() - r.time) / 86_400_000L;
            String label = tr("rysteams.admin.restore");
            row(g, t, r, right, y, colW, tr("rysteams.admin.archived", days) + " · " + r.leader, label, "r" + r.id, () -> TeamClient.action("admin_restore", "team", r.id));
            y += STEP;
        }
        g.disableScissor();
        hits.unclip();
    }

    private void panel(GuiGraphics g, Theme t, int x, int y, int w, int h) {
        if (t.beveled()) {
            t.panel(g, x, y, w, h);
            return;
        }
        if (t.shadow) Draw.shadow(g, x, y, w, h, 7f, 1f);
        Draw.roundBox(g, x, y, w, h, 7f, t.c("panel.bg"), t.c("panel.border"));
    }

    private void header(GuiGraphics g, Theme t, int px, int py, int pw) {
        // заливка шапки срезается по её низу: второй слой панели в полупрозрачной теме дал бы полосу
        Draw.scissor(g, px, py, px + pw, py + HEADER);
        Draw.round(g, px + 0.5f, py + 0.5f, pw - 1, HEADER + 14, 6.5f, t.c("sidebar.bg"));
        g.disableScissor();
        Draw.round(g, px + 0.5f, py + HEADER - 0.5f, pw - 1, 0.5f, 0.02f, t.c("panel.border"));
        TeamScreen.logo(g, t, px + 9, py + 9, 13f);
        Draw.label(g, tr("rysteams.admin.title"), px + 27, py + 16, 16, Weight.DISPLAY, t.c("text.primary"));
        int closeX = px + pw - 9 - 17, closeY = py + 7;
        boolean hot = Kit.over(mx, my, closeX, closeY, 17, 17);
        Kit.iconButton(g, t, closeX, closeY, 17, hot);
        Draw.cross(g, closeX + 5, closeY + 5, hot ? t.c("accent") : t.c("button.fg"));
        hits.add(closeX, closeY, 17, 17, b -> onClose());
        if (ftbAvailable) {
            String label = tr("rysteams.admin.import");
            int w = (int) Math.ceil(Kit.buttonWidth(label, 12));
            confirm(g, t, closeX - 7 - w, closeY + 1, w, 15, label, "import", () -> TeamClient.action("admin_import"));
        }
    }

    private void heading(GuiGraphics g, Theme t, String title, int x, int y) {
        Draw.label(g, title, x, y + 3, 16, Weight.DISPLAY, t.c("text.primary"));
    }

    private void row(GuiGraphics g, Theme t, Row r, int x, int y, int w, String sub, String label, String key, Runnable action) {
        Draw.roundBox(g, x, y, w, ROW, 6f, t.c("card.bg"), t.c("panel.border"));
        TeamScreen.teamTile(g, r.color, r.icon, x + 5, y + 4, 18, 4.5f);
        int bw = (int) Math.ceil(Kit.buttonWidth(label, 12));
        float room = w - 28 - bw - 10;
        Draw.label(g, Draw.ellipsize(r.name, room, 13, Weight.SEMIBOLD), x + 28, y + 9, 13, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, Draw.ellipsize(sub, room, 11, Weight.REGULAR), x + 28, y + 18, 11, Weight.REGULAR, t.c("text.muted"));
        confirm(g, t, x + w - bw - 5, y + 6, bw, 14, label, key, action);
    }

    /** Действие администратора необратимо для игроков: нужно второе нажатие. */
    private void confirm(GuiGraphics g, Theme t, int x, int y, int w, int h, String label, String key, Runnable action) {
        boolean ready = armed.equals(key) && System.currentTimeMillis() < armedUntil;
        boolean hot = Kit.over(mx, my, x, y, w, h);
        Draw.roundBox(g, x, y, w, h, 4f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
        String shown = ready ? tr("rysteams.confirm") : label;
        Draw.label(g, shown, x + (w - Draw.labelWidth(shown, 12, Weight.REGULAR)) / 2f, y + h / 2f, 12, Weight.REGULAR, ready ? t.c("state.bad") : t.c("text.primary"));
        hits.add(x, y, w, h, b -> {
            if (b != 0) return;
            if (ready) {
                armed = "";
                action.run();
            } else {
                armed = key;
                armedUntil = System.currentTimeMillis() + 3000;
            }
        });
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        return hits.click(x, y, button) || super.onClick(x, y, button);
    }

    @Override
    protected boolean onScroll(double x, double y, double dx, double dy) {
        scroller.by(-(float) dy * 18);
        return true;
    }
}
