package com.bookmarkdrawer.folder;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
public class FolderApiController {

    private final FolderService folderService;

    @GetMapping
    public List<FolderCount> list() {
        return folderService.listFolders();
    }

    @PostMapping
    public Folder create(@Valid @RequestBody CreateFolderRequest request) {
        return folderService.createFolder(request.name());
    }

    @PatchMapping("/{id}")
    public Folder rename(@PathVariable Long id, @Valid @RequestBody CreateFolderRequest request) {
        try {
            return folderService.renameFolder(id, request.name());
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        try {
            folderService.deleteFolder(id);
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
