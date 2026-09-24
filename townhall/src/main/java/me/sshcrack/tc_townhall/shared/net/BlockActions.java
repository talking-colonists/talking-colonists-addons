// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_townhall.shared.net;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
/*? if neoforge {*/
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
/*?}*/
/*? if forge {*/
/*import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
*//*?}*/

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client to server: a button in a block's window was clicked, e.g. "take note 2" at the suggestion
 * box. One small packet per addon carries the block position, an action name and an integer. The
 * server runs the registered handler on its own thread, only for players standing close to the block.
 */
public final class BlockActions {
    /** Runs on the server thread for a player within {@link #MAX_DISTANCE} of {@code pos}. */
    public interface Handler {
        void handle(ServerPlayer player, BlockPos pos, int argument);
    }

    static final double MAX_DISTANCE = 8;
    private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();

    private BlockActions() {
    }

    public static void on(String action, Handler handler) {
        HANDLERS.put(action, handler);
    }

    static void handle(ServerPlayer player, BlockPos pos, String action, int argument) {
        Handler handler = HANDLERS.get(action);
        if (handler == null || !player.level().isLoaded(pos)) return;
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE * MAX_DISTANCE) return;
        handler.handle(player, pos, argument);
    }

    /*? if neoforge {*/
    record Payload(BlockPos pos, String action, int argument) implements CustomPacketPayload {
        static CustomPacketPayload.Type<Payload> type;
        static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Payload::pos,
                ByteBufCodecs.stringUtf8(64), Payload::action,
                ByteBufCodecs.VAR_INT, Payload::argument,
                Payload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return type;
        }
    }

    /** Call once from the mod constructor. */
    public static void init(String modId, IEventBus modBus) {
        Payload.type = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(modId, "block_action"));
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToServer(Payload.type, Payload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) handle(player, payload.pos(), payload.action(), payload.argument());
                })));
    }

    /** Client side: tell the server a button was clicked. */
    public static void send(BlockPos pos, String action, int argument) {
        PacketDistributor.sendToServer(new Payload(pos, action, argument));
    }
    /*?}*/

    /*? if forge {*/
    /*record Message(BlockPos pos, String action, int argument) {
    }

    private static SimpleChannel channel;

    // Call once from the mod constructor.
    public static void init(String modId) {
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(modId, "block_action"), () -> "1", "1"::equals, "1"::equals);
        channel.messageBuilder(Message.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buf) -> {
                    buf.writeBlockPos(message.pos());
                    buf.writeUtf(message.action(), 64);
                    buf.writeVarInt(message.argument());
                })
                .decoder(buf -> new Message(buf.readBlockPos(), buf.readUtf(64), buf.readVarInt()))
                .consumerMainThread((message, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) handle(player, message.pos(), message.action(), message.argument());
                })
                .add();
    }

    // Client side: tell the server a button was clicked.
    public static void send(BlockPos pos, String action, int argument) {
        channel.sendToServer(new Message(pos, action, argument));
    }
    *//*?}*/
}
