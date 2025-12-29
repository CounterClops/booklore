package com.adityachandel.booklore.service.fileprocessor;

import com.adityachandel.booklore.mapper.BookMapper;
import com.adityachandel.booklore.model.FileProcessResult;
import com.adityachandel.booklore.model.dto.Book;
import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.enums.FileProcessStatus;
import com.adityachandel.booklore.repository.BookAdditionalFileRepository;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.service.book.BookCreatorService;
import com.adityachandel.booklore.service.file.FileFingerprint;
import com.adityachandel.booklore.service.metadata.MetadataMatchService;
import com.adityachandel.booklore.service.watcher.BookFilePersistenceService;
import com.adityachandel.booklore.util.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Slf4j
public abstract class AbstractFileProcessor implements BookFileProcessor {

    protected final BookRepository bookRepository;
    protected final BookAdditionalFileRepository bookAdditionalFileRepository;
    protected final BookCreatorService bookCreatorService;
    protected final BookMapper bookMapper;
    protected final MetadataMatchService metadataMatchService;
    protected final FileService fileService;
    protected final BookFilePersistenceService bookFilePersistenceService;


    protected AbstractFileProcessor(BookRepository bookRepository,
                                    BookAdditionalFileRepository bookAdditionalFileRepository,
                                    BookCreatorService bookCreatorService,
                                    BookMapper bookMapper,
                                    FileService fileService,
                                    MetadataMatchService metadataMatchService,
                                    BookFilePersistenceService bookFilePersistenceService) {
        this.bookRepository = bookRepository;
        this.bookAdditionalFileRepository = bookAdditionalFileRepository;
        this.bookCreatorService = bookCreatorService;
        this.bookMapper = bookMapper;
        this.metadataMatchService = metadataMatchService;
        this.fileService = fileService;
        this.bookFilePersistenceService = bookFilePersistenceService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public FileProcessResult processFile(LibraryFile libraryFile) {
        Path path = libraryFile.getFullPath();
        String hash = FileFingerprint.generateHash(path);
        
        if (hash == null || hash.isEmpty()) {
            log.warn("[HASH_GENERATION_FAILED] Could not generate hash for file: '{}'", path);
            Book book = createAndMapBook(libraryFile, null);
            return new FileProcessResult(book, FileProcessStatus.NEW);
        }
        
        Long libraryId = libraryFile.getLibraryEntity().getId();
        List<BookEntity> candidateBooks = bookRepository.findAllByCurrentHashOrInitialHashAndLibrary(hash, libraryId);
        
        if (!candidateBooks.isEmpty()) {
            Optional<BookEntity> bookToRelink = selectBestBookForRelink(candidateBooks, hash, libraryId);
            
            if (bookToRelink.isPresent()) {
                BookEntity book = bookToRelink.get();
                if (!hash.equals(book.getCurrentHash())) {
                    book.setCurrentHash(hash);
                    log.info("[HASH_UPDATE] Updated currentHash for book {} from '{}' to '{}'", 
                            book.getId(), book.getCurrentHash(), hash);
                }
                bookFilePersistenceService.updatePathIfChanged(book, libraryFile.getLibraryEntity(), path, hash);
                log.info("[RELINK] File relinked via hash '{}' in library {}: '{}'", hash, libraryId, path);
                return new FileProcessResult(bookMapper.toBook(book), FileProcessStatus.RELINKED);
            }
        }
        
        Book book = createAndMapBook(libraryFile, hash);
        return new FileProcessResult(book, FileProcessStatus.NEW);
    }

    private Optional<BookEntity> selectBestBookForRelink(List<BookEntity> candidateBooks, String hash, Long libraryId) {
        List<BookEntity> booksWithMissingFiles = candidateBooks.stream()
                .filter(book -> !Files.exists(book.getFullFilePath()))
                .toList();
        
        if (!booksWithMissingFiles.isEmpty()) {
            BookEntity selected = booksWithMissingFiles.get(0);
            log.info("[PRIORITY_RELINK] Found {} books with hash '{}' in library {}. " +
                    "Prioritizing book {} with missing file path: {}", 
                    candidateBooks.size(), hash, libraryId, selected.getId(), selected.getFullFilePath());
            return Optional.of(selected);
        }
        
        log.info("[DUPLICATE_HASH_SKIP] Found {} books with hash '{}' in library {} - all have valid file paths. " +
                "Creating new book entry to allow duplicate.", 
                candidateBooks.size(), hash, libraryId);
        return Optional.empty();
    }

    private Book createAndMapBook(LibraryFile libraryFile, String hash) {
        BookEntity entity = processNewFile(libraryFile);
        entity.setCurrentHash(hash);
        entity.setMetadataMatchScore(metadataMatchService.calculateMatchScore(entity));
        bookCreatorService.saveConnections(entity);
        return bookMapper.toBook(entity);
    }

    protected abstract BookEntity processNewFile(LibraryFile libraryFile);
}