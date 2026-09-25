// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_noticeboard.shared.guide;

import com.minecolonies.api.colony.IColony;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.guide.AddonGuide;
import me.sshcrack.mc_talking.api.guide.AddonGuideService;
import me.sshcrack.mc_talking.api.intro.CitizenIntroductionService;
import me.sshcrack.mc_talking.api.intro.Introduction;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * The addon's chapter in the Colony Handbook, which citizens also know, and the introduction a citizen
 * gives each player once. Talking Colonists versions without these features are skipped quietly, so the
 * addon still runs on them.
 */
public final class Guides {
    private Guides() {
    }

    /** Call once from the mod constructor, on both sides. */
    public static void register(String id, String title, String summary, List<String> steps, List<String> notes) {
        try {
            if (!TalkingColonistsApi.supports(ApiFeature.ADDON_GUIDES)) return;
            AddonGuideService.register(new AddonGuide(id, title, summary, steps, notes));
        } catch (LinkageError olderTalkingColonists) {
            // ApiFeature.ADDON_GUIDES or the guide classes are missing: a Talking Colonists without the handbook.
        }
    }

    /**
     * A citizen walks up to each player once, when {@code due} says it is relevant, and tells them about
     * {@code topic} following {@code lineHint}. Call once from the mod constructor.
     */
    public static void introduce(String id, String topic, String lineHint, String guideId, BiPredicate<ServerPlayer, IColony> due) {
        try {
            if (!TalkingColonistsApi.supports(ApiFeature.INTRODUCTIONS)) return;
            CitizenIntroductionService.register(new Introduction(id, topic, lineHint, guideId, due::test));
        } catch (LinkageError olderTalkingColonists) {
            // ApiFeature.INTRODUCTIONS or the introduction classes are missing: an older Talking Colonists.
        }
    }
}
