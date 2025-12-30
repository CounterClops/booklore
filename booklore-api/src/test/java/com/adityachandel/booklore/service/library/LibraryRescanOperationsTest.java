package com.adityachandel.booklore.service.library;

import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookAdditionalFileEntity;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.entity.LibraryPathEntity;
import com.adityachandel.booklore.model.enums.BookFileType;
import com.adityachandel.booklore.repository.BookAdditionalFileRepository;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.repository.LibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibraryRescanOperationsTest {

    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock
    private BookDeletionService bookDeletionService;
    @Mock
    private BookRestorationService bookRestorationService;

    @InjectMocks
    private LibraryRescanOperations rescanOperations;

    @TempDir
    Path tempDir;

    private LibraryEntity libraryEntity;
    private LibraryPathEntity libraryPathEntity;

    @BeforeEach
    void setUp() {
        libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath(tempDir.toString());

        libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);
        libraryEntity.setName("Test Library");
        libraryEntity.setLibraryPaths(List.of(libraryPathEntity));
        libraryEntity.setBookEntities(new ArrayList<>());
    }

    @Nested
    @DisplayName("handleDeletions")
    class HandleDeletionsTests {

        @Test
        @DisplayName("should skip when library not found")
        void shouldSkipWhenLibraryNotFound() {
            when(libraryRepository.findById(999L)).thenReturn(Optional.empty());

            rescanOperations.handleDeletions(List.of(), 999L);

            verify(bookDeletionService, never()).processDeletedLibraryFiles(any(), any());
            verify(bookAdditionalFileRepository, never()).deleteAllById(any());
        }

        @Test
        @DisplayName("should detect deleted books when file no longer exists")
        void shouldDetectDeletedBooks() throws IOException {
            Path existingFile = tempDir.resolve("existing.epub");
            Files.writeString(existingFile, "content");

            BookEntity existingBook = createBookEntity(1L, "", "existing.epub", false);
            BookEntity deletedBook = createBookEntity(2L, "", "deleted.epub", false);
            libraryEntity.setBookEntities(List.of(existingBook, deletedBook));

            LibraryFile diskFile = createLibraryFile("", "existing.epub");

            when(libraryRepository.findById(1L)).thenReturn(Optional.of(libraryEntity));
            when(bookAdditionalFileRepository.findByLibraryId(1L)).thenReturn(List.of());

            rescanOperations.handleDeletions(List.of(diskFile), 1L);

            verify(bookDeletionService).processDeletedLibraryFiles(
                    argThat(ids -> ids.size() == 1 && ids.contains(2L)),
                    any()
            );
        }

        @Test
        @DisplayName("should not report already deleted books")
        void shouldNotReportAlreadyDeletedBooks() throws IOException {
            Path existingFile = tempDir.resolve("existing.epub");
            Files.writeString(existingFile, "content");

            BookEntity existingBook = createBookEntity(1L, "", "existing.epub", false);
            BookEntity alreadyDeletedBook = createBookEntity(2L, "", "missing.epub", true);
            libraryEntity.setBookEntities(List.of(existingBook, alreadyDeletedBook));

            LibraryFile diskFile = createLibraryFile("", "existing.epub");

            when(libraryRepository.findById(1L)).thenReturn(Optional.of(libraryEntity));
            when(bookAdditionalFileRepository.findByLibraryId(1L)).thenReturn(List.of());

            rescanOperations.handleDeletions(List.of(diskFile), 1L);

            verify(bookDeletionService, never()).processDeletedLibraryFiles(any(), any());
        }
    }

    @Nested
    @DisplayName("handleRestorations")
    class HandleRestorationsTests {

        @Test
        @DisplayName("should skip when library not found")
        void shouldSkipWhenLibraryNotFound() {
            when(libraryRepository.findById(999L)).thenReturn(Optional.empty());

            rescanOperations.handleRestorations(List.of(), 999L);

            verify(bookRestorationService, never()).restoreDeletedBooks(any(), any());
        }

        @Test
        @DisplayName("should delegate to restoration service")
        void shouldDelegateToRestorationService() throws IOException {
            Path existingFile = tempDir.resolve("restored.epub");
            Files.writeString(existingFile, "content");

            LibraryFile diskFile = createLibraryFile("", "restored.epub");

            when(libraryRepository.findById(1L)).thenReturn(Optional.of(libraryEntity));

            rescanOperations.handleRestorations(List.of(diskFile), 1L);

            verify(bookRestorationService).restoreDeletedBooks(
                    eq(List.of(diskFile)),
                    eq(libraryEntity)
            );
        }
    }

    @Nested
    @DisplayName("categorizeAndUpdateExistingFiles")
    class CategorizeAndUpdateFilesTests {

        @Captor
        ArgumentCaptor<BookEntity> bookCaptor;

        @Test
        @DisplayName("should return new files for processing")
        void shouldReturnNewFilesForProcessing() throws IOException {
            Path newFile = tempDir.resolve("new-book.epub");
            Files.writeString(newFile, "new content");

            LibraryFile diskFile = createLibraryFile("", "new-book.epub");

            when(bookRepository.findBookPathComponentsByLibraryId(1L)).thenReturn(List.of());
            when(bookAdditionalFileRepository.findByLibraryId(1L)).thenReturn(List.of());

            List<LibraryFile> filesToProcess = rescanOperations.categorizeAndUpdateExistingFiles(List.of(diskFile), 1L);

            assertThat(filesToProcess).hasSize(1);
            assertThat(filesToProcess.get(0).getFileName()).isEqualTo("new-book.epub");
        }

        @Test
        @DisplayName("should not return files at known paths")
        void shouldNotReturnFilesAtKnownPaths() throws IOException {
            Path existingFile = tempDir.resolve("existing.epub");
            Files.writeString(existingFile, "content");

            LibraryFile diskFile = createLibraryFile("", "existing.epub");

            List<Object[]> pathComponents = Collections.singletonList(
                    new Object[]{1L, tempDir.toString(), "", "existing.epub"});
            when(bookRepository.findBookPathComponentsByLibraryId(1L)).thenReturn(pathComponents);
            when(bookAdditionalFileRepository.findByLibraryId(1L)).thenReturn(List.of());
            
            BookEntity book = createBookEntity(1L, "", "existing.epub", false);
            book.setLastModifiedTime(Instant.now());
            book.setFileSizeKb(1L);
            when(bookRepository.findById(1L)).thenReturn(Optional.of(book));

            List<LibraryFile> filesToProcess = rescanOperations.categorizeAndUpdateExistingFiles(List.of(diskFile), 1L);

            assertThat(filesToProcess).isEmpty();
        }

        @Test
        @DisplayName("should not return additional files")
        void shouldNotReturnAdditionalFiles() throws IOException {
            Path additionalFile = tempDir.resolve("additional.epub");
            Files.writeString(additionalFile, "additional content");

            LibraryFile diskFile = createLibraryFile("", "additional.epub");

            BookEntity parentBook = createBookEntity(99L, "", "parent.epub", false);
            
            BookAdditionalFileEntity additionalFileEntity = BookAdditionalFileEntity.builder()
                    .id(1L)
                    .book(parentBook)
                    .fileSubPath("")
                    .fileName("additional.epub")
                    .build();

            when(bookRepository.findBookPathComponentsByLibraryId(1L)).thenReturn(List.of());
            when(bookAdditionalFileRepository.findByLibraryId(1L)).thenReturn(List.of(additionalFileEntity));

            List<LibraryFile> filesToProcess = rescanOperations.categorizeAndUpdateExistingFiles(List.of(diskFile), 1L);

            assertThat(filesToProcess).isEmpty();
        }

        @Test
        @DisplayName("should update hash when file is modified")
        void shouldUpdateHashWhenFileIsModified() throws IOException {
            Path modifiedFile = tempDir.resolve("modified.epub");
            Files.writeString(modifiedFile, "new content that changed the file");

            LibraryFile diskFile = createLibraryFile("", "modified.epub");

            List<Object[]> pathComponents = Collections.singletonList(
                    new Object[]{1L, tempDir.toString(), "", "modified.epub"});
            when(bookRepository.findBookPathComponentsByLibraryId(1L)).thenReturn(pathComponents);
            when(bookAdditionalFileRepository.findByLibraryId(1L)).thenReturn(List.of());
            
            BookEntity book = createBookEntity(1L, "", "modified.epub", false);
            book.setLastModifiedTime(Instant.now().minusSeconds(3600)); // 1 hour ago
            book.setFileSizeKb(5L); // Different size
            when(bookRepository.findById(1L)).thenReturn(Optional.of(book));

            List<LibraryFile> filesToProcess = rescanOperations.categorizeAndUpdateExistingFiles(List.of(diskFile), 1L);

            assertThat(filesToProcess).isEmpty();
            verify(bookRepository).save(bookCaptor.capture());
            
            BookEntity savedBook = bookCaptor.getValue();
            assertThat(savedBook.getCurrentHash()).isNotNull();
            assertThat(savedBook.getLastModifiedTime()).isNotNull();
        }

        @Test
        @DisplayName("should initialize modification info when missing")
        void shouldInitializeModificationInfoWhenMissing() throws IOException {
            Path existingFile = tempDir.resolve("untracked.epub");
            Files.writeString(existingFile, "content");

            LibraryFile diskFile = createLibraryFile("", "untracked.epub");

            List<Object[]> pathComponents = Collections.singletonList(
                    new Object[]{1L, tempDir.toString(), "", "untracked.epub"});
            when(bookRepository.findBookPathComponentsByLibraryId(1L)).thenReturn(pathComponents);
            when(bookAdditionalFileRepository.findByLibraryId(1L)).thenReturn(List.of());
            
            BookEntity book = createBookEntity(1L, "", "untracked.epub", false);
            book.setLastModifiedTime(null); // No modification tracking yet
            book.setFileSizeKb(null);
            when(bookRepository.findById(1L)).thenReturn(Optional.of(book));

            rescanOperations.categorizeAndUpdateExistingFiles(List.of(diskFile), 1L);

            verify(bookRepository).save(bookCaptor.capture());
            
            BookEntity savedBook = bookCaptor.getValue();
            assertThat(savedBook.getLastModifiedTime()).isNotNull();
            assertThat(savedBook.getFileSizeKb()).isNotNull();
        }

        @Test
        @DisplayName("should handle files in subdirectories")
        void shouldHandleFilesInSubdirectories() throws IOException {
            Path subDir = tempDir.resolve("subfolder");
            Files.createDirectories(subDir);
            Path newFile = subDir.resolve("nested.epub");
            Files.writeString(newFile, "nested content");

            LibraryFile diskFile = createLibraryFile("subfolder", "nested.epub");

            when(bookRepository.findBookPathComponentsByLibraryId(1L)).thenReturn(List.of());
            when(bookAdditionalFileRepository.findByLibraryId(1L)).thenReturn(List.of());

            List<LibraryFile> filesToProcess = rescanOperations.categorizeAndUpdateExistingFiles(List.of(diskFile), 1L);

            assertThat(filesToProcess).hasSize(1);
            assertThat(filesToProcess.get(0).getFileSubPath()).isEqualTo("subfolder");
        }
    }

    private BookEntity createBookEntity(Long id, String subPath, String fileName, boolean deleted) {
        BookEntity book = new BookEntity();
        book.setId(id);
        book.setLibrary(libraryEntity);
        book.setLibraryPath(libraryPathEntity);
        book.setFileSubPath(subPath);
        book.setFileName(fileName);
        book.setDeleted(deleted);
        return book;
    }

    private LibraryFile createLibraryFile(String subPath, String fileName) {
        return LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileSubPath(subPath)
                .fileName(fileName)
                .bookFileType(BookFileType.EPUB)
                .build();
    }
}
