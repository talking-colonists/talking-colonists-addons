package me.sshcrack.tc_gazette;

import me.sshcrack.tc_gazette.shared.book.BookPages;
import me.sshcrack.tc_gazette.shared.book.WrittenBooks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Lays out a {@link GazetteIssue} as a written book. */
public final class GazetteBook {
    private GazetteBook() {
    }

    public static ItemStack create(GazetteIssue issue, int generation) {
        return WrittenBooks.create(issue.bookTitle(), issue.author(), generation, pages(issue));
    }

    static List<Component> pages(GazetteIssue issue) {
        List<Component> pages = new ArrayList<>();
        pages.add(frontPage(issue));
        for (GazetteIssue.Article article : issue.articles()) {
            List<String> parts = BookPages.paginate(article.body(),
                    BookPages.PAGE_CHARS - BookPages.headingCost(article.title()));
            for (int i = 0; i < parts.size(); i++) {
                MutableComponent page = Component.empty();
                if (i == 0) {
                    page.append(Component.literal(article.title()).withStyle(ChatFormatting.BOLD)).append("\n\n");
                }
                page.append(Component.literal(parts.get(i)));
                pages.add(page);
            }
        }
        return pages;
    }

    private static Component frontPage(GazetteIssue issue) {
        MutableComponent page = Component.empty();
        page.append(Component.literal("The " + issue.colonyName() + " Gazette")
                .withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_BLUE));
        page.append(Component.literal("\nNo. " + issue.number() + " · Day " + issue.day())
                .withStyle(ChatFormatting.GRAY));
        page.append("\n\n");
        page.append(Component.literal(issue.headline()).withStyle(ChatFormatting.BOLD));
        page.append("\n\n");
        String byline = issue.authorRole().isBlank()
                ? "From the colony's notes"
                : "Written by " + issue.author() + ", " + issue.authorRole();
        page.append(Component.literal(byline).withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return page;
    }
}
