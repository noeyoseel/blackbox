package com.bookmarkdrawer.folder;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FolderService {

    private final FolderRepository folderRepository;
    private final BookmarkRepository bookmarkRepository;

    @Transactional
    public Folder createFolder(String name) {
        String trimmed = name.trim();
        return folderRepository.findByName(trimmed)
                .orElseGet(() -> folderRepository.save(new Folder(trimmed)));
    }

    public List<FolderCount> listFolders() {
        return folderRepository.findAllByOrderByNameAsc().stream()
                .map(f -> new FolderCount(f.getId(), f.getName(),
                        bookmarkRepository.countByFolderIdAndArchivedFalse(f.getId())))
                .toList();
    }

    public List<Bookmark> bookmarksInFolder(Long folderId) {
        return bookmarkRepository.findByFolderIdAndArchivedFalseOrderByCollectedAtDesc(folderId);
    }

    public Folder getFolder(Long folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new EntityNotFoundException("폴더를 찾을 수 없음: " + folderId));
    }

    @Transactional
    public void deleteFolder(Long folderId) {
        for (Bookmark bookmark : bookmarkRepository.findByFolderId(folderId)) {
            bookmark.assignFolder(null, null);
        }
        folderRepository.deleteById(folderId);
    }

    @Transactional
    public Folder renameFolder(Long folderId, String name) {
        Folder folder = getFolder(folderId);
        String trimmed = name.trim();
        folder.rename(trimmed);

        // 폴더 이름을 각 북마크에 그대로 복사해뒀으니(단순 조회를 위해), 여기서도 같이 갱신한다.
        for (Bookmark bookmark : bookmarkRepository.findByFolderId(folderId)) {
            bookmark.assignFolder(folderId, trimmed);
        }
        return folder;
    }
}
