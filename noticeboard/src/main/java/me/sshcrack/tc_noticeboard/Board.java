package me.sshcrack.tc_noticeboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.memory.BroadcastPublishResult;
import me.sshcrack.mc_talking.api.memory.BroadcastRequest;
import me.sshcrack.mc_talking.api.memory.BroadcastSource;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.tc_noticeboard.shared.book.BookText;
import me.sshcrack.tc_noticeboard.shared.book.WrittenBooks;
import me.sshcrack.tc_noticeboard.shared.provider.TextCapacity;
import me.sshcrack.tc_noticeboard.shared.store.JsonFile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Notices: a signed book on a lectern inside a colony. Posting tells the citizens near the board, who
 * spread it; a few minutes later up to {@link #MAX_REPLIES} of them write short replies, which are
 * pinned into the book as extra pages. Also the loudspeaker. Server thread only.
 */
public final class Board {
    static final int MAX_REPLIES = 3;
    /** Replies come two to four minutes after posting. */
    static final long REPLY_DELAY_TICKS = 2_400;
    static final long REPLY_SPREAD_TICKS = 2_400;
    static final double REPLIER_RANGE = 64;
    static final int MAX_FAILURES = 4;
    static final Duration NOTICE_LIFETIME = Duration.ofHours(3);
    private static final int CHECK_INTERVAL_TICKS = 100;

    /** A posted notice waiting for (or collecting) replies. */
    public static final class Notice {
        public UUID id = UUID.randomUUID();
        public String colonyKey = "";
        public BlockPos pos = BlockPos.ZERO;
        public String title = "";
        public String body = "";
        public UUID posterId = new UUID(0, 0);
        public String posterName = "";
        public long repliesDue;
        public List<Integer> replied = new ArrayList<>();
        public List<String> replies = new ArrayList<>();
        public int failures;
        transient boolean writing;
    }

    private final MinecraftServer server;
    private final Path file;
    private final List<Notice> notices = new ArrayList<>();
    private int ticks;

    Board(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    public List<Notice> notices() {
        return notices;
    }

    private long now() {
        return server.overworld().getGameTime();
    }

    /**
     * Posts the book on the lectern at {@code pos} as a notice, if the lectern is inside a colony the
     * player belongs to. Tells the player the outcome. Returns whether it was posted.
     */
    public boolean post(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern) || !lectern.hasBook()) return false;
        BookText text = BookText.read(lectern.getBook());
        IColony colony = IColonyManager.getInstance().getIColony(level, pos);
        if (text == null || colony == null || !colony.getPermissions().isColonyMember(player)) return false;
        notices.removeIf(notice -> notice.pos.equals(pos) && notice.colonyKey.equals(key(colony)));

        String message = NoticeText.broadcast(text.title(), text.body());
        BroadcastSource source = BroadcastSource.block(pos, NoticeText.SOURCE_NAME);
        BroadcastPublishResult result = CitizenMemoryService.publishBroadcast(colony,
                BroadcastRequest.fromPosition(source, message, pos).withExpiry(NOTICE_LIFETIME));
        if (result.status() == BroadcastPublishResult.Status.NO_RECIPIENTS) {
            // Nobody stands at the board right now: the nearest loaded citizen reads it first.
            AbstractEntityCitizen nearest = repliers(colony, level, pos, List.of()).stream().findFirst().orElse(null);
            if (nearest != null) {
                result = CitizenMemoryService.publishBroadcast(colony,
                        BroadcastRequest.fromCitizen(source, message, nearest.getUUID()).withExpiry(NOTICE_LIFETIME));
            }
        }
        if (!result.isPublished()) {
            tell(player, switch (result.status()) {
                case RATE_LIMITED -> "The colony has had a lot of news lately; post the notice again in a while.";
                case DISABLED -> "Broadcasts are turned off on this server, so nobody reads the board.";
                case NO_RECIPIENTS -> "No citizen is around to read the notice yet.";
                default -> "The notice could not be posted.";
            });
            return false;
        }
        Notice notice = new Notice();
        notice.colonyKey = key(colony);
        notice.pos = pos.immutable();
        notice.title = text.title();
        notice.body = text.body();
        notice.posterId = player.getUUID();
        notice.posterName = player.getGameProfile().getName();
        notice.repliesDue = now() + REPLY_DELAY_TICKS + level.getRandom().nextInt((int) REPLY_SPREAD_TICKS);
        notices.add(notice);
        save();
        tell(player, "Posted \"" + text.title() + "\". Citizens near the board read it and spread the word; "
                + "replies get pinned into the book in a few minutes.");
        return true;
    }

    /** {@code /loudspeaker}: every citizen of the colony hears the message now. */
    public BroadcastPublishResult announce(ServerPlayer player, IColony colony, String message) {
        return CitizenMemoryService.publishBroadcast(colony,
                BroadcastRequest.immediate(BroadcastSource.player(player.getUUID(), player.getGameProfile().getName()), message));
    }

    /** Operators: all notices collect their replies on the next check. */
    public int rush() {
        notices.forEach(notice -> notice.repliesDue = Math.min(notice.repliesDue, now()));
        return notices.size();
    }

    void tick() {
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return;
        for (Notice notice : List.copyOf(notices)) {
            if (!notice.writing && notice.repliesDue <= now()) collectReply(notice);
        }
    }

    private void collectReply(Notice notice) {
        IColony colony = colony(notice.colonyKey);
        ServerLevel level = colony == null || !(colony.getWorld() instanceof ServerLevel world) ? null : world;
        LecternBlockEntity lectern = level != null && level.isLoaded(notice.pos)
                && level.getBlockEntity(notice.pos) instanceof LecternBlockEntity found ? found : null;
        if (colony == null || level == null) {
            drop(notice);
            return;
        }
        if (lectern == null) return; // unloaded: try again later
        BookText text = lectern.hasBook() ? BookText.read(lectern.getBook()) : null;
        if (text == null || !text.title().equals(notice.title)) {
            drop(notice); // the notice was taken down
            return;
        }
        List<AbstractEntityCitizen> candidates = repliers(colony, level, notice.pos, notice.replied);
        if (notice.replies.size() >= MAX_REPLIES || candidates.isEmpty() || notice.failures >= MAX_FAILURES) {
            finish(notice, lectern);
            return;
        }
        if (!TextCapacity.hasSpare()) return;
        AbstractEntityCitizen replier = candidates.get(0);
        ICitizenData data = replier.getCitizenData();
        notice.writing = true;
        notice.replied.add(data.getId());
        TextRequest request = TextRequest.of(NoticeBoard.MOD_ID + ":reply",
                NoticeText.replyDirective(notice.posterName, notice.title, notice.body)).withMaxChars(NoticeText.MAX_REPLY_CHARS);
        CitizenTextService.generate(replier, request).whenComplete((result, error) -> server.execute(() -> {
            notice.writing = false;
            if (error == null && result.isSuccess() && !result.text().isBlank()) {
                notice.replies.add(NoticeText.replyPage(data.getName(), role(data), NoticeText.cleanReply(result.text())));
            } else {
                notice.failures++;
                NoticeBoard.LOGGER.warn("A reply to notice \"{}\" failed: {}", notice.title,
                        error != null ? error.toString() : result.status() + " " + result.detail());
            }
            save();
        }));
    }

    /** Pins the replies into the book and tells the poster. */
    private void finish(Notice notice, LecternBlockEntity lectern) {
        notices.remove(notice);
        save();
        if (notice.replies.isEmpty()) return;
        ItemStack book = lectern.getBook();
        List<Component> pages = new ArrayList<>();
        for (String reply : notice.replies) {
            pages.add(Component.literal(reply));
        }
        WrittenBooks.appendPages(book, pages);
        lectern.setChanged();
        String summary = notice.replies.size() + (notice.replies.size() == 1 ? " citizen" : " citizens")
                + " pinned a reply to \"" + notice.title + "\". Read them on the lectern.";
        ServerPlayer poster = server.getPlayerList().getPlayer(notice.posterId);
        if (poster != null) tell(poster, summary);
        NoticeBoard.LOGGER.info("Pinned {} replies to notice \"{}\"", notice.replies.size(), notice.title);
    }

    private void drop(Notice notice) {
        notices.remove(notice);
        save();
    }

    /** Loaded citizens near the board who did not reply yet, nearest first. */
    private static List<AbstractEntityCitizen> repliers(IColony colony, ServerLevel level, BlockPos pos, List<Integer> done) {
        Vec3 board = Vec3.atCenterOf(pos);
        List<AbstractEntityCitizen> near = new ArrayList<>();
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            if (done.contains(data.getId())) continue;
            AbstractEntityCitizen citizen = data.getEntity().orElse(null);
            if (citizen == null || citizen.level() != level || !citizen.isAlive()) continue;
            if (citizen.position().distanceToSqr(board) <= REPLIER_RANGE * REPLIER_RANGE) near.add(citizen);
        }
        near.sort(Comparator.comparingDouble(citizen -> citizen.position().distanceToSqr(board)));
        return near;
    }

    private static String role(ICitizenData data) {
        try {
            return data.getJob() == null ? "" : data.getJob().getJobRegistryEntry().getKey().getPath().replace('_', ' ');
        } catch (RuntimeException e) {
            return "";
        }
    }

    static void tell(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[Notice board] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    private static @Nullable IColony colony(String key) {
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (key(colony).equals(key)) return colony;
        }
        return null;
    }

    static String key(IColony colony) {
        return colony.getDimension().location() + "|" + colony.getID();
    }

    void load() {
        notices.clear();
        try {
            JsonObject root = JsonFile.read(file);
            if (root == null || !(root.get("notices") instanceof JsonArray array)) return;
            for (JsonElement element : array) {
                JsonObject json = element.getAsJsonObject();
                Notice notice = new Notice();
                notice.id = UUID.fromString(json.get("id").getAsString());
                notice.colonyKey = json.get("colonyKey").getAsString();
                notice.pos = BlockPos.of(json.get("pos").getAsLong());
                notice.title = json.get("title").getAsString();
                notice.body = json.get("body").getAsString();
                notice.posterId = UUID.fromString(json.get("posterId").getAsString());
                notice.posterName = json.get("posterName").getAsString();
                notice.repliesDue = json.get("repliesDue").getAsLong();
                json.getAsJsonArray("replied").forEach(id -> notice.replied.add(id.getAsInt()));
                json.getAsJsonArray("replies").forEach(reply -> notice.replies.add(reply.getAsString()));
                notice.failures = json.get("failures").getAsInt();
                notices.add(notice);
            }
        } catch (IOException | RuntimeException e) {
            NoticeBoard.LOGGER.error("Could not read {}; starting with no notices", file, e);
        }
    }

    void save() {
        JsonArray array = new JsonArray();
        for (Notice notice : notices) {
            JsonObject json = new JsonObject();
            json.addProperty("id", notice.id.toString());
            json.addProperty("colonyKey", notice.colonyKey);
            json.addProperty("pos", notice.pos.asLong());
            json.addProperty("title", notice.title);
            json.addProperty("body", notice.body);
            json.addProperty("posterId", notice.posterId.toString());
            json.addProperty("posterName", notice.posterName);
            json.addProperty("repliesDue", notice.repliesDue);
            JsonArray replied = new JsonArray();
            notice.replied.forEach(replied::add);
            json.add("replied", replied);
            JsonArray replies = new JsonArray();
            notice.replies.forEach(replies::add);
            json.add("replies", replies);
            json.addProperty("failures", notice.failures);
            array.add(json);
        }
        JsonObject root = new JsonObject();
        root.add("notices", array);
        try {
            JsonFile.write(file, root);
        } catch (IOException e) {
            NoticeBoard.LOGGER.error("Could not save {}", file, e);
        }
    }
}
