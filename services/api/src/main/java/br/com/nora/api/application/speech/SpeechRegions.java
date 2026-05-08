package br.com.nora.api.application.speech;

import java.util.Set;

public final class SpeechRegions {

    private static final Set<String> ALLOWED =
            Set.of(
                    "brazilsouth",
                    "eastus",
                    "westeurope",
                    "southeastasia",
                    "westus2",
                    "northeurope",
                    "australiaeast",
                    "japaneast");

    private SpeechRegions() {}

    public static void requireAllowed(String region) {
        if (region == null || region.isBlank()) {
            throw new SpeechException.InvalidRegion("(empty)");
        }
        String normalized = region.trim().toLowerCase();
        if (!ALLOWED.contains(normalized)) {
            throw new SpeechException.InvalidRegion(region);
        }
    }

    public static Set<String> allowed() {
        return ALLOWED;
    }
}
