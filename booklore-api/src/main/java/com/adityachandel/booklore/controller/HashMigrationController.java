package com.adityachandel.booklore.controller;

import com.adityachandel.booklore.service.hash.HashMigrationService;
import com.adityachandel.booklore.service.hash.HashMigrationService.HashMigrationResult;
import com.adityachandel.booklore.service.hash.HashMigrationService.HashMigrationStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/hash")
@Tag(name = "Hash Management", description = "Administrative endpoints for book hash management and migration")
@PreAuthorize("@securityUtil.isAdmin()")
public class HashMigrationController {

    private final HashMigrationService hashMigrationService;

    @Operation(
            summary = "Get hash migration statistics",
            description = "Returns statistics about books with/without hashes and soft-deleted books pending cleanup"
    )
    @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
    @GetMapping("/stats")
    public ResponseEntity<HashMigrationStats> getStats() {
        return ResponseEntity.ok(hashMigrationService.getMigrationStats());
    }

    @Operation(
            summary = "Regenerate all hashes",
            description = "Regenerates hashes for ALL books in the system. This may take a long time for large libraries."
    )
    @ApiResponse(responseCode = "200", description = "Hash regeneration completed")
    @PostMapping("/regenerate-all")
    public ResponseEntity<HashMigrationResult> regenerateAllHashes() {
        HashMigrationResult result = hashMigrationService.regenerateAllHashes();
        return ResponseEntity.ok(result);
    }

    @Operation(
            summary = "Regenerate missing hashes",
            description = "Regenerates hashes only for books that are missing them. Much faster than regenerate-all."
    )
    @ApiResponse(responseCode = "200", description = "Hash regeneration completed")
    @PostMapping("/regenerate-missing")
    public ResponseEntity<HashMigrationResult> regenerateMissingHashes() {
        HashMigrationResult result = hashMigrationService.regenerateMissingHashes(null);
        return ResponseEntity.ok(result);
    }

    @Operation(
            summary = "Trigger startup migration",
            description = "Manually trigger the startup migration process. Only needed if automatic startup migration was disabled or failed."
    )
    @ApiResponse(responseCode = "200", description = "Startup migration triggered")
    @PostMapping("/startup-migration")
    public ResponseEntity<Void> triggerStartupMigration() {
        new Thread(() -> hashMigrationService.performStartupMigration(), "manual-startup-migration").start();
        return ResponseEntity.accepted().build();
    }
}
