package works.repka.rysteams.network;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import works.repka.rysteams.RYSTeams;
import works.repka.rysteams.client.TeamClient;
import works.repka.rysteams.server.TeamService;

/** Два канала: сервер шлёт клиенту состояние, клиент серверу действия. Формат тела NBT, как и в сохранении. */
public final class ModNetwork {
    private ModNetwork() {}

    /** Сервер → клиент: поле {@code type} говорит, что внутри. */
    public record ToClient(CompoundTag data) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "to_client"));
        public static final StreamCodec<FriendlyByteBuf, ToClient> CODEC = ByteBufCodecs.COMPOUND_TAG.map(ToClient::new, ToClient::data).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент → сервер: имя действия и его параметры. Права проверяет сервер. */
    public record Action(String action, CompoundTag args) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSTeams.MODID, "action"));
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), Action::action, ByteBufCodecs.COMPOUND_TAG, Action::args, Action::new).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // версия меняется вместе с форматом пакетов: иначе разные версии мода молча расходятся. "2": requests списком, общие teams и online, cancel_request.
        // "3": метки со сроком и владельцем, настройка deathNotice и действие death_notice
        PayloadRegistrar registrar = event.registrar("3");
        registrar.playToClient(ToClient.TYPE, ToClient.CODEC, ModNetwork::onClient);
        registrar.playToServer(Action.TYPE, Action.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) TeamService.handle(player, payload.action(), payload.args());
        });
    }

    private static void onClient(ToClient payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) Client.accept(payload.data());
    }

    /** Отдельный класс: на выделенном сервере клиентские классы не загружаются. */
    private static final class Client {
        static void accept(CompoundTag data) {
            if (Minecraft.getInstance().level != null || "state".equals(data.getString("type"))) TeamClient.accept(data);
        }
    }

    public static void send(ServerPlayer player, CompoundTag data) {
        PacketDistributor.sendToPlayer(player, new ToClient(data));
    }

    /** С клиента: выполнить действие на сервере. */
    public static void action(String action, CompoundTag args) {
        PacketDistributor.sendToServer(new Action(action, args));
    }
}
