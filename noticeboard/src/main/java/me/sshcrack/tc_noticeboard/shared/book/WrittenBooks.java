// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_noticeboard.shared.book;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
/*? if neoforge {*/
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.WrittenBookContent;
/*?}*/
/*? if forge {*/
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
*//*?}*/

import java.util.List;
import java.util.ArrayList;

/** Builds signed written books on both loaders (data components on 1.21.1, NBT on 1.20.1). */
public final class WrittenBooks {
    /** Generation shown as "Original". */
    public static final int ORIGINAL = 0;
    /** Generation shown as "Copy of original"; such a copy can still be copied once more. */
    public static final int COPY = 1;

    private WrittenBooks() {
    }

    /**
     * A signed book. {@code title} must be at most 32 characters, and at most
     * {@link BookPages#MAX_PAGES} pages are kept.
     */
    public static ItemStack create(String title, String author, int generation, List<Component> pages) {
        List<Component> kept = pages.size() > BookPages.MAX_PAGES ? pages.subList(0, BookPages.MAX_PAGES) : pages;
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        /*? if neoforge {*/
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), author,
                generation, kept.stream().map(Filterable::passThrough).toList(), true));
        /*?}*/
        /*? if forge {*/
        /*CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", title);
        tag.putString("author", author);
        tag.putInt("generation", generation);
        ListTag pageTags = new ListTag();
        for (Component page : kept) {
            pageTags.add(StringTag.valueOf(Component.Serializer.toJson(page)));
        }
        tag.put("pages", pageTags);
        tag.putBoolean("resolved", true);
        *//*?}*/
        return book;
    }

    /** Adds pages to the end of a signed book in place, keeping at most {@link BookPages#MAX_PAGES}. */
    public static void appendPages(ItemStack book, List<Component> pages) {
        /*? if neoforge {*/
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return;
        List<Filterable<Component>> all = new ArrayList<>(content.pages());
        for (Component page : pages) {
            if (all.size() >= BookPages.MAX_PAGES) break;
            all.add(Filterable.passThrough(page));
        }
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(content.title(), content.author(),
                content.generation(), all, true));
        /*?}*/
        /*? if forge {*/
        /*CompoundTag tag = book.getTag();
        if (tag == null) return;
        ListTag all = tag.getList("pages", net.minecraft.nbt.Tag.TAG_STRING);
        for (Component page : pages) {
            if (all.size() >= BookPages.MAX_PAGES) break;
            all.add(StringTag.valueOf(Component.Serializer.toJson(page)));
        }
        tag.put("pages", all);
        *//*?}*/
    }
}
