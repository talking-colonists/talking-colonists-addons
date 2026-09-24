// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_noticeboard.shared.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A JSON object file in the world folder. A plain file keeps the format the same on both loaders;
 * writes go through a temporary file so a crash never leaves half a file behind.
 */
public final class JsonFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFile() {
    }

    /** The file's object, null when it does not exist. Throws when it cannot be read or parsed. */
    public static @Nullable JsonObject read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Not a JSON object: " + file, e);
        }
    }

    public static void write(Path file, JsonObject root) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
