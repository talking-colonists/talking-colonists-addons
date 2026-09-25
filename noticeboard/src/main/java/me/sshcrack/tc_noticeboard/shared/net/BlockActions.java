// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_noticeboard.shared.net;

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
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
/*?}*/
/*? if forge {*/
/*import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
*//*?}*/

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The small network layer behind the addons' block windows, one channel per addon.
 * <ul>
 * <li>Client to server: a button in a block's window was clicked, e.g. "take note 2" at the suggestion
 * box. The packet carries the block position, an action name, an integer and a short text. The server
 * runs the registered {@link Handler} on its own thread, only for players standing close to the block.</li>
 * <li>Server to client: a view of what the block shows, e.g. the running election, as a JSON string of
 * the addon's choosing. The client runs the registered {@link ViewHandler} on its main thread.</li>
 * </ul>
 */
public final class BlockActions {
    /** Runs on the server thread for a player within {@link #MAX_DISTANCE} of {@code pos}. */
    public interface Handler {
        void handle(ServerPlayer player, BlockPos pos, int argument, String text);
    }

    /** Runs on the client's main thread. */
    public interface ViewHandler {
        void show(BlockPos pos, String json);
    }

    static final double MAX_DISTANCE = 8;
    public static final int MAX_TEXT = 2_000;
    public static final int MAX_VIEW = 60_000;
    private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();
    private static final Map<String, ViewHandler> VIEWS = new ConcurrentHashMap<>();

    private BlockActions() {
    }

    public static void on(String action, Handler handler) {
        HANDLERS.put(action, handler);
    }

    /** Client side: what to do with a view of kind {@code kind} the server sends. */
    public static void onView(String kind, ViewHandler handler) {
        VIEWS.put(kind, handler);
    }

    static void handle(ServerPlayer player, BlockPos pos, String action, int argument, String text) {
        Handler handler = HANDLERS.get(action);
        if (handler == null || !player.level().isLoaded(pos)) return;
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE * MAX_DISTANCE) return;
        handler.handle(player, pos, argument, text);
    }

    static void show(BlockPos pos, String kind, String json) {
        ViewHandler handler = VIEWS.get(kind);
        if (handler != null) handler.show(pos, json);
    }

    /** Client side: tell the server a button was clicked. */
    public static void send(BlockPos pos, String action, int argument) {
        send(pos, action, argument, "");
    }

    private static String fit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /*? if neoforge {*/
    record Payload(BlockPos pos, String action, int argument, String text) implements CustomPacketPayload {
        static CustomPacketPayload.Type<Payload> type;
        static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Payload::pos,
                ByteBufCodecs.stringUtf8(64), Payload::action,
                ByteBufCodecs.VAR_INT, Payload::argument,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Payload::text,
                Payload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return type;
        }
    }

    record ViewPayload(BlockPos pos, String kind, String json) implements CustomPacketPayload {
        static CustomPacketPayload.Type<ViewPayload> type;
        static final StreamCodec<RegistryFriendlyByteBuf, ViewPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ViewPayload::pos,
                ByteBufCodecs.stringUtf8(64), ViewPayload::kind,
                ByteBufCodecs.stringUtf8(MAX_VIEW), ViewPayload::json,
                ViewPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return type;
        }
    }

    /** Call once from the mod constructor. */
    public static void init(String modId, IEventBus modBus) {
        Payload.type = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(modId, "block_action"));
        ViewPayload.type = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(modId, "block_view"));
        modBus.addListener((RegisterPayloadHandlersEvent event) -> {
            PayloadRegistrar registrar = event.registrar("1");
            registrar.playToServer(Payload.type, Payload.CODEC, (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    handle(player, payload.pos(), payload.action(), payload.argument(), payload.text());
                }
            }));
            registrar.playToClient(ViewPayload.type, ViewPayload.CODEC,
                    (payload, context) -> context.enqueueWork(() -> show(payload.pos(), payload.kind(), payload.json())));
        });
    }

    /** Client side: tell the server a button was clicked, with a short text (at most {@link #MAX_TEXT} characters). */
    public static void send(BlockPos pos, String action, int argument, String text) {
        PacketDistributor.sendToServer(new Payload(pos, action, argument, fit(text, MAX_TEXT)));
    }

    /** Server side: send {@code player} a view of the block at {@code pos}. */
    public static void view(ServerPlayer player, BlockPos pos, String kind, String json) {
        PacketDistributor.sendToPlayer(player, new ViewPayload(pos, kind, fit(json, MAX_VIEW)));
    }
    /*?}*/

    /*? if forge {*/
    /*record Message(BlockPos pos, String action, int argument, String text) {
    }

    record ViewMessage(BlockPos pos, String kind, String json) {
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
                    buf.writeUtf(message.text(), MAX_TEXT);
                })
                .decoder(buf -> new Message(buf.readBlockPos(), buf.readUtf(64), buf.readVarInt(), buf.readUtf(MAX_TEXT)))
                .consumerMainThread((message, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) handle(player, message.pos(), message.action(), message.argument(), message.text());
                })
                .add();
        channel.messageBuilder(ViewMessage.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buf) -> {
                    buf.writeBlockPos(message.pos());
                    buf.writeUtf(message.kind(), 64);
                    buf.writeUtf(message.json(), MAX_VIEW);
                })
                .decoder(buf -> new ViewMessage(buf.readBlockPos(), buf.readUtf(64), buf.readUtf(MAX_VIEW)))
                .consumerMainThread((message, context) -> show(message.pos(), message.kind(), message.json()))
                .add();
    }

    // Client side: tell the server a button was clicked, with a short text (at most MAX_TEXT characters).
    public static void send(BlockPos pos, String action, int argument, String text) {
        channel.sendToServer(new Message(pos, action, argument, fit(text, MAX_TEXT)));
    }

    // Server side: send the player a view of the block at pos.
    public static void view(ServerPlayer player, BlockPos pos, String kind, String json) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new ViewMessage(pos, kind, fit(json, MAX_VIEW)));
    }
    *//*?}*/
}
