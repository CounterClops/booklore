package com.adityachandel.booklore.service.watcher;

import com.adityachandel.booklore.mapper.BookMapper;
import com.adityachandel.booklore.model.dto.Book;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.entity.LibraryPathEntity;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.service.NotificationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static com.adityachandel.booklore.model.enums.PermissionType.ADMIN;
import static com.adityachandel.booklore.model.enums.PermissionType.MANAGE_LIBRARY;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookFilePersistenceServiceTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private BookMapper bookMapper;

    @InjectMocks
    private BookFilePersistenceService bookFilePersistenceService;

    private BookEntity bookEntity;
    private LibraryEntity libraryEntity;
    private LibraryPathEntity libraryPathEntity;
    private Path newPath;
    private String currentHash;

    @BeforeEach
    void setUp() {
        libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);

        libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/root");
        libraryPathEntity.setLibrary(libraryEntity);

        libraryEntity.setLibraryPaths(List.of(libraryPathEntity));

        bookEntity = new BookEntity();
        bookEntity.setId(1L);
        bookEntity.setLibrary(libraryEntity);
        bookEntity.setLibraryPath(libraryPathEntity);
        bookEntity.setFileSubPath("Fiction");
        bookEntity.setFileName("original.epub");

        newPath = Paths.get("/library/root/SciFi/renamed.epub");
        currentHash = "abc123";

        when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPathEntity);
        when(bookMapper.toBookWithDescription(any(), anyBoolean())).thenReturn(Book.builder().build());
    }

    @Test
    void updatePathIfChanged_whenPathChanged_sendsRelinkNotification() {
        bookFilePersistenceService.updatePathIfChanged(bookEntity, libraryEntity, newPath, currentHash);

        verify(bookRepository).save(bookEntity);
        verify(notificationService).sendMessageToPermissions(eq(Topic.BOOK_RELINKED), any(), eq(Set.of(ADMIN, MANAGE_LIBRARY)));
    }

    @Test
    void updatePathIfChanged_whenFileNameChanged_sendsRelinkNotification() {
        Path pathWithNewFileName = Paths.get("/library/root/Fiction/newname.epub");

        bookFilePersistenceService.updatePathIfChanged(bookEntity, libraryEntity, pathWithNewFileName, currentHash);

        verify(bookRepository).save(bookEntity);
        verify(notificationService).sendMessageToPermissions(eq(Topic.BOOK_RELINKED), any(), eq(Set.of(ADMIN, MANAGE_LIBRARY)));
    }

    @Test
    void updatePathIfChanged_whenBookWasDeleted_sendsAddNotification() {
        bookEntity.setDeleted(true);
        Path samePath = Paths.get("/library/root/Fiction/original.epub");

        bookFilePersistenceService.updatePathIfChanged(bookEntity, libraryEntity, samePath, currentHash);

        verify(bookRepository).save(bookEntity);
        verify(notificationService).sendMessageToPermissions(eq(Topic.BOOK_ADD), any(), eq(Set.of(ADMIN, MANAGE_LIBRARY)));
    }

    @Test
    void updatePathIfChanged_whenPathUnchanged_sendsAddNotificationOnly() {
        Path samePath = Paths.get("/library/root/Fiction/original.epub");

        bookFilePersistenceService.updatePathIfChanged(bookEntity, libraryEntity, samePath, currentHash);

        verify(bookRepository, never()).save(any());
        verify(notificationService).sendMessageToPermissions(eq(Topic.BOOK_ADD), any(), eq(Set.of(ADMIN, MANAGE_LIBRARY)));
    }

    @Test
    void updatePathIfChanged_updatesAllPathFields() {
        bookFilePersistenceService.updatePathIfChanged(bookEntity, libraryEntity, newPath, currentHash);

        verify(bookRepository).save(argThat(book -> 
            "SciFi".equals(book.getFileSubPath()) && 
            "renamed.epub".equals(book.getFileName()) &&
            Boolean.FALSE.equals(book.getDeleted())
        ));
    }
}
