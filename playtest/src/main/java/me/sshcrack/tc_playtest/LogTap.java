package me.sshcrack.tc_playtest;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Every log message of this game (client and integrated server share the JVM), so a scenario can wait
 * for something to happen, e.g. a citizen's line, instead of guessing how long it takes.
 */
final class LogTap {
    private static final String NAME = "TcPlaytestTap";
    private static final List<String> LINES = new ArrayList<>();

    private LogTap() {
    }

    /** Adds the tap to the root logger; again after a reconfiguration dropped it. */
    static void install() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        if (config.getRootLogger().getAppenders().containsKey(NAME)) return;
        // The 4-argument constructor: the only one on both loaders' log4j versions.
        @SuppressWarnings("deprecation")
        AbstractAppender appender = new AbstractAppender(NAME, null, null, true) {
            @Override
            public void append(LogEvent event) {
                String line = event.getLoggerName() + " " + event.getMessage().getFormattedMessage();
                synchronized (LINES) {
                    LINES.add(line);
                }
            }
        };
        appender.start();
        config.getRootLogger().addAppender(appender, Level.INFO, null);
        context.updateLoggers();
    }

    /** How many lines were logged so far; a position to search from. */
    static int size() {
        synchronized (LINES) {
            return LINES.size();
        }
    }

    /** The first line from {@code from} on that matches, or null. */
    static @Nullable String find(Pattern pattern, int from) {
        synchronized (LINES) {
            for (int i = Math.max(0, from); i < LINES.size(); i++) {
                if (pattern.matcher(LINES.get(i)).find()) return LINES.get(i);
            }
        }
        return null;
    }
}
