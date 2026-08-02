package com.bookmarkdrawer.license;

public record LicenseStatus(boolean licensed, String plan, String email) {
    public static LicenseStatus from(LicensePayload payload) {
        if (payload == null) return new LicenseStatus(false, null, null);
        return new LicenseStatus(true, payload.plan(), payload.email());
    }
}
