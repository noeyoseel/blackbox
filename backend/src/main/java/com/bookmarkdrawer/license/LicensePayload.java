package com.bookmarkdrawer.license;

public record LicensePayload(String licenseId, String email, String plan, String issuedAt) {
}
