package me.sshcrack.tc_postal;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.jobs.ModJobs;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.tc_postal.shared.book.BookPages;
import me.sshcrack.tc_postal.shared.book.BookText;
import me.sshcrack.tc_postal.shared.book.WrittenBooks;
import me.sshcrack.tc_postal.shared.delivery.Couriers;
import me.sshcrack.tc_postal.shared.provider.TextCapacity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Takes letters from players, has the recipient write back through Talking Colonists once the letter
 * arrives, and has the courier (or the writer) carry the reply to the player when they are in the
 * colony. Replies wait in the player's mailbox until then. Server thread only.
 */
public final class PostOffice {
    static final String SOURCE = PostalService.MOD_ID;
    /** A letter handed to its recipient is read about a minute later. */
    static final long HANDED_OVER_TICKS = 1_200;
    /** A courier needs two to four minutes. */
    static final long COURIER_MIN_TICKS = 2_400;
    static final long COURIER_SPREAD_TICKS = 2_400;
    static final long RETRY_TICKS = 1_200;
    static final int MAX_ATTEMPTS = 6;
    /** A recipient who stays unloaded this long after the letter arrived gets it returned. */
    static final long GIVE_UP_TICKS = 3 * 24_000;
    static final int MAX_IN_TRANSIT = 5;
    private static final int CHECK_INTERVAL_TICKS = 100;

    private final MinecraftServer server;
    private final PostStore store;
    private final Couriers couriers;
    private int ticks;

    PostOffice(MinecraftServer server, PostStore store) {
        this.server = server;
        this.store = store;
        this.couriers = new Couriers(server, SOURCE + ":delivery");
    }

    Couriers couriers() {
        return couriers;
    }

    public PostStore store() {
        return store;
    }

    private long now() {
        return server.overworld().getGameTime();
    }

    /**
     * The player hands {@code letter} (a signed book titled with the recipient's name) to {@code citizen}.
     * Takes the book when the letter is accepted. Always tells the player what happened.
     */
    public boolean accept(ServerPlayer player, AbstractEntityCitizen citizen, ItemStack letter) {
        BookText text = BookText.read(letter);
        ICitizenData handler = citizen.getCitizenData();
        if (text == null || handler == null) return false;
        IColony colony = handler.getColony();
        List<ICitizenData> citizens = new ArrayList<>(colony.getCitizenManager().getCitizens());
        if (!citizens.contains(handler)) {
            tell(player, handler.getName() + " is only visiting and cannot take letters.");
            return false;
        }
        int index = LetterText.matchRecipient(text.title(), citizens.stream().map(ICitizenData::getName).toList());
        if (index == LetterText.AMBIGUOUS) {
            tell(player, "Several citizens of " + colony.getName() + " are called \"" + text.title()
                    + "\". Sign the letter with their full name.");
            return false;
        }
        if (index == LetterText.NOT_FOUND) {
            tell(player, "Nobody in " + colony.getName() + " is called \"" + text.title()
                    + "\". Sign the letter with the recipient's name as its title.");
            return false;
        }
        ICitizenData recipient = citizens.get(index);
        boolean handedOver = recipient == handler;
        ICitizenData courier = citizens.stream().filter(PostOffice::isCourier).findFirst().orElse(null);
        if (!handedOver && courier != null && !isCourier(handler)) {
            tell(player, handler.getName() + " says: \"Letters go to our courier, " + courier.getName() + ".\"");
            return false;
        }
        if (store.inTransit(player.getUUID()) >= MAX_IN_TRANSIT) {
            tell(player, "You already have " + MAX_IN_TRANSIT + " letters on their way. Wait for some answers first.");
            return false;
        }

        PostStore.Letter sent = new PostStore.Letter();
        sent.colonyKey = key(colony);
        sent.recipientId = recipient.getId();
        sent.recipientName = recipient.getName();
        sent.playerId = player.getUUID();
        sent.playerName = player.getGameProfile().getName();
        sent.title = text.title();
        sent.body = text.body();
        sent.handedOver = handedOver;
        sent.sentGameTime = now();
        sent.dueGameTime = now() + (handedOver ? HANDED_OVER_TICKS
                : COURIER_MIN_TICKS + server.overworld().getRandom().nextInt((int) COURIER_SPREAD_TICKS));
        store.letters().add(sent);
        store.save();
        letter.shrink(1);
        tell(player, handedOver
                ? recipient.getName() + " takes your letter and will write back soon."
                : handler.getName() + " will bring your letter to " + recipient.getName() + ".");
        return true;
    }

    private static boolean isCourier(ICitizenData citizen) {
        return citizen.getJob() != null && citizen.getJob().getJobRegistryEntry() == ModJobs.delivery.get();
    }

    void tick() {
        couriers.tick();
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return;
        long now = now();
        for (PostStore.Letter letter : List.copyOf(store.letters())) {
            if (letter.writing || letter.dueGameTime > now) continue;
            deliver(letter, now);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bringMail(player);
        }
    }

    /**
     * Sends a citizen with the player's oldest waiting letter, if the player is inside the colony it
     * comes from. One letter at a time. Returns whether someone is on the way.
     */
    public boolean bringMail(ServerPlayer player) {
        List<PostStore.Mail> box = store.mailbox(player.getUUID());
        for (PostStore.Mail mail : List.copyOf(box)) {
            IColony colony = colony(mail.colonyKey());
            if (colony == null) {
                // Its colony is gone: nobody can carry it any more.
                box.remove(mail);
                store.save();
                continue;
            }
            if (player.level() != colony.getWorld() || !colony.isCoordInColony(player.level(), player.blockPosition())) continue;
            String key = "mail|" + mail.id();
            if (couriers.isRunning(key)) return true;
            ICitizenData writer = mail.fromCitizenId() < 0 ? null : colony.getCitizenManager().getCivilian(mail.fromCitizenId());
            AbstractEntityCitizen preferred = writer == null ? null : writer.getEntity().orElse(null);
            boolean ownReply = preferred != null && writer.getName().equals(mail.from());
            String hint = mail.fromCitizenId() < 0 ? "It is their own letter, which could not be delivered."
                    : "It is " + mail.from() + "'s reply to the letter they sent" + (ownReply ? "; you wrote it yourself." : ".");
            return couriers.dispatch(key, colony, player, preferred, "a letter from " + mail.from(), hint, () -> book(mail),
                    delivered -> {
                        if (delivered && box.remove(mail)) store.save();
                    });
        }
        return false;
    }

    private static ItemStack book(PostStore.Mail mail) {
        return WrittenBooks.create(mail.title(), mail.from(), WrittenBooks.ORIGINAL,
                mail.pages().stream().map(page -> (Component) Component.literal(page)).toList());
    }

    private void deliver(PostStore.Letter letter, long now) {
        IColony colony = colony(letter.colonyKey);
        ICitizenData recipient = colony == null ? null : colony.getCitizenManager().getCivilian(letter.recipientId);
        if (recipient == null) {
            returnLetter(letter, letter.recipientName + " no longer lives in the colony");
            return;
        }
        AbstractEntityCitizen entity = recipient.getEntity().orElse(null);
        if (entity == null || !TextCapacity.hasSpare()) {
            // The recipient must be loaded to answer in character; wait for them (or for quota).
            if (now - letter.dueGameTime > GIVE_UP_TICKS) returnLetter(letter, letter.recipientName + " never came to read it");
            return;
        }
        letter.writing = true;
        TextRequest request = TextRequest.of(SOURCE + ":reply",
                        LetterText.directive(letter.playerName, letter.title, letter.body, letter.handedOver))
                .withMaxChars(LetterText.MAX_REPLY_CHARS)
                .withResponseSchema(LetterText.responseSchema());
        CitizenTextService.generate(entity, request).whenComplete((result, error) -> server.execute(() -> {
            letter.writing = false;
            if (!store.letters().contains(letter)) return;
            LetterText.Answer answer = error == null && result.isSuccess() ? LetterText.parse(result.json()) : null;
            if (answer == null) {
                letter.attempts++;
                PostalService.LOGGER.warn("Reply to letter {} failed ({}), attempt {}", letter.id,
                        error != null ? error.toString() : result.status() + " " + result.detail(), letter.attempts);
                if (letter.attempts >= MAX_ATTEMPTS) {
                    returnLetter(letter, letter.recipientName + " could not find the words");
                } else {
                    letter.dueGameTime = now() + RETRY_TICKS * letter.attempts;
                    store.save();
                }
                return;
            }
            remember(recipient, letter, answer);
            List<String> pages = BookPages.paginate(answer.reply(), BookPages.PAGE_CHARS);
            store.letters().remove(letter);
            store.mailbox(letter.playerId).add(new PostStore.Mail(UUID.randomUUID(), letter.colonyKey, recipient.getId(),
                    recipient.getName(), LetterText.replyTitle(letter.title, recipient.getName()), pages));
            store.save();
            notifyMail(letter.playerId, recipient.getName() + " wrote back. The letter will be brought to you in the colony.");
        }));
    }

    /** Talking Colonists remembers the exchange as a confirmed outcome (once per letter). */
    private static void remember(ICitizenData recipient, PostStore.Letter letter, LetterText.Answer answer) {
        String event = "Received a letter from " + letter.playerName + " (\"" + LetterText.cut(letter.title, 60)
                + "\") and wrote back" + (answer.memory().isEmpty() ? "." : ": " + answer.memory());
        try {
            CitizenMemoryService.confirmOutcome(recipient, new AddonConfirmedOutcome(SOURCE, letter.id.toString(),
                    LetterText.cut(event, 300), letter.playerId, List.of(), List.of()));
        } catch (RuntimeException e) {
            PostalService.LOGGER.warn("Could not store the memory of letter {}", letter.id, e);
        }
    }

    private void returnLetter(PostStore.Letter letter, String why) {
        store.letters().remove(letter);
        List<String> pages = new ArrayList<>();
        for (String part : LetterText.returnedLetter(letter.recipientName, why, letter.body)) {
            pages.addAll(BookPages.paginate(part, BookPages.PAGE_CHARS));
        }
        store.mailbox(letter.playerId).add(new PostStore.Mail(UUID.randomUUID(), letter.colonyKey, -1, "Post office",
                LetterText.cut("Returned: " + letter.title, 32), pages));
        store.save();
        notifyMail(letter.playerId, "Your letter to " + letter.recipientName + " could not be delivered; it will be brought back to you.");
    }

    /** Operators: hands the player all their waiting letters at once. Returns how many. */
    public int collect(ServerPlayer player) {
        List<PostStore.Mail> box = store.mailbox(player.getUUID());
        int count = box.size();
        for (PostStore.Mail mail : box) {
            ItemStack book = book(mail);
            if (!player.getInventory().add(book)) player.drop(book, false);
        }
        box.clear();
        if (count > 0) store.save();
        return count;
    }

    /** Operators: every letter of this player arrives on the next check. */
    public int rush(UUID player) {
        int count = 0;
        for (PostStore.Letter letter : store.letters()) {
            if (letter.playerId.equals(player)) {
                letter.dueGameTime = Math.min(letter.dueGameTime, now());
                count++;
            }
        }
        store.save();
        return count;
    }

    void onLogin(ServerPlayer player) {
        int waiting = store.mailbox(player.getUUID()).size();
        if (waiting > 0) {
            notifyMail(player.getUUID(), waiting + (waiting == 1 ? " letter waits" : " letters wait")
                    + " for you; it will be brought to you in the colony.");
        }
    }

    private void notifyMail(UUID playerId, String text) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) tell(player, text);
    }

    static void tell(ServerPlayer player, String text) {
        player.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    private static net.minecraft.network.chat.MutableComponent prefix() {
        return Component.literal("[Post] ").withStyle(ChatFormatting.GOLD);
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
}
