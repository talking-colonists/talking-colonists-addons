// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_postal.shared.guide;

import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.guide.AddonGuide;
import me.sshcrack.mc_talking.api.guide.AddonGuideService;

import java.util.List;

/**
 * The addon's chapter in the Colony Handbook, which citizens also know. Talking Colonists versions without
 * addon guides (before the handbook) are skipped quietly, so the addon still runs on them.
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
}
