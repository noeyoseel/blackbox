package com.bookmarkdrawer.license;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/license")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;

    @GetMapping("/status")
    public LicenseStatus status() {
        return LicenseStatus.from(licenseService.currentLicense());
    }

    @PostMapping
    public LicenseStatus activate(@RequestBody ActivateLicenseRequest request) {
        try {
            return LicenseStatus.from(licenseService.activate(request.key()));
        } catch (InvalidLicenseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
