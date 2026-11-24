package com.bookmarkdrawer.interestmap;

import jakarta.validation.constraints.NotBlank;

public record UpdateClusterNameRequest(@NotBlank String name) {
}
