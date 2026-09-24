// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_postal.shared.book;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
/*? if neoforge {*/
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.WrittenBookContent;
/*?}*/
/*? if forge {*/
/*import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
*//*?}*/

import java.util.ArrayList;
import java.util.List;

/** Reads a signed written book as plain text on both loaders. */
public record BookText(String title, String author, List<String> pages) {
    /** The book's text, or null when the stack is not a signed written book. */
    public static @Nullable BookText read(ItemStack stack) {
        if (!stack.is(Items.WRITTEN_BOOK)) return null;
        List<String> pages = new ArrayList<>();
        /*? if neoforge {*/
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return null;
        for (var page : content.pages()) {
            pages.add(page.raw().getString());
        }
        return new BookText(content.title().raw(), content.author(), List.copyOf(pages));
        /*?}*/
        /*? if forge {*/
        /*CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("title", Tag.TAG_STRING)) return null;
        var list = tag.getList("pages", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String raw = list.getString(i);
            Component page = null;
            try {
                page = Component.Serializer.fromJson(raw);
            } catch (RuntimeException ignored) {
                // Not JSON: an old or hand-made book stores plain text.
            }
            pages.add(page == null ? raw : page.getString());
        }
        return new BookText(tag.getString("title"), tag.getString("author"), List.copyOf(pages));
        *//*?}*/
    }

    /** All pages joined with blank lines, stripped. */
    public String body() {
        return String.join("\n\n", pages).strip();
    }
}
