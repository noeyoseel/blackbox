package com.bookmarkdrawer.folder;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequiredArgsConstructor
public class FolderPageController {

    private final FolderService folderService;

    @GetMapping("/folders")
    public String list(Model model) {
        model.addAttribute("folders", folderService.listFolders());
        return "folders";
    }

    @GetMapping("/folders/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("folder", folderService.getFolder(id));
        model.addAttribute("bookmarks", folderService.bookmarksInFolder(id));
        return "folder-detail";
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public void handleNotFound(EntityNotFoundException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
