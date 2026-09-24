package me.sshcrack.tc_gazette;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GazetteIssueTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void parsesAnswerAndSkipsBrokenArticles() {
        GazetteIssue issue = GazetteIssue.fromJson(json("""
                {"headline": " Raiders Repelled! ",
                 "articles": [
                   {"title": "The raid", "body": "Guards held the wall."},
                   {"title": "", "body": "no title"},
                   "not an object",
                   {"title": "New baker", "body": "Anna starts at the bakery."}
                 ]}"""), 3, 12, "Oakvale", "Maria", "teacher");
        assertNotNull(issue);
        assertEquals("Raiders Repelled!", issue.headline());
        assertEquals(List.of(new GazetteIssue.Article("The raid", "Guards held the wall."),
                new GazetteIssue.Article("New baker", "Anna starts at the bakery.")), issue.articles());
    }

    @Test
    void rejectsAnswersWithoutHeadlineOrArticles() {
        assertNull(GazetteIssue.fromJson(json("{\"articles\":[{\"title\":\"a\",\"body\":\"b\"}]}"), 1, 1, "C", "A", ""));
        assertNull(GazetteIssue.fromJson(json("{\"headline\":\"h\",\"articles\":[]}"), 1, 1, "C", "A", ""));
        assertNull(GazetteIssue.fromJson(json("{\"headline\":\"h\",\"articles\":\"nope\"}"), 1, 1, "C", "A", ""));
    }

    @Test
    void capsArticlesAndLengths() {
        StringBuilder articles = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) articles.append(',');
            articles.append("{\"title\":\"").append("t".repeat(100)).append("\",\"body\":\"")
                    .append("word ".repeat(300)).append("\"}");
        }
        GazetteIssue issue = GazetteIssue.fromJson(json("{\"headline\":\"" + "h ".repeat(100) + "\",\"articles\":["
                + articles + "]}"), 1, 1, "C", "A", "");
        assertNotNull(issue);
        assertEquals(GazetteIssue.MAX_ARTICLES, issue.articles().size());
        assertTrue(issue.headline().length() <= GazetteIssue.MAX_HEADLINE_CHARS);
        assertTrue(issue.articles().get(0).title().length() <= GazetteIssue.MAX_TITLE_CHARS);
        assertTrue(issue.articles().get(0).body().length() <= GazetteIssue.MAX_BODY_CHARS);
        assertTrue(issue.articles().get(0).body().endsWith("…"));
    }

    @Test
    void savedIssueLoadsBack() {
        GazetteIssue issue = new GazetteIssue(7, 40, "Oakvale", "Maria", "teacher", "Big news",
                List.of(new GazetteIssue.Article("One", "Body one."), new GazetteIssue.Article("Two", "Body two.")));
        assertEquals(issue, GazetteIssue.load(issue.toJson()));
        assertNull(GazetteIssue.load(json("{\"number\":\"x\"}")));
    }

    @Test
    void bookTitleFitsMinecraftLimit() {
        GazetteIssue shortName = new GazetteIssue(12, 1, "Oakvale", "A", "", "h", List.of(new GazetteIssue.Article("t", "b")));
        assertEquals("Oakvale Gazette #12", shortName.bookTitle());
        GazetteIssue longName = new GazetteIssue(12, 1, "The Very Long Colony Name Of Doom", "A", "", "h",
                List.of(new GazetteIssue.Article("t", "b")));
        assertEquals("Gazette #12", longName.bookTitle());
    }

    @Test
    void directiveListsEventsAndRole() {
        String directive = GazetteIssue.directive("Oakvale", 4, "teacher", List.of("A raid hit the east wall", "Anna was born"));
        assertTrue(directive.contains("issue No. 4 of \"The Oakvale Gazette\""));
        assertTrue(directive.contains("as the colony's teacher"));
        assertTrue(directive.contains("- A raid hit the east wall\n- Anna was born\n"));
        assertTrue(directive.contains("Never invent deaths"));
        assertTrue(directive.length() < 4_000);
    }

    @Test
    void schemaRequiresHeadlineAndArticles() {
        JsonObject schema = GazetteIssue.responseSchema();
        assertEquals("[\"headline\",\"articles\"]", schema.get("required").toString());
        assertEquals("array", schema.getAsJsonObject("properties").getAsJsonObject("articles").get("type").getAsString());
    }
}
