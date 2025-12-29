package com.adityachandel.booklore.service.fileprocessor;

import com.adityachandel.booklore.mapper.BookMapper;
import com.adityachandel.booklore.model.FileProcessResult;
import com.adityachandel.booklore.model.dto.Book;
import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.entity.LibraryPathEntity;
import com.adityachandel.booklore.model.enums.BookFileType;
import com.adityachandel.booklore.model.enums.FileProcessStatus;
import com.adityachandel.booklore.repository.BookAdditionalFileRepository;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.service.book.BookCreatorService;
import com.adityachandel.booklore.service.metadata.MetadataMatchService;
import com.adityachandel.booklore.service.watcher.BookFilePersistenceService;
import com.adityachandel.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractFileProcessorTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock
    private BookCreatorService bookCreatorService;
    @Mock
    private BookMapper bookMapper;
    @Mock
    private FileService fileService;
    @Mock
    private MetadataMatchService metadataMatchService;
    @Mock
    private BookFilePersistenceService bookFilePersistenceService;

    private TestFileProcessor fileProcessor;
    private LibraryFile libraryFile;
    private BookEntity bookEntity;
    private Book bookDto;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        fileProcessor = new TestFileProcessor(
                bookRepository,
                bookAdditionalFileRepository,
                bookCreatorService,
                bookMapper,
                fileService,
                metadataMatchService,
                bookFilePersistenceService
        );

        LibraryEntity library = new LibraryEntity();
        library.setId(1L);

        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setId(1L);
        libraryPath.setPath(tempDir.toString());

        Path testFile = tempDir.resolve("test.epub");
        Files.writeString(testFile, "test content");

        libraryFile = LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath("")
                .fileName("test.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        bookEntity = new BookEntity();
        bookEntity.setId(1L);
        bookEntity.setLibrary(library);
        bookEntity.setLibraryPath(libraryPath);
        bookEntity.setFileName("test.epub");
        bookEntity.setFileSubPath("");

        bookDto = Book.builder().id(1L).build();
    }

    @Test
    void processFile_whenHashNotFound_createsNewBook() {
        when(bookRepository.findAllByCurrentHashOrInitialHashAndLibrary(anyString(), anyLong())).thenReturn(List.of());
        when(bookCreatorService.createShellBook(any(), any())).thenReturn(bookEntity);
        when(metadataMatchService.calculateMatchScore(any())).thenReturn(0.0f);
        when(bookMapper.toBook(any())).thenReturn(bookDto);

        FileProcessResult result = fileProcessor.processFile(libraryFile);

        assertEquals(FileProcessStatus.NEW, result.getStatus());
        assertEquals(bookDto, result.getBook());
        verify(bookRepository).findAllByCurrentHashOrInitialHashAndLibrary(anyString(), eq(1L));
        verify(bookCreatorService).createShellBook(libraryFile, BookFileType.EPUB);
        verify(bookFilePersistenceService, never()).updatePathIfChanged(any(), any(), any(), anyString());
    }

    @Test
    void processFile_whenHashFoundWithMissingFile_relinksBook() {
        bookEntity.setFileSubPath("invalid/path");
        bookEntity.setFileName("does-not-exist.epub");
        
        when(bookRepository.findAllByCurrentHashOrInitialHashAndLibrary(anyString(), anyLong())).thenReturn(List.of(bookEntity));
        when(bookMapper.toBook(any())).thenReturn(bookDto);

        FileProcessResult result = fileProcessor.processFile(libraryFile);

        assertEquals(FileProcessStatus.RELINKED, result.getStatus());
        assertEquals(bookDto, result.getBook());
        verify(bookRepository).findAllByCurrentHashOrInitialHashAndLibrary(anyString(), eq(1L));
        verify(bookFilePersistenceService).updatePathIfChanged(eq(bookEntity), eq(libraryFile.getLibraryEntity()), any(Path.class), anyString());
        verify(bookCreatorService, never()).createShellBook(any(), any());
    }

    @Test
    void processFile_whenHashFoundForDeletedBookWithMissingFile_relinksBook() {
        bookEntity.setDeleted(true);
        bookEntity.setFileSubPath("invalid/path");
        bookEntity.setFileName("deleted.epub");
        
        when(bookRepository.findAllByCurrentHashOrInitialHashAndLibrary(anyString(), anyLong())).thenReturn(List.of(bookEntity));
        when(bookMapper.toBook(any())).thenReturn(bookDto);

        FileProcessResult result = fileProcessor.processFile(libraryFile);

        assertEquals(FileProcessStatus.RELINKED, result.getStatus());
        verify(bookFilePersistenceService).updatePathIfChanged(eq(bookEntity), eq(libraryFile.getLibraryEntity()), any(Path.class), anyString());
    }

    @Test
    void processFile_whenRelinking_updatesCurrentHashIfDifferent() {
        String oldHash = "old123";
        bookEntity.setCurrentHash(oldHash);
        bookEntity.setFileSubPath("invalid/path");
        bookEntity.setFileName("old.epub");
        
        when(bookRepository.findAllByCurrentHashOrInitialHashAndLibrary(anyString(), anyLong())).thenReturn(List.of(bookEntity));
        when(bookMapper.toBook(any())).thenReturn(bookDto);

        fileProcessor.processFile(libraryFile);

        verify(bookFilePersistenceService).updatePathIfChanged(
            argThat(book -> !oldHash.equals(book.getCurrentHash())),
            any(), any(), anyString()
        );
    }

    @Test
    void processFile_whenHashMatchesButFileExists_createsNewBook() throws IOException {
        Path existingFile = tempDir.resolve("existing.epub");
        Files.writeString(existingFile, "existing content");
        
        bookEntity.setFileSubPath("");
        bookEntity.setFileName("existing.epub");
        bookEntity.setCurrentHash("matching-hash");
        
        when(bookRepository.findAllByCurrentHashOrInitialHashAndLibrary(anyString(), anyLong())).thenReturn(List.of(bookEntity));
        when(bookCreatorService.createShellBook(any(), any())).thenReturn(bookEntity);
        when(metadataMatchService.calculateMatchScore(any())).thenReturn(0.0f);
        when(bookMapper.toBook(any())).thenReturn(bookDto);

        FileProcessResult result = fileProcessor.processFile(libraryFile);

        assertEquals(FileProcessStatus.NEW, result.getStatus());
        verify(bookFilePersistenceService, never()).updatePathIfChanged(any(), any(), any(), anyString());
        verify(bookCreatorService).createShellBook(libraryFile, BookFileType.EPUB);
    }

    @Test
    void processFile_whenMultipleCandidatesWithMissingFiles_relinksToFirst() {
        BookEntity book1 = new BookEntity();
        book1.setId(100L);
        book1.setLibrary(bookEntity.getLibrary());
        book1.setLibraryPath(bookEntity.getLibraryPath());
        book1.setFileSubPath("missing/path1");
        book1.setFileName("missing1.epub");
        
        BookEntity book2 = new BookEntity();
        book2.setId(200L);
        book2.setLibrary(bookEntity.getLibrary());
        book2.setLibraryPath(bookEntity.getLibraryPath());
        book2.setFileSubPath("missing/path2");
        book2.setFileName("missing2.epub");
        
        when(bookRepository.findAllByCurrentHashOrInitialHashAndLibrary(anyString(), anyLong()))
                .thenReturn(List.of(book1, book2));
        when(bookMapper.toBook(any())).thenReturn(bookDto);

        FileProcessResult result = fileProcessor.processFile(libraryFile);

        assertEquals(FileProcessStatus.RELINKED, result.getStatus());
        verify(bookFilePersistenceService).updatePathIfChanged(eq(book1), any(), any(), anyString());
        verify(bookFilePersistenceService, never()).updatePathIfChanged(eq(book2), any(), any(), anyString());
    }

    @Test
    void processFile_whenMultipleCandidatesAllWithValidFiles_createsNewBook() throws IOException {
        Path existingFile1 = tempDir.resolve("existing1.epub");
        Path existingFile2 = tempDir.resolve("existing2.epub");
        Files.writeString(existingFile1, "content1");
        Files.writeString(existingFile2, "content2");
        
        BookEntity book1 = new BookEntity();
        book1.setId(100L);
        book1.setLibrary(bookEntity.getLibrary());
        book1.setLibraryPath(bookEntity.getLibraryPath());
        book1.setFileSubPath("");
        book1.setFileName("existing1.epub");
        
        BookEntity book2 = new BookEntity();
        book2.setId(200L);
        book2.setLibrary(bookEntity.getLibrary());
        book2.setLibraryPath(bookEntity.getLibraryPath());
        book2.setFileSubPath("");
        book2.setFileName("existing2.epub");
        
        when(bookRepository.findAllByCurrentHashOrInitialHashAndLibrary(anyString(), anyLong()))
                .thenReturn(List.of(book1, book2));
        when(bookCreatorService.createShellBook(any(), any())).thenReturn(bookEntity);
        when(metadataMatchService.calculateMatchScore(any())).thenReturn(0.0f);
        when(bookMapper.toBook(any())).thenReturn(bookDto);

        FileProcessResult result = fileProcessor.processFile(libraryFile);

        assertEquals(FileProcessStatus.NEW, result.getStatus());
        verify(bookFilePersistenceService, never()).updatePathIfChanged(any(), any(), any(), anyString());
        verify(bookCreatorService).createShellBook(libraryFile, BookFileType.EPUB);
    }

    private static class TestFileProcessor extends AbstractFileProcessor {

        protected TestFileProcessor(BookRepository bookRepository,
                                    BookAdditionalFileRepository bookAdditionalFileRepository,
                                    BookCreatorService bookCreatorService,
                                    BookMapper bookMapper,
                                    FileService fileService,
                                    MetadataMatchService metadataMatchService,
                                    BookFilePersistenceService bookFilePersistenceService) {
            super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, bookFilePersistenceService);
        }

        @Override
        public List<BookFileType> getSupportedTypes() {
            return List.of(BookFileType.EPUB);
        }

        @Override
        protected BookEntity processNewFile(LibraryFile libraryFile) {
            return bookCreatorService.createShellBook(libraryFile, BookFileType.EPUB);
        }

        @Override
        public boolean generateCover(BookEntity bookEntity) {
            return false;
        }
    }
}
