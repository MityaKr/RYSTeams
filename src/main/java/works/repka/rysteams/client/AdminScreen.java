package works.repka.rysteams.client;

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

/** Экран администратора: все команды, архив распущенных, импорт из FTB Teams. Открывается командой {@code /rysteams admin}. */
public final class AdminScreen extends Screen {
    private record Row(UUID id, String name, int color, int count, String leader, long time) {}

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final List<Row> teams = new ArrayList<>();
    private final List<Row> archive = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private boolean ftbAvailable;
    private String armed = "";
    private long armedUntil;
    private int mx, my, scroll;

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
        return new Row(x.getUUID("id"), x.getString("name"), x.getInt("color"), x.getInt("count"), x.getString("leader"), x.getLong("time"));
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Theme t = ThemeManager.get();
        mx = mouseX;
        my = mouseY;
        hits.clear();
        renderBackground(g, mouseX, mouseY, partialTick);
        int pw = Math.min(width - 8, 520), ph = Math.min(height - 8, 340);
        int px = (width - pw) / 2, py = Math.max(4, Math.min(height / 18, height - ph - 4));
        t.panel(g, px, py, pw, ph);
        Draw.text(g, tr("rysteams.admin.title"), px + 10, py + 9, t.c("text.primary"));
        int closeX = px + pw - 21;
        t.button(g, closeX, py + 6, 13, 13, over(closeX, py + 6, 13, 13), false);
        Draw.cross(g, closeX + 3, py + 9, t.c("text.muted"));
        hits.add(new Hit(closeX, py + 6, 13, 13, this::onClose));
        if (ftbAvailable) {
            String label = tr("rysteams.admin.import");
            int w = Minecraft.getInstance().font.width(label) + 12;
            confirm(g, t, closeX - w - 6, py + 5, w, 15, label, "import", () -> TeamClient.action("admin_import"));
        }
        Draw.rect(g, px + 1, py + 24, pw - 2, 1, t.c("panel.border"));
        int colW = (pw - 24) / 2, top = py + 30, height = ph - 36;
        int left = px + 8, right = left + colW + 8;
        section(g, t, tr("rysteams.admin.teams", teams.size()), left + 1, top);
        section(g, t, tr("rysteams.admin.archive", archive.size()), right + 1, top);
        int listTop = top + 11, listBottom = top + height;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, Math.max(teams.size(), archive.size()) * 25 - (listBottom - listTop))));
        g.enableScissor(px + 1, listTop, px + pw - 1, listBottom);
        int y = listTop - scroll;
        for (Row r : teams) {
            row(g, t, r, left, y, colW, tr("rysteams.team.count", r.count) + " · " + r.leader);
            if (y + 25 > listTop && y < listBottom) {
                String label = tr("rysteams.action.disband");
                int w = Minecraft.getInstance().font.width(label) + 10;
                confirm(g, t, left + colW - w - 3, y + 4, w, 14, label, "d" + r.id, () -> TeamClient.action("admin_disband", "team", r.id));
            }
            y += 25;
        }
        y = listTop - scroll;
        for (Row r : archive) {
            long days = (System.currentTimeMillis() - r.time) / 86_400_000L;
            row(g, t, r, right, y, colW, tr("rysteams.admin.archived", days) + " · " + r.leader);
            if (y + 25 > listTop && y < listBottom) {
                String label = tr("rysteams.admin.restore");
                int w = Minecraft.getInstance().font.width(label) + 10;
                confirm(g, t, right + colW - w - 3, y + 4, w, 14, label, "r" + r.id, () -> TeamClient.action("admin_restore", "team", r.id));
            }
            y += 25;
        }
        g.disableScissor();
        TeamHud.toast(g, t, Minecraft.getInstance());
    }

    private void section(GuiGraphics g, Theme t, String text, int x, int y) {
        Draw.textScaled(g, text.toUpperCase(java.util.Locale.ROOT), x, y, 0.75f, t.c("text.dim"));
    }

    private void row(GuiGraphics g, Theme t, Row r, int x, int y, int w, String sub) {
        t.card(g, x, y, w, 22);
        Draw.round(g, x + 4, y + 5, 12, 12, 3f, TeamHud.rgb(r.color));
        Draw.label(g, Draw.ellipsize(r.name, w - 92, 13, works.repka.ryscore.ui.Type.Weight.SEMIBOLD), x + 21, y + 7, 13, works.repka.ryscore.ui.Type.Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, Draw.ellipsize(sub, w - 90, 11, works.repka.ryscore.ui.Type.Weight.REGULAR), x + 21, y + 16, 11, works.repka.ryscore.ui.Type.Weight.REGULAR, t.c("text.muted"));
    }

    private boolean over(int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Действие администратора необратимо для игроков: нужно второе нажатие. */
    private void confirm(GuiGraphics g, Theme t, int x, int y, int w, int h, String label, String key, Runnable action) {
        boolean ready = armed.equals(key) && System.currentTimeMillis() < armedUntil;
        t.button(g, x, y, w, h, over(x, y, w, h), false);
        Draw.textCentered(g, ready ? tr("rysteams.confirm") : label, x + w / 2, y + (h - 8) / 2, t.c(ready ? "state.bad" : "text.body"));
        hits.add(new Hit(x, y, w, h, () -> {
            if (ready) {
                armed = "";
                action.run();
            } else {
                armed = key;
                armedUntil = System.currentTimeMillis() + 3000;
            }
        }));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h) {
                    h.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        scroll -= (int) (dy * 18);
        return true;
    }
}
