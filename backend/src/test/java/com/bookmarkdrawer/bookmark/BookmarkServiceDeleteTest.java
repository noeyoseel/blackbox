package com.bookmarkdrawer.bookmark;

import com.bookmarkdrawer.duplicates.DuplicateGroup;
import com.bookmarkdrawer.duplicates.DuplicateGroupRepository;
import com.bookmarkdrawer.duplicates.DuplicateSignal;
import com.bookmarkdrawer.duplicates.DuplicateType;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 영구 삭제 서비스 로직 단위 테스트(가상 데이터, DB 없음). 실제 SQL·잠금·롤백은 PermanentDeleteMySqlIT.
class BookmarkServiceDeleteTest {

    private BookmarkRepository bookmarkRepository;
    private DuplicateGroupRepository duplicateGroupRepository;
    private BookmarkService service;

    @BeforeEach
    void setUp() {
        bookmarkRepository = mock(BookmarkRepository.class);
        duplicateGroupRepository = mock(DuplicateGroupRepository.class);
        service = new BookmarkService(bookmarkRepository, duplicateGroupRepository);
    }

    private static Bookmark bookmark(String tweetId, boolean archived) {
        Bookmark b = new Bookmark(tweetId, "fake text " + tweetId, null, null, null, null, null,
                null, null, null, null, null, 1L, List.of());
        b.setArchived(archived);
        return b;
    }

    private static DuplicateGroup group(String key, boolean dismissed, String... members) {
        DuplicateGroup g = new DuplicateGroup(DuplicateType.NEAR, DuplicateSignal.TEXT_SIMILAR, 0.9, key, Set.of(members));
        if (dismissed) g.dismiss();
        return g;
    }

    @Test
    void deletesArchivedBookmarkUsingRowLock() {
        Bookmark target = bookmark("t-1", true);
        when(bookmarkRepository.findByTweetIdForUpdate("t-1")).thenReturn(Optional.of(target));
        when(duplicateGroupRepository.findByMemberTweetIdForUpdate("t-1")).thenReturn(List.of());

        service.deletePermanently("t-1");

        verify(bookmarkRepository).delete(target);
        verify(bookmarkRepository, never()).findByTweetId(anyString());
    }

    @Test
    void refusesBookmarkThatIsNotArchived() {
        when(bookmarkRepository.findByTweetIdForUpdate("t-2")).thenReturn(Optional.of(bookmark("t-2", false)));

        assertThatThrownBy(() -> service.deletePermanently("t-2")).isInstanceOf(IllegalStateException.class);

        verify(bookmarkRepository, never()).delete(any());
        verify(duplicateGroupRepository, never()).findByMemberTweetIdForUpdate(anyString());
        verify(duplicateGroupRepository, never()).delete(any());
    }

    @Test
    void missingBookmarkIsNotFound() {
        when(bookmarkRepository.findByTweetIdForUpdate("t-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deletePermanently("t-404")).isInstanceOf(EntityNotFoundException.class);

        verify(bookmarkRepository, never()).delete(any());
    }

    @Test
    void cleansPendingAndDismissedGroupsAndRemovesGroupsLeftWithOneMember() {
        Bookmark target = bookmark("t-1", true);
        DuplicateGroup pendingOfThree = group("k-p3", false, "t-1", "t-2", "t-3");
        DuplicateGroup pendingOfTwo = group("k-p2", false, "t-1", "t-4");
        DuplicateGroup dismissedOfTwo = group("k-d2", true, "t-1", "t-5");
        DuplicateGroup dismissedOfThree = group("k-d3", true, "t-1", "t-6", "t-7");
        when(bookmarkRepository.findByTweetIdForUpdate("t-1")).thenReturn(Optional.of(target));
        when(duplicateGroupRepository.findByMemberTweetIdForUpdate("t-1"))
                .thenReturn(List.of(pendingOfThree, pendingOfTwo, dismissedOfTwo, dismissedOfThree));

        service.deletePermanently("t-1");

        // 2명 이상 남은 그룹은 멤버만 빠지고 상태(dismissed)·키는 유지
        assertThat(pendingOfThree.getMemberTweetIds()).containsExactlyInAnyOrder("t-2", "t-3");
        assertThat(pendingOfThree.isDismissed()).isFalse();
        assertThat(dismissedOfThree.getMemberTweetIds()).containsExactlyInAnyOrder("t-6", "t-7");
        assertThat(dismissedOfThree.isDismissed()).isTrue();
        assertThat(dismissedOfThree.getGroupKey()).isEqualTo("k-d3");
        // 1명만 남은 그룹은 pending·dismissed 모두 삭제
        verify(duplicateGroupRepository).delete(pendingOfTwo);
        verify(duplicateGroupRepository).delete(dismissedOfTwo);
        verify(duplicateGroupRepository, never()).delete(pendingOfThree);
        verify(duplicateGroupRepository, never()).delete(dismissedOfThree);

        InOrder order = inOrder(bookmarkRepository, duplicateGroupRepository);
        order.verify(bookmarkRepository).findByTweetIdForUpdate("t-1");
        order.verify(duplicateGroupRepository).findByMemberTweetIdForUpdate("t-1");
        order.verify(bookmarkRepository).delete(target);
    }
}
