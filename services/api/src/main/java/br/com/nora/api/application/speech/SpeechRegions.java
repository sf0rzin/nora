package br.com.nora.api.application.speech;

import java.util.Set;

public final class SpeechRegions {

    private static final Set<String> ALLOWED =
            Set.of(
                    "australiaeast",
                    "brazilsouth",
                    "canadacentral",
                    "centralus",
                    "eastasia",
                    "eastus",
                    "eastus2",
                    "francecentral",
                    "germanywestcentral",
                    "japaneast",
                    "koreacentral",
                    "northcentralus",
                    "northeurope",
                    "southafricanorth",
                    "southcentralus",
                    "southeastasia",
                    "swedencentral",
                    "switzerlandnorth",
                    "uaenorth",
                    "uksouth",
                    "westcentralus",
                    "westeurope",
                    "westus",
                    "westus2",
                    "westus3");

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
