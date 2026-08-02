package com.bookmarkdrawer.bookmark;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    @PostMapping("/batch")
    public BookmarkIngestResponse ingest(@Valid @RequestBody BookmarkIngestRequest request) {
        return bookmarkService.ingest(request.tweets());
    }

    @GetMapping("/feed")
    public FeedPage feed(@RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size,
                          @RequestParam(defaultValue = "newest") String sort) {
        return bookmarkService.feed(page, size, sort);
    }

    @PatchMapping("/{tweetId}/tags")
    public TagsView updateTags(@PathVariable String tweetId, @RequestBody UpdateTagsRequest request) {
        try {
            return bookmarkService.updateTags(tweetId, request.tags());
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PatchMapping("/{tweetId}/archive")
    public void setArchived(@PathVariable String tweetId, @RequestBody ArchiveRequest request) {
        try {
            bookmarkService.setArchived(tweetId, request.archived());
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PatchMapping("/{tweetId}/folder")
    public void assignFolder(@PathVariable String tweetId, @RequestBody AssignFolderRequest request) {
        try {
            bookmarkService.assignFolder(tweetId, request.folderId());
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/sync-check")
    public SyncCheckResult syncCheck(@RequestBody SyncCheckRequest request) {
        return bookmarkService.syncCheck(request.tweetIds(), request.minOrder(), request.maxOrder());
    }

    @DeleteMapping("/{tweetId}")
    public void deletePermanently(@PathVariable String tweetId) {
        try {
            bookmarkService.deletePermanently(tweetId);
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
