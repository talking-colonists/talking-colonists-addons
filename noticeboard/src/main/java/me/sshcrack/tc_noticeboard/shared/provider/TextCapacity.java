// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_noticeboard.shared.provider;

import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.provider.ProviderBudgetService;
import me.sshcrack.mc_talking.api.provider.ProviderBudgetView;
import me.sshcrack.mc_talking.api.provider.ProviderConfigView;
import me.sshcrack.mc_talking.api.provider.ProviderQuotaState;

/** Whether a background text request would likely be served right now. */
public final class TextCapacity {
    private TextCapacity() {
    }

    /**
     * True when a Gemini key is set, a background slot is free and the text model (or the Live model
     * it falls back to) has quota left. Always true on runtimes without the provider budget API.
     */
    public static boolean hasSpare() {
        if (!TalkingColonistsApi.supports(ApiFeature.PROVIDER_BUDGET)) return true;
        ProviderConfigView config = ProviderBudgetService.config();
        if (!config.apiKeySet()) return false;
        ProviderBudgetView budget = ProviderBudgetService.snapshot();
        if (budget.background().available() <= 0) return false;
        // Text requests fall back to the Live model when the text model is out of quota.
        return usable(budget, config.textModel()) || usable(budget, config.liveModel());
    }

    private static boolean usable(ProviderBudgetView budget, String model) {
        return budget.model(model).map(view -> view.state() == ProviderQuotaState.OK).orElse(true);
    }
}
