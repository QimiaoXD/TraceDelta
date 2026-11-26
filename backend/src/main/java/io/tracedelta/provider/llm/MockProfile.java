package io.tracedelta.provider.llm;

import java.util.Locale;

public enum MockProfile {
    MOCK_GOOD("mock-good"),
    MOCK_SLOPPY("mock-sloppy"),
    MOCK_SLOW("mock-slow"),
    MOCK_FLAKY("mock-flaky");

    private final String profileName;

    MockProfile(String profileName) {
        this.profileName = profileName;
    }

    public String getProfileName() {
        return profileName;
    }

    public static MockProfile fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return MOCK_GOOD;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (MockProfile profile : values()) {
            if (profile.profileName.equals(normalized)
                    || profile.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown mock profile: " + raw);
    }
}
