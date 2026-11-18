package works.repka.rysteams.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import works.repka.ryscore.team.TeamsApi;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.api.Role;
import works.repka.rysteams.client.TeamClient;
import works.repka.rysteams.client.TeamScreen;
import works.repka.rysteams.data.Team;
import works.repka.rysteams.network.ModNetwork;
import works.repka.rysteams.server.TeamService;

/** Проверка в игре: создаёт мир, наполняет команду, снимает экраны. Включается свойством {@code rysteams.autotest}, в jar не входит. */
@EventBusSubscriber(modid = RYSTeams.MODID, value = Dist.CLIENT)
public final class TeamsAutoTest {
    private record Step(String name, BooleanSupplier ready, Runnable action, int pause) {}

    private static final String WORLD = "rysteams_autotest";
    private static final boolean ON = System.getProperty("rysteams.autotest") != null;
    private static final List<Step> STEPS = new ArrayList<>();
    private static final UUID LENA = UUID.nameUUIDFromBytes("Lena".getBytes()), VASYA = UUID.nameUUIDFromBytes("Vasya".getBytes()), GOST = UUID.nameUUIDFromBytes("Gost".getBytes());
    private static final UUID OTHER_TEAM = UUID.nameUUIDFromBytes("Boloto".getBytes());
    private static boolean built;
    private static int index, wait, total, shots = 1, failures;

    private TeamsAutoTest() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (!ON) return;
        if (!built) {
            built = true;
            build();
        }
        total++;
        if (wait > 0) {
            wait--;
            return;
        }
        if (index >= STEPS.size()) return;
        Step s = STEPS.get(index);
        if (!s.ready.getAsBoolean()) return;
        try {
            s.action.run();
        } catch (Throwable t) {
            failures++;
            RYSTeams.LOG.error("[autotest] шаг {} упал", s.name, t);
        }
        wait = s.pause;
        index++;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void step(String name, Runnable action, int w) {
        STEPS.add(new Step(name, () -> true, action, w));
    }

    private static void shot(String name) {
        shot(name, false);
    }

    /** @param notices оставить общие уведомления семьи: они тосты игры, и очистка тостов убрала бы их с кадра */
    private static void shot(String name, boolean notices) {
        step("clean", () -> {
            if (!notices) mc().getToasts().clear();
            mc().gui.getChat().clearMessages(false);
        }, 2);
        step("shot " + name, () -> Screenshot.grab(mc().gameDirectory, String.format("teams_%02d_%s.png", shots++, name), mc().getMainRenderTarget(), m -> {}), 4);
    }

    private static void check(String what, boolean ok) {
        if (!ok) failures++;
        RYSTeams.LOG.info("[autotest] {} {}", ok ? "OK  " : "FAIL", what);
    }

    private static ServerPlayer player() {
        return mc().getSingleplayerServer().getPlayerList().getPlayers().get(0);
    }

    private static void act(String action, CompoundTag args) {
        ModNetwork.action(action, args);
    }

    private static void server(Runnable r) {
        mc().getSingleplayerServer().execute(r);
    }

    private static void seed() {
        var s = TeamService.store();
        Team team = s.teamOf(player().getUUID());
        long now = System.currentTimeMillis();
        team.members.put(LENA, new Team.Member(LENA, "Lena", Role.OFFICER, now, now));
        team.members.put(VASYA, new Team.Member(VASYA, "Vasya", Role.MEMBER, now, now));
        team.members.put(GOST, new Team.Member(GOST, "Gost", Role.GUEST, now, now - 2 * 3600_000L));
        for (UUID id : team.members.keySet()) s.byPlayer.put(id, team.id);
        team.requests.put(UUID.nameUUIDFromBytes("Petya".getBytes()), "Petya");
        TeamsApi.get().post(team.id, new TeamsApi.Post(net.minecraft.resources.ResourceLocation.parse("rysquests:quests"),
            Component.literal("Vasya выполнил «Динамо». Награда команды: 8 золота"), net.minecraft.resources.ResourceLocation.parse("minecraft:gold_ingot"), false));
        TeamsApi.get().post(team.id, new TeamsApi.Post(net.minecraft.resources.ResourceLocation.parse("ryspulse:pulse"),
            Component.literal("Плавильня ×2 встала: выход полон. Ближе всех Vasya"), net.minecraft.resources.ResourceLocation.parse("minecraft:blast_furnace"), true));
        Team other = new Team(OTHER_TEAM, "Болотники", Team.COLORS[2], "minecraft:lily_pad", now);
        other.join = Team.JoinMode.REQUEST;
        UUID boss = UUID.nameUUIDFromBytes("Boss".getBytes());
        other.members.put(boss, new Team.Member(boss, "Boss", Role.LEADER, now, now));
        s.teams.put(other.id, other);
        s.byPlayer.put(boss, other.id);
        s.setDirty();
        TeamService.imported();
    }

    private static void build() {
        step("title", () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            if (!(mc().screen instanceof TitleScreen)) mc().setScreen(new TitleScreen());
            ThemeManager.select("ryscore:slate");
        }, 30);
        step("world", () -> {
            String world = WORLD + "_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(42L, false, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc().screen);
        }, 10);
        STEPS.add(new Step("wait", () -> mc().player != null && mc().level != null && mc().screen == null, () -> {
            check("состояние пришло, игрок соло", TeamClient.team == null);
            check("соло: команда равна игроку", TeamsApi.get().teamOf(player().getUUID()).equals(player().getUUID()));
        }, 40));
        if ("migrate".equals(System.getProperty("rysteams.autotest"))) {
            buildMigrate();
            return;
        }
        step("hand", () -> {
            mc().player.connection.sendCommand("give @s minecraft:compass");
            mc().player.connection.sendCommand("give @s minecraft:emerald");
        }, 10);
        step("lobby", () -> mc().setScreen(new TeamScreen()), 10);
        shot("lobby");
        step("create", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("name", "Repka");
            a.putInt("color", Team.COLORS[0]);
            a.putString("icon", "minecraft:emerald");
            act("create", a);
        }, 10);
        step("created", () -> check("команда создана, игрок лидер", TeamClient.team != null && TeamClient.team.members.get(mc().player.getUUID()).role == Role.LEADER), 2);
        step("seed", () -> server(TeamsAutoTest::seed), 10);
        step("cards", () -> {
            works.repka.ryscore.team.TeamCards.registerDetailed(() -> new works.repka.ryscore.team.TeamCards.Detailed(new works.repka.ryscore.team.TeamCards.Card(net.minecraft.resources.ResourceLocation.parse("rysquests:quests"),
                Component.literal("RYSQuests"), List.of(Component.literal("41 / 68 · 2 награды к получению · Lena на «Пламя», Vasya закрыл «Динамо»")), Component.literal("Открыть книгу · K"), () -> {}, false), Component.literal("глава II"), 0.6f));
            works.repka.ryscore.team.TeamCards.registerDetailed(() -> new works.repka.ryscore.team.TeamCards.Detailed(new works.repka.ryscore.team.TeamCards.Card(net.minecraft.resources.ResourceLocation.parse("ryspulse:pulse"),
                Component.literal("RYSPulse"), List.of(Component.literal("База «Дом» · энергия 68 %, хватит на 12 мин · плавильня ×2 встала · ближе всех Vasya (14 м)")), Component.literal("Открыть базу · P"), () -> {}, true), Component.literal("2 тревоги"), -1f));
        }, 2);
        step("note", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("text", "Сверху портал в Незер, не закрывать проход.");
            act("note_add", a);
            CompoundTag b = new CompoundTag();
            b.putString("text", "Принести медь ×64");
            act("todo_add", b);
            CompoundTag c = new CompoundTag();
            c.putString("name", "Портал в Незер");
            act("mark_add", c);
        }, 10);
        step("deny", () -> {
            // чужая роль не должна проходить: гость не меняет права, проверка на сервере
            server(() -> {
                Team team = TeamService.store().teamOf(player().getUUID());
                check("гость не может менять настройки", !team.can(GOST, works.repka.rysteams.api.Permission.MANAGE));
                check("API: права офицера", TeamsApi.get().can(LENA, "invite"));
                check("API: участники", TeamsApi.get().members(team.id).size() == 4);
                // казна: по умолчанию лидер и офицер; проверка по UUID работает и для тех, кого нет в игре
                check("API: офицер тратит казну", TeamsApi.get().can(LENA, "spend_treasury"));
                check("API: участник не тратит казну", !TeamsApi.get().can(VASYA, "spend_treasury"));
                check("API: гость не тратит казну", !TeamsApi.get().can(GOST, "spend_treasury"));
            });
        }, 5);
        shot("team");
        step("team hold", () -> {}, 2);
        step("board", () -> openTab(1), 8);
        shot("board");
        step("marks", () -> openTab(2), 8);
        shot("marks");
        step("allies", () -> openTab(3), 8);
        shot("allies");
        step("settings", () -> openTab(4), 8);
        shot("settings");
        step("perm toggle", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("perm", "edit_marks");
            a.putString("role", "member");
            act("set_perm", a);
        }, 8);
        step("perm check", () -> check("матрица меняется", TeamClient.team.can(Role.MEMBER, works.repka.rysteams.api.Permission.EDIT_MARKS)), 2);
        step("treasury grant", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("perm", "spend_treasury");
            a.putString("role", "member");
            act("set_perm", a);
        }, 8);
        step("treasury check", () -> server(() -> check("лидер выдал казну участникам", TeamsApi.get().can(VASYA, "spend_treasury")
                && !TeamsApi.get().can(GOST, "spend_treasury"))), 2);
        shot("settings_treasury");
        step("merge", () -> {
            CompoundTag a = new CompoundTag();
            a.putUUID("team", OTHER_TEAM);
            act("merge_propose", a);
        }, 10);
        shot("merge_preview");
        step("merge confirm other", () -> server(() -> {
            // второго лидера в мире нет: подтверждаем за него напрямую
            try {
                var f = TeamService.class.getDeclaredField("MERGES");
                f.setAccessible(true);
                Object m = ((java.util.Map<?, ?>) f.get(null)).values().iterator().next();
                var ok = m.getClass().getDeclaredField("fromOk");
                ok.setAccessible(true);
                ok.setBoolean(m, true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            TeamService.handle(player(), "merge_confirm", new CompoundTag());
        }), 10);
        step("merge check", () -> check("слияние: участников стало 5", TeamClient.team.members.size() == 5), 2);
        shot("merge_undo");
        step("undo", () -> act("merge_undo", new CompoundTag()), 10);
        step("undo check", () -> check("откат: участников снова 4", TeamClient.team.members.size() == 4), 2);
        step("hud", () -> {
            mc().setScreen(null);
            server(() -> {
                CompoundTag pos = new CompoundTag();
                pos.putString("type", "presence");
                net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
                for (UUID id : new UUID[] {LENA, VASYA}) {
                    CompoundTag x = new CompoundTag();
                    x.putUUID("id", id);
                    x.putString("dim", id == LENA ? "minecraft:the_nether" : "minecraft:overworld");
                    x.putBoolean("pos", true);
                    x.putInt("x", -30);
                    x.putInt("y", -60);
                    x.putInt("z", 20);
                    if (id == VASYA) x.putString("status", "«Динамо» ✓");
                    list.add(x);
                }
                pos.put("list", list);
                ModNetwork.send(player(), pos);
                CompoundTag ping = new CompoundTag();
                ping.putString("kind", "help");
                ping.putInt("x", (int) player().getX() + 8);
                ping.putInt("y", (int) player().getY() - 1);
                ping.putInt("z", (int) player().getZ() + 14);
                TeamService.handle(player(), "ping", ping);
            });
        }, 15);
        shot("hud");
        step("card top left", () -> works.repka.ryscore.ui.Anchors.setCorner(works.repka.ryscore.ui.Anchors.Element.TEAM_CARD, works.repka.ryscore.ui.Anchors.Corner.TOP_LEFT), 5);
        shot("hud_card_top_left");
        step("card back", () -> works.repka.ryscore.ui.Anchors.setCorner(works.repka.ryscore.ui.Anchors.Element.TEAM_CARD, works.repka.ryscore.ui.Anchors.Corner.BOTTOM_RIGHT), 2);
        step("invite toast", () -> {
            TeamClient.invites = List.of(new TeamClient.Info(OTHER_TEAM, "Болотники", Team.COLORS[2], "minecraft:lily_pad", 3, "INVITE", "Vasya",
                System.currentTimeMillis() + 240_000));
            TeamClient.inviteShownUntil = System.currentTimeMillis() + TeamClient.INVITE_SHOW_MS;
            works.repka.rysteams.client.ClientEntry.PING.setDown(true);
        }, 24);
        step("invite in the notices", () -> check("приглашение стоит в общих уведомлениях справа вверху",
            works.repka.ryscore.ui.Notices.boundsForTest(works.repka.ryscore.ui.Notice.TEAMS, "invite:" + OTHER_TEAM) != null
                && works.repka.ryscore.ui.Notices.boundsForTest(works.repka.ryscore.ui.Notice.TEAMS, "invite:" + OTHER_TEAM)[1] == 0), 2);
        shot("invite_and_ping_bar", true);
        step("invite clear", () -> {
            TeamClient.invites = List.of();
            works.repka.rysteams.client.ClientEntry.PING.setDown(false);
        }, 2);
        step("a line and an error in the notices", () -> {
            TeamClient.toast(net.minecraft.network.chat.Component.literal("Lena_R вошла в команду"), false);
            TeamClient.toast(net.minecraft.network.chat.Component.literal("Команда уже полная"), true);
        }, 20);
        step("one line: the new one replaced the old", () -> check("строка RYSTeams одна, новая заменила прежнюю",
            works.repka.ryscore.ui.Notices.activeForTest().stream().filter(line -> line.startsWith("teams/row")).count() == 1), 2);
        shot("notice_row_bad", true);
        death();
        step("leave", () -> act("disband", new CompoundTag()), 12);
        step("left", () -> {
            check("команда распущена, игрок соло", TeamClient.team == null);
            check("архив предлагает вернуть", !TeamClient.restore.isEmpty());
        }, 2);
        step("restore", () -> act("restore", new CompoundTag()), 10);
        step("restored", () -> check("команда возвращена из архива", TeamClient.team != null), 2);
        step("admin open", () -> mc().player.connection.sendCommand("rysteams admin"), 10);
        step("admin check", () -> check("команда /rysteams admin открыла экран", mc().screen instanceof works.repka.rysteams.client.AdminScreen), 2);
        shot("admin");
        step("admin disband", () -> {
            CompoundTag a = new CompoundTag();
            a.putUUID("team", OTHER_TEAM);
            act("admin_disband", a);
        }, 10);
        step("admin disbanded", () -> check("администратор распустил чужую команду", TeamClient.teams.stream().noneMatch(i -> i.id().equals(OTHER_TEAM))), 2);
        shot("admin_archive");
        step("admin restore", () -> {
            CompoundTag a = new CompoundTag();
            a.putUUID("team", OTHER_TEAM);
            act("admin_restore", a);
        }, 10);
        step("admin restored", () -> {
            check("администратор вернул команду из архива", TeamClient.teams.stream().anyMatch(i -> i.id().equals(OTHER_TEAM)));
            mc().setScreen(null);
        }, 2);
        step("ftb import", () -> server(() -> {
            // файл в том же виде, что пишет FTB Teams: без запятых, ключи с новой строки
            UUID ftbTeam = UUID.nameUUIDFromBytes("ftb".getBytes()), owner = UUID.nameUUIDFromBytes("ftbOwner".getBytes()), officer = UUID.nameUUIDFromBytes("ftbOfficer".getBytes());
            String snbt = "{\n\tid: \"" + ftbTeam + "\"\n\ttype: \"party\"\n\towner: \"" + owner + "\"\n\tranks: {\n\t\t\"" + owner + "\": \"owner\"\n\t\t\""
                + officer + "\": \"officer\"\n\t\t\"" + UUID.randomUUID() + "\": \"ally\"\n\t}\n\tproperties: {\n\t\t\"ftbteams:display_name\": \"Старая команда\"\n\t\t\"ftbteams:color\": \"#FF8800\"\n\t\t\"ftbteams:free_to_join\": true\n\t}\n\tmessage_history: [ ]\n\textra: { }\n}\n";
            try {
                var server = mc().getSingleplayerServer();
                var dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("ftbteams").resolve("party");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.writeString(dir.resolve(ftbTeam + ".snbt"), snbt);
                var players = dir.resolveSibling("player");
                java.nio.file.Files.createDirectories(players);
                java.nio.file.Files.writeString(players.resolve(owner + ".snbt"), "{\n\tid: \"" + owner + "\"\n\ttype: \"player\"\n\tplayer_name: \"OldLeader\"\n}\n");
                var result = works.repka.rysteams.server.FtbImport.run(server);
                Team imported = TeamService.store().teams.get(ftbTeam);
                check("импорт FTB: одна команда, двое участников, союзник не участник", result.teams() == 1 && result.players() == 2);
                check("импорт FTB: ник игрока из его файла", imported != null && imported.members.get(owner).name.equals("OldLeader"));
                check("импорт FTB: имя, цвет, открытость, лидер", imported != null && imported.name.equals("Старая команда") && imported.color == 0xFF8800
                    && imported.join == Team.JoinMode.OPEN && imported.members.get(owner).role == Role.LEADER);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }), 10);
        for (int[] size : new int[][] {{2560, 1440}, {1920, 1080}}) {
            for (int scale : new int[] {2, 3, 4, 0}) {
                for (String theme : new String[] {"glass", "slate"}) {
                    String tag = size[0] + "_s" + scale + "_" + theme;
                    step("matrix " + tag, () -> {
                        mc().setScreen(null);
                        works.repka.ryscore.theme.ThemeManager.select("ryscore:" + theme);
                        mc().getWindow().setWindowed(size[0], size[1]);
                        mc().options.guiScale().set(scale);
                        mc().resizeDisplay();
                    }, 8);
                    step("matrix open " + tag, () -> openTab(0), 8);
                    shot("m_" + tag);
                    step("matrix hud " + tag, () -> mc().setScreen(null), 4);
                    shot("m_" + tag + "_hud");
                }
            }
        }
        step("matrix restore", () -> {
            works.repka.ryscore.theme.ThemeManager.select("ryscore:slate");
            mc().getWindow().setWindowed(1280, 800);
            mc().options.guiScale().set(2);
            mc().resizeDisplay();
        }, 4);
        step("finish", () -> {
            RYSTeams.LOG.info(failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", failures);
            mc().stop();
        }, 0);
    }

    /**
     * Сценарий {@code migrate}: файлы FTB сервера RepYS ({@code rysteams.autotest.fixtures}) кладутся в новый мир, шаг
     * {@code teams} идёт командой ядра дважды. Первый раз переносит VENIK с двумя игроками, второй ничего не меняет.
     */
    private static void buildMigrate() {
        UUID venik = UUID.fromString("0c383da8-5fb5-41ef-96be-7cae45e6e640");
        UUID mitya = UUID.fromString("216ea99a-d6eb-45be-8a5b-3da7852588a5");
        UUID kifa = UUID.fromString("633d6f3f-f318-4004-a3fb-b5131b22c28b");
        step("migrate first", () -> server(() -> {
            var server = mc().getSingleplayerServer();
            var world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize();
            var from = java.nio.file.Path.of(System.getProperty("rysteams.autotest.fixtures", "")).resolve("world").resolve("ftbteams");
            check("файлы FTB есть: " + from, java.nio.file.Files.isDirectory(from));
            try (var walk = java.nio.file.Files.walk(from)) {
                for (var p : (Iterable<java.nio.file.Path>) walk::iterator) {
                    var t = world.resolve("ftbteams").resolve(from.relativize(p).toString());
                    if (java.nio.file.Files.isDirectory(p)) java.nio.file.Files.createDirectories(t);
                    else java.nio.file.Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var o = works.repka.ryscore.migrate.Migrations.runManual(works.repka.rysteams.server.TeamsMigration.ID, server);
            Team team = TeamService.store().teams.get(venik);
            check("переезд: шаг прошёл (" + o + ")", o.status() == works.repka.ryscore.migrate.Migrations.Status.DONE);
            check("переезд: VENIK с двумя игроками и ролями", team != null && team.members.size() == 2
                && team.members.get(mitya).role == Role.LEADER && team.members.get(kifa).role == Role.OFFICER);
            check("переезд: отметка ядра", java.nio.file.Files.isRegularFile(world.resolve("rys/migrate.json")));
        }), 10);
        step("migrate again", () -> server(() -> {
            var server = mc().getSingleplayerServer();
            CompoundTag before = TeamService.store().teams.get(venik).save(false);
            var o = works.repka.ryscore.migrate.Migrations.runManual(works.repka.rysteams.server.TeamsMigration.ID, server);
            check("повтор: уже сделан (" + o + ")", o.status() == works.repka.ryscore.migrate.Migrations.Status.ALREADY_DONE);
            check("повтор: команда не изменилась", before.equals(TeamService.store().teams.get(venik).save(false)));
        }), 10);
        step("lobby", () -> mc().setScreen(new TeamScreen()), 10);
        shot("migrated");
        step("done", () -> {
            RYSTeams.LOG.info(failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", failures);
            mc().stop();
        }, 0);
    }

    @org.jetbrains.annotations.Nullable
    private static Team.Mark deathMark(@org.jetbrains.annotations.Nullable Team team) {
        if (team == null) return null;
        for (Team.Mark m : team.marks) if (m.death()) return m;
        return null;
    }

    private static Team serverTeam() {
        return TeamService.store().teamOf(player().getUUID());
    }

    /** Отойти на сорок блоков и погибнуть по-настоящему: /kill идёт через LivingDeathEvent и в творческом режиме. */
    private static void dieAway(String tag) {
        step("death away " + tag, () -> server(() -> {
            ServerPlayer p = player();
            p.teleportTo(p.getX(), p.getY(), p.getZ() + 40);
        }), 5);
        step("death kill " + tag, () -> server(() -> {
            feedBefore = serverTeam().feed.size();
            player().kill();
        }), 10);
    }

    private static void respawn(String tag) {
        step("death respawn " + tag, () -> {
            mc().player.respawn();
            mc().setScreen(null);
        }, 5);
        STEPS.add(new Step("death alive " + tag, () -> mc().player != null && mc().player.isAlive(), () -> mc().setScreen(null), 30));
    }

    private static int feedBefore;

    /** Гибель участника: запись с тревогой, метка вне лимита, подход снимает метку, отказ и скрытие координат. */
    private static void death() {
        step("death notice default", () -> check("гибель: «Сообщать о моей смерти» включено по умолчанию", TeamClient.deathNotice), 1);
        dieAway("1");
        step("death check", () -> server(() -> {
            Team team = serverTeam();
            Team.Mark m = deathMark(team);
            long now = System.currentTimeMillis();
            check("гибель: метка с источником rysteams:death и владельцем", m != null && player().getUUID().equals(m.owner()) && Team.SOURCE_DEATH.equals(m.source()));
            check("гибель: метка снимется через 30 минут", m != null && m.expires() > now + 29 * 60_000L && m.expires() <= now + 30 * 60_000L);
            check("гибель: метка не входит в лимит", team.permanentMarks() == team.marks.size() - 1);
            Team.Mark back = deathMark(Team.load(team.save(false)));
            check("гибель: срок и владелец переживают сохранение", back != null && m != null && back.expires() == m.expires() && m.owner().equals(back.owner()));
            Team.Feed f = team.feed.peekFirst();
            check("гибель: запись в ленте с тревогой и координатами", team.feed.size() == feedBefore + 1 && f != null && f.alert()
                && f.text().contains("rysteams.feed.death\"") && f.action().startsWith("waypoint:"));
        }), 5);
        step("death client", () -> check("гибель: метка пришла клиенту", deathMark(TeamClient.team) != null), 1);
        respawn("1");
        step("death far", () -> {
            Team.Mark m = deathMark(TeamClient.team);
            check("гибель: вдали от метки она остаётся", m != null);
            if (m != null) mc().player.connection.sendCommand("tp @s ~ ~ ~ facing " + m.x() + " " + m.y() + " " + m.z());
        }, 10);
        shot("death_mark");
        step("death feed", () -> openTab(0), 8);
        shot("death_feed");
        step("death approach", () -> {
            mc().setScreen(null);
            server(() -> {
                Team.Mark m = deathMark(serverTeam());
                if (m != null) player().teleportTo(m.x() + 0.5, m.y(), m.z() + 2.5);
            });
        }, 45);
        step("death reached", () -> server(() -> check("гибель: погибший подошёл ближе 6 блоков, метка снята", deathMark(serverTeam()) == null)), 5);
        step("death reached client", () -> check("гибель: метка снята и у клиента", deathMark(TeamClient.team) == null), 1);
        step("death quiet", () -> {
            CompoundTag a = new CompoundTag();
            a.putBoolean("on", false);
            act("death_notice", a);
        }, 8);
        step("death quiet state", () -> check("гибель: отказ от сообщения сохранён на сервере", !TeamClient.deathNotice), 1);
        dieAway("quiet");
        step("death quiet check", () -> server(() -> check("гибель: при отказе нет ни метки, ни записи",
            deathMark(serverTeam()) == null && serverTeam().feed.size() == feedBefore)), 2);
        respawn("quiet");
        step("death loud", () -> {
            CompoundTag a = new CompoundTag();
            a.putBoolean("on", true);
            act("death_notice", a);
            CompoundTag h = new CompoundTag();
            h.putBoolean("on", true);
            act("hide", h);
        }, 200);
        dieAway("hidden");
        step("death hidden check", () -> server(() -> {
            Team team = serverTeam();
            Team.Feed f = team.feed.peekFirst();
            check("гибель со скрытыми координатами: метки нет", deathMark(team) == null);
            check("гибель со скрытыми координатами: в ленте только измерение", team.feed.size() == feedBefore + 1 && f != null
                && f.text().contains("rysteams.feed.death_hidden") && f.action().isEmpty());
        }), 2);
        respawn("hidden");
        step("death unhide", () -> {
            CompoundTag h = new CompoundTag();
            h.putBoolean("on", false);
            act("hide", h);
        }, 8);
    }

    private static void openTab(int index) {
        var screen = new TeamScreen();
        mc().setScreen(screen);
        try {
            var field = TeamScreen.class.getDeclaredField("tab");
            field.setAccessible(true);
            field.set(null, field.getType().getEnumConstants()[index]);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
