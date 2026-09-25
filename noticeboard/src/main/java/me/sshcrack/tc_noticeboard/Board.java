package me.sshcrack.tc_noticeboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.memory.BroadcastPublishResult;
import me.sshcrack.mc_talking.api.memory.BroadcastReach;
import me.sshcrack.mc_talking.api.memory.BroadcastRequest;
import me.sshcrack.mc_talking.api.memory.BroadcastSource;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.tc_noticeboard.block.NoticeBoardBlockEntity;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Notices: typed in a Notice Board block's window, or a signed book put on a lectern, inside a colony.
 * Posting tells the citizens near the board, who spread it; a few minutes later up to
 * {@link #MAX_REPLIES} of them write short replies, pinned on the board as they come (or into the
 * lectern's book as extra pages at the end). Also announcements: from the board's window, or by ringing
 * a bell with a signed book, the whole colony hears it at once. Server thread only.
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
        /** Pinned on a Notice Board block rather than a lectern. */
        public boolean onBoard;
        transient boolean writing;
    }

    /**
     * A posted notice whose spread the poster hears about: once half the colony knows, and once
     * everyone does. Outlives the replies, as news keeps spreading; not saved across restarts.
     */
    private static final class ReachWatch {
        final String colonyKey;
        final String broadcastId;
        final String title;
        final UUID posterId;
        final long until;
        /** The Notice Board block showing the reach, or null for a lectern. */
        final @Nullable BlockPos board;
        int milestone;

        ReachWatch(String colonyKey, String broadcastId, String title, UUID posterId, long until, @Nullable BlockPos board) {
            this.colonyKey = colonyKey;
            this.broadcastId = broadcastId;
            this.title = title;
            this.posterId = posterId;
            this.until = until;
            this.board = board;
        }
    }

    private final MinecraftServer server;
    private final Path file;
    private final List<Notice> notices = new ArrayList<>();
    private final List<ReachWatch> reach = new ArrayList<>();
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
        return publish(player, colony, level, pos, text.title(), text.body(), false) != null;
    }

    /**
     * Posts a notice typed in the window of the Notice Board block at {@code pos} and pins it there,
     * replacing what hung there before. Tells the player the outcome. Returns whether it was posted.
     */
    public boolean postOnBoard(ServerPlayer player, ServerLevel level, BlockPos pos, String title, String body) {
        if (!(level.getBlockEntity(pos) instanceof NoticeBoardBlockEntity entity)) return false;
        IColony colony = memberColony(player, level, pos);
        if (colony == null) return false;
        if (title.isBlank() || body.isBlank()) {
            tell(player, "A notice needs a title and some text.");
            return false;
        }
        Notice notice = publish(player, colony, level, pos, title.strip(), body.strip(), true);
        if (notice == null) return false;
        entity.pin(notice.id.toString(), notice.title, notice.body, notice.posterName, colony.getDay());
        return true;
    }

    /** Takes the notice and its replies off the Notice Board block at {@code pos}. */
    public void takeDown(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof NoticeBoardBlockEntity entity) || memberColony(player, level, pos) == null) return;
        entity.takeDown();
        notices.removeIf(notice -> notice.onBoard && notice.pos.equals(pos));
        reach.removeIf(watch -> pos.equals(watch.board));
        save();
    }

    /** The colony at {@code pos} if the player is a member of it; otherwise tells them why not. */
    private static @Nullable IColony memberColony(ServerPlayer player, ServerLevel level, BlockPos pos) {
        IColony colony = IColonyManager.getInstance().getIColony(level, pos);
        if (colony == null) {
            tell(player, "Place the notice board inside your colony.");
            return null;
        }
        if (!colony.getPermissions().isColonyMember(player)) {
            tell(player, "Only members of " + colony.getName() + " can use its notice board.");
            return null;
        }
        return colony;
    }

    private @Nullable Notice publish(ServerPlayer player, IColony colony, ServerLevel level, BlockPos pos, String title, String body,
            boolean onBoard) {
        notices.removeIf(notice -> notice.pos.equals(pos) && notice.colonyKey.equals(key(colony)));

        String message = NoticeText.broadcast(title, body);
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
            return null;
        }
        Notice notice = new Notice();
        notice.colonyKey = key(colony);
        notice.pos = pos.immutable();
        notice.title = title;
        notice.body = body;
        notice.onBoard = onBoard;
        notice.posterId = player.getUUID();
        notice.posterName = player.getGameProfile().getName();
        notice.repliesDue = now() + REPLY_DELAY_TICKS + level.getRandom().nextInt((int) REPLY_SPREAD_TICKS);
        notices.add(notice);
        save();
        watchReach(colony, result, title, player.getUUID(), onBoard ? notice.pos : null);
        tell(player, "Posted \"" + title + "\". Citizens near the board read it and spread the word; "
                + (onBoard ? "their replies get pinned under it over the next minutes." : "replies get pinned into the book in a few minutes."));
        return notice;
    }

    /** Every citizen of the colony hears the message now, attributed to the player. */
    public BroadcastPublishResult announce(ServerPlayer player, IColony colony, String message) {
        return CitizenMemoryService.publishBroadcast(colony,
                BroadcastRequest.immediate(BroadcastSource.player(player.getUUID(), player.getGameProfile().getName()), message));
    }

    /**
     * The player rings a bell inside their colony while holding a signed book: every citizen hears
     * the book at once (the bell itself rings as usual). Returns whether it was announced.
     */
    public boolean ringBell(ServerPlayer player, ServerLevel level, BlockPos bell, ItemStack book) {
        BookText text = BookText.read(book);
        IColony colony = IColonyManager.getInstance().getIColony(level, bell);
        if (text == null || colony == null || !colony.getPermissions().isColonyMember(player)) return false;
        return announce(player, colony, text.title(), text.body(), "The bell rings out");
    }

    /** "Announce" in the notice board window: the whole colony hears it at once; nothing is pinned. */
    public boolean announceFromBoard(ServerPlayer player, ServerLevel level, BlockPos pos, String title, String body) {
        IColony colony = memberColony(player, level, pos);
        if (colony == null) return false;
        if (title.isBlank() && body.isBlank()) {
            tell(player, "Write what the colony should hear first.");
            return false;
        }
        return announce(player, colony, title.strip(), body.strip(), "You announce");
    }

    private boolean announce(ServerPlayer player, IColony colony, String title, String body, String verb) {
        BroadcastPublishResult result = announce(player, colony, NoticeText.announcement(title, body));
        tell(player, switch (result.status()) {
            case PUBLISHED -> verb + " \"" + title + "\": " + result.recipients()
                    + " citizens of " + colony.getName() + " heard it.";
            case RATE_LIMITED -> "The colony has had a lot of news lately; try again in a while.";
            case DISABLED -> "Broadcasts are turned off on this server.";
            default -> "Nobody was there to hear it.";
        });
        return result.isPublished();
    }

    private void watchReach(IColony colony, BroadcastPublishResult result, String title, UUID posterId, @Nullable BlockPos board) {
        if (result.broadcastId() == null || !TalkingColonistsApi.supports(ApiFeature.BROADCAST_REACH)) return;
        reach.removeIf(watch -> watch.colonyKey.equals(key(colony)) && (watch.title.equals(title) || board != null && board.equals(watch.board)));
        ReachWatch watch = new ReachWatch(key(colony), result.broadcastId(), title, posterId,
                now() + NOTICE_LIFETIME.toSeconds() * 20, board);
        // Only news from here on: a notice read by most of a small colony right away starts at half.
        Optional<BroadcastReach> now = currentReach(watch);
        watch.milestone = now.map(r -> NoticeText.reachMilestone(r.heard(), r.citizens())).orElse(0);
        now.ifPresent(r -> showReach(watch, r));
        if (watch.milestone < 2 || board != null) reach.add(watch);
    }

    /** Writes how far the word has spread onto the watch's Notice Board block, if it is loaded. */
    private void showReach(ReachWatch watch, BroadcastReach current) {
        IColony colony = colony(watch.colonyKey);
        if (watch.board == null || colony == null || !colony.getWorld().isLoaded(watch.board)) return;
        if (colony.getWorld().getBlockEntity(watch.board) instanceof NoticeBoardBlockEntity entity && entity.title().equals(watch.title)) {
            entity.setReach(NoticeText.reach(watch.title, current.heard(), current.citizens()));
        }
    }

    private Optional<BroadcastReach> currentReach(ReachWatch watch) {
        IColony colony = colony(watch.colonyKey);
        return colony == null ? Optional.empty() : CitizenMemoryService.broadcastReach(colony, watch.broadcastId);
    }

    private void checkReach() {
        for (ReachWatch watch : List.copyOf(reach)) {
            Optional<BroadcastReach> current = currentReach(watch);
            if (current.isEmpty() || now() > watch.until) {
                reach.remove(watch); // retracted, expired, or the colony is gone
                continue;
            }
            showReach(watch, current.get());
            int milestone = NoticeText.reachMilestone(current.get().heard(), current.get().citizens());
            if (milestone == 2 && watch.board != null) reach.remove(watch);
            if (milestone <= watch.milestone) continue;
            watch.milestone = milestone;
            if (milestone == 2) reach.remove(watch);
            ServerPlayer poster = server.getPlayerList().getPlayer(watch.posterId);
            if (poster != null) tell(poster, NoticeText.reach(watch.title, current.get().heard(), current.get().citizens()));
        }
    }

    /** Operators: all notices collect their replies on the next check. */
    public int rush() {
        notices.forEach(notice -> notice.repliesDue = Math.min(notice.repliesDue, now()));
        return notices.size();
    }

    void tick() {
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return;
        checkReach();
        for (Notice notice : List.copyOf(notices)) {
            if (!notice.writing && notice.repliesDue <= now()) collectReply(notice);
        }
    }

    private void collectReply(Notice notice) {
        IColony colony = colony(notice.colonyKey);
        ServerLevel level = colony == null || !(colony.getWorld() instanceof ServerLevel world) ? null : world;
        if (colony == null || level == null) {
            drop(notice);
            return;
        }
        if (!level.isLoaded(notice.pos)) return; // try again later
        BlockEntity found = level.getBlockEntity(notice.pos);
        LecternBlockEntity lectern = null;
        if (notice.onBoard) {
            if (!(found instanceof NoticeBoardBlockEntity board) || !board.shows(notice.id.toString())) {
                drop(notice); // the notice was taken down, or the board broken
                return;
            }
        } else {
            lectern = found instanceof LecternBlockEntity entity ? entity : null;
            BookText text = lectern != null && lectern.hasBook() ? BookText.read(lectern.getBook()) : null;
            if (text == null || !text.title().equals(notice.title)) {
                drop(notice); // the notice was taken down
                return;
            }
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
                String reply = NoticeText.cleanReply(result.text());
                notice.replies.add(NoticeText.replyPage(data.getName(), role(data), reply));
                if (notice.onBoard && level.isLoaded(notice.pos) && level.getBlockEntity(notice.pos) instanceof NoticeBoardBlockEntity board
                        && board.shows(notice.id.toString())) {
                    board.addReply(new NoticeBoardBlockEntity.Reply(data.getName(), role(data), reply));
                }
            } else {
                notice.failures++;
                NoticeBoard.LOGGER.warn("A reply to notice \"{}\" failed: {}", notice.title,
                        error != null ? error.toString() : result.status() + " " + result.detail());
            }
            save();
        }));
    }

    /** Pins the replies into the lectern's book (a board already shows them) and tells the poster. */
    private void finish(Notice notice, @Nullable LecternBlockEntity lectern) {
        notices.remove(notice);
        save();
        if (notice.replies.isEmpty()) return;
        if (lectern != null) {
            ItemStack book = lectern.getBook();
            List<Component> pages = new ArrayList<>();
            for (String reply : notice.replies) {
                pages.add(Component.literal(reply));
            }
            WrittenBooks.appendPages(book, pages);
            // The lectern caches the page count from when the book was placed; hand the book back so
            // readers can turn to the new pages.
            lectern.setBook(book);
        }
        String summary = notice.replies.size() + (notice.replies.size() == 1 ? " citizen" : " citizens")
                + " pinned a reply to \"" + notice.title + "\". Read them on the " + (lectern != null ? "lectern." : "notice board.");
        ReachWatch watch = reach.stream().filter(w -> w.colonyKey.equals(notice.colonyKey) && w.title.equals(notice.title))
                .findFirst().orElse(null);
        BroadcastReach spread = watch == null ? null : currentReach(watch).orElse(null);
        if (spread != null) summary += " " + NoticeText.reach(notice.title, spread.heard(), spread.citizens());
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
                notice.onBoard = json.has("onBoard") && json.get("onBoard").getAsBoolean();
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
            json.addProperty("onBoard", notice.onBoard);
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
