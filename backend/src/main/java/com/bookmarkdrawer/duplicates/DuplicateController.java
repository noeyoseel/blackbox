package com.bookmarkdrawer.duplicates;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/duplicates")
@RequiredArgsConstructor
public class DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;

    @PostMapping("/scan")
    public DuplicateScanResult scan() {
        return duplicateDetectionService.scan();
    }

    @GetMapping("/groups")
    public List<DuplicateGroupView> groups(@RequestParam(defaultValue = "exact") String type) {
        DuplicateType resolved = "near".equalsIgnoreCase(type) ? DuplicateType.NEAR : DuplicateType.EXACT;
        return duplicateDetectionService.listGroups(resolved);
    }

    @PostMapping("/groups/{id}/resolve")
    public DuplicateResolveResult resolve(@PathVariable Long id, @RequestBody DuplicateResolveRequest request) {
        try {
            return duplicateDetectionService.resolve(id, request.action(), request.tweetIds());
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
