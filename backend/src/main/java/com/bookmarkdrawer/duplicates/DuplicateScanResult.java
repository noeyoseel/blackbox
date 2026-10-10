package com.bookmarkdrawer.duplicates;

public record DuplicateScanResult(int created, int alreadyKnown, int stalePendingRemoved) {
}
