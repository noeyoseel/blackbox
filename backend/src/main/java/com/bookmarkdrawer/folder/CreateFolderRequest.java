package com.bookmarkdrawer.folder;

import jakarta.validation.constraints.NotBlank;

public record CreateFolderRequest(@NotBlank String name) {
}
