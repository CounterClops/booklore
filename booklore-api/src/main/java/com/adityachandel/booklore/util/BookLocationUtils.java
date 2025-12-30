package com.adityachandel.booklore.util;

import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.service.file.FileFingerprint;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Slf4j
public final class BookLocationUtils {

    private BookLocationUtils() {
    }

    public static boolean canLocateBookInFiles(BookEntity book, Set<Path> currentPaths) {
        if (book == null || currentPaths == null) {
            log.debug("Cannot locate book - null parameters provided");
            return false;
        }

        Path bookPath = book.getFullFilePath();
        if (bookPath != null && currentPaths.contains(bookPath)) {
            return true;
        }

        String currentHash = book.getCurrentHash();
        if (currentHash != null && !currentHash.isEmpty() &&
            findBookByHashInPaths(currentHash, currentPaths, book.getId())) {
            return true;
        }

        String initialHash = book.getInitialHash();
        if (initialHash != null && !initialHash.isEmpty() &&
            !initialHash.equals(currentHash)) {
            if (findBookByHashInPaths(initialHash, currentPaths, book.getId())) {
                return true;
            }
        }

        log.debug("Book {} not found - no path or hash match in current files", book.getId());
        return false;
    }

    public static boolean findBookByHashInPaths(String hash, Set<Path> paths, Long bookId) {
        if (hash == null || hash.isEmpty() || paths == null) {
            return false;
        }

        boolean exists = paths.stream().anyMatch(path -> verifyFileMatchesHash(path, hash));

        if (exists) {
            log.debug("Book {} found at new location via hash '{}'", bookId, hash);
        }

        return exists;
    }

    public static boolean verifyFileMatchesHash(Path path, String expectedHash) {
        if (path == null || expectedHash == null) {
            return false;
        }

        try {
            if (!Files.exists(path)) {
                return false;
            }
            String fileHash = FileFingerprint.generateHash(path);
            boolean matches = expectedHash.equals(fileHash);

            if (!matches) {
                log.trace("Hash mismatch for '{}': expected '{}', got '{}'", path, expectedHash, fileHash);
            }

            return matches;
        } catch (Exception e) {
            log.debug("Error checking hash for '{}': {}", path, e.getMessage());
            return false;
        }
    }
}
