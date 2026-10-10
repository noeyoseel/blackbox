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
                          @RequestParam(defaultValue = "newest") String sort,
                          @RequestParam(required = false) ContentType contentType,
                          @RequestParam(required = false) String tag) {
        return bookmarkService.feed(page, size, sort, contentType, tag);
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

    @PatchMapping("/{tweetId}/metadata")
    public BookmarkMetadataView updateMetadata(@PathVariable String tweetId, @RequestBody UpdateMetadataRequest request) {
        try {
            return bookmarkService.updateMetadata(tweetId, request.contentType(), request.place());
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

    @GetMapping("/organize")
    public OrganizePage organize(@RequestParam(defaultValue = "candidates") String filter,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(defaultValue = "oldest") String sort) {
        return bookmarkService.organize(OrganizeFilter.from(filter), page, size, sort);
    }

    @PatchMapping("/batch/tags")
    public BatchActionResult batchAddTags(@Valid @RequestBody BatchTagRequest request) {
        return bookmarkService.batchAddTags(request.tweetIds(), request.tagsToAdd());
    }

    @PatchMapping("/batch/archive")
    public BatchActionResult batchSetArchived(@Valid @RequestBody BatchArchiveRequest request) {
        return bookmarkService.batchSetArchived(request.tweetIds(), request.archived());
    }

    @PatchMapping("/batch/metadata")
    public BatchActionResult batchUpdateMetadata(@Valid @RequestBody BatchMetadataRequest request) {
        return bookmarkService.batchUpdateMetadata(request.tweetIds(), request.contentType(), request.place());
    }
}
