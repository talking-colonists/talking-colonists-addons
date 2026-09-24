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
import me.sshcrack.tc_postal.shared.provider.TextCapacity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Takes letters from players, has the recipient write back through Talking Colonists once the letter
 * arrives, and keeps the replies in the player's mailbox ({@code /mail}). Server thread only.
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
    private int ticks;

    PostOffice(MinecraftServer server, PostStore store) {
        this.server = server;
        this.store = store;
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
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return;
        long now = now();
        for (PostStore.Letter letter : List.copyOf(store.letters())) {
            if (letter.writing || letter.dueGameTime > now) continue;
            deliver(letter, now);
        }
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
            store.mailbox(letter.playerId).add(new PostStore.Mail(recipient.getName(),
                    LetterText.replyTitle(letter.title, recipient.getName()), pages));
            store.save();
            notifyMail(letter.playerId, "A letter from " + recipient.getName() + " has arrived.");
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
        store.mailbox(letter.playerId).add(new PostStore.Mail("Post office", LetterText.cut("Returned: " + letter.title, 32), pages));
        store.save();
        notifyMail(letter.playerId, "Your letter to " + letter.recipientName + " came back undelivered.");
    }

    /** Hands the player all their mail as books. Returns how many letters they got. */
    public int collect(ServerPlayer player) {
        List<PostStore.Mail> box = store.mailbox(player.getUUID());
        int count = box.size();
        for (PostStore.Mail mail : box) {
            ItemStack book = WrittenBooks.create(mail.title(), mail.from(), WrittenBooks.ORIGINAL,
                    mail.pages().stream().map(page -> (Component) Component.literal(page)).toList());
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
        if (waiting > 0) notifyMail(player.getUUID(), "You have " + waiting + (waiting == 1 ? " letter" : " letters") + " in your mailbox.");
    }

    private void notifyMail(UUID playerId, String text) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        player.sendSystemMessage(prefix().append(Component.literal(text + " ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[Collect]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mail"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/mail"))))));
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
