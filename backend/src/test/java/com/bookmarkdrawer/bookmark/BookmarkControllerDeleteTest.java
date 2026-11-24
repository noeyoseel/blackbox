package com.bookmarkdrawer.bookmark;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DELETE /api/bookmarks/{tweetId}의 상태 코드 계약. 화면(permanent-delete.js)이 404/409를 구분해
// 안내하므로 이 매핑이 바뀌면 안 된다. DB 장애 등 그 밖의 오류는 404/409로 바꾸지 않는다.
class BookmarkControllerDeleteTest {

    private BookmarkService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BookmarkService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BookmarkController(service)).build();
    }

    @Test
    void deletedReturns200() throws Exception {
        mvc.perform(delete("/api/bookmarks/t-1")).andExpect(status().isOk());
    }

    @Test
    void missingReturns404() throws Exception {
        doThrow(new EntityNotFoundException("x")).when(service).deletePermanently("t-404");
        mvc.perform(delete("/api/bookmarks/t-404")).andExpect(status().isNotFound());
    }

    @Test
    void notArchivedReturns409() throws Exception {
        doThrow(new IllegalStateException("x")).when(service).deletePermanently("t-active");
        mvc.perform(delete("/api/bookmarks/t-active")).andExpect(status().isConflict());
    }

    @Test
    void databaseFailureIsNotTurnedInto404Or409() {
        doThrow(new DataAccessResourceFailureException("db down")).when(service).deletePermanently("t-db");
        // standalone MockMvc는 처리되지 않은 예외를 그대로 던진다 = 실제 서버에서는 500
        assertThatThrownBy(() -> mvc.perform(delete("/api/bookmarks/t-db")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);
    }
}
