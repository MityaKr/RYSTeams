package works.repka.rysteams;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysteams.api.Permission;
import works.repka.rysteams.data.Team;
import works.repka.rysteams.network.ModNetwork;
import works.repka.rysteams.server.FtbImport;
import works.repka.rysteams.server.TeamService;
import works.repka.rysteams.server.TeamStore;

/** Точка входа: общая для клиента и сервера. */
@Mod(RYSTeams.MODID)
public final class RYSTeams {
    public static final String MODID = "rysteams";
    public static final Logger LOG = LoggerFactory.getLogger(RYSTeams.class);

    public RYSTeams(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, TeamsConfig.SPEC);
        modBus.addListener(ModNetwork::register);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) works.repka.rysteams.client.ClientEntry.init(modBus, container);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> TeamService.start(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> TeamService.stop());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> TeamService.tick());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) TeamService.login(p);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && TeamService.running()) TeamService.logout(p);
        });
        // последним и только неотменённая: мод воскрешения может отменить гибель. Механизмы (FakePlayer) участниками не бывают
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer) && TeamService.running()) TeamService.onDeath(p);
        });
        NeoForge.EVENT_BUS.addListener((ServerChatEvent e) -> {
            if (TeamService.chat(e.getPlayer(), e.getRawText())) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> registerCommands(e.getDispatcher()));
        TeamsApi.provide(new Api());
        LOG.info("RYSTeams загружен");
    }

    /** Реализация контракта кора. */
    private static final class Api implements TeamsApi {
        @Override
        public UUID teamOf(UUID player) {
            if (!TeamService.running()) return player;
            UUID team = TeamService.store().byPlayer.get(player);
            return team == null ? player : team;
        }

        @Override
        public Collection<UUID> members(UUID team) {
            if (!TeamService.running()) return List.of(team);
            Team t = TeamService.store().teams.get(team);
            return t == null ? List.of(team) : List.copyOf(t.members.keySet());
        }

        @Override
        public boolean can(UUID player, String permission) {
            if (!TeamService.running()) return true;
            Team t = TeamService.store().teamOf(player);
            Permission p = Permission.parse(permission);
            return t == null || p == null || t.can(player, p);
        }

        @Override
        public void post(UUID team, Post post) {
            TeamService.post(team, post);
        }

        @Override
        public net.minecraft.nbt.CompoundTag getData(UUID team, net.minecraft.resources.ResourceLocation key) {
            if (!TeamService.running()) return null;
            Team t = TeamService.store().teams.get(team);
            return t == null || !t.modData.contains(key.toString()) ? null : t.modData.getCompound(key.toString());
        }

        @Override
        public void putData(UUID team, net.minecraft.resources.ResourceLocation key, net.minecraft.nbt.CompoundTag value) {
            if (!TeamService.running()) return;
            Team t = TeamService.store().teams.get(team);
            if (t == null) return;
            t.modData.put(key.toString(), value);
            TeamService.store().setDirty();
        }

        @Override
        public void setStatus(UUID player, String status) {
            works.repka.rysteams.server.Presence.setStatus(player, status);
        }

        @Override
        public void addTodo(UUID team, net.minecraft.resources.ResourceLocation source, String text) {
            TeamService.addTodo(team, source.getPath(), text);
        }

        @Override
        public void ping(UUID team, String dimension, int x, int y, int z, String kind, String by) {
            TeamService.pingFromMod(team, dimension, x, y, z, kind, by);
        }
    }

    /** Команды не обязательны: ссылка из чата и действия администратора. */
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(MODID)
            .then(Commands.literal("accept").then(Commands.argument("team", StringArgumentType.word()).executes(c -> {
                ServerPlayer player = c.getSource().getPlayerOrException();
                TeamService.inviteReply(player, UUID.fromString(StringArgumentType.getString(c, "team")), "accept");
                return Command.SINGLE_SUCCESS;
            })))
            .then(Commands.literal("admin").requires(s -> s.hasPermission(2)).executes(c -> {
                TeamService.sendAdmin(c.getSource().getPlayerOrException());
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("list").requires(s -> s.hasPermission(2)).executes(c -> {
                TeamStore store = TeamService.store();
                c.getSource().sendSuccess(() -> Component.translatable("rysteams.admin.count", store.teams.size()), false);
                for (Team team : store.teams.values()) {
                    c.getSource().sendSuccess(() -> Component.literal(team.name + " · " + team.members.size() + " · " + team.id), false);
                }
                return store.teams.size();
            }))
            .then(Commands.literal("import").requires(s -> s.hasPermission(3)).then(Commands.literal("ftb").executes(c -> {
                try {
                    FtbImport.Result r = FtbImport.run(c.getSource().getServer());
                    c.getSource().sendSuccess(() -> Component.translatable("rysteams.admin.imported", r.teams(), r.players(), r.skipped()), true);
                    return r.teams();
                } catch (java.io.IOException e) {
                    c.getSource().sendFailure(Component.literal(e.toString()));
                    return 0;
                }
            }))));
    }
}
