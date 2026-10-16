package com.hanjari.movie;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(MovieController.class)
class MovieControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;

    @Test
    void returnsMovieDetail() throws Exception {
        given(movieService.getMovie(1L))
                .willReturn(new MovieDetailResponse(1L, "한자리 테스트 영화", 120, "AGE_12"));

        mockMvc.perform(get("/api/v1/movies/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("한자리 테스트 영화"))
                .andExpect(jsonPath("$.runtimeMinutes").value(120))
                .andExpect(jsonPath("$.runtimeMinutes").isNumber())
                .andExpect(jsonPath("$.ageRating").value("AGE_12"));
    }

    @Test
    void returnsNotFoundForMissingMovie() throws Exception {
        given(movieService.getMovie(2L)).willThrow(new MovieNotFoundException(2L));

        expectError(mockMvc.perform(get("/api/v1/movies/2")),
                404, "MOVIE_NOT_FOUND", "영화를 찾을 수 없습니다.");
    }

    @Test
    void treatsMaximumIdAsValidLookup() throws Exception {
        given(movieService.getMovie(Long.MAX_VALUE)).willThrow(new MovieNotFoundException(Long.MAX_VALUE));

        expectError(mockMvc.perform(get("/api/v1/movies/9223372036854775807")),
                404, "MOVIE_NOT_FOUND", "영화를 찾을 수 없습니다.");
        verify(movieService).getMovie(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/movies/abc",
            "/api/v1/movies/0",
            "/api/v1/movies/-1",
            "/api/v1/movies/01",
            "/api/v1/movies/+1",   // 경로의 '+'는 공백이 아닌 문자 그대로 전달된다.
            "/api/v1/movies/%2B1", // 인코딩된 '+'
            "/api/v1/movies/%2D1", // 인코딩된 '-'
            "/api/v1/movies/9223372036854775808",
            "/api/v1/movies/99999999999999999999"
    })
    void returnsBadRequestForInvalidId(String path) throws Exception {
        expectError(mockMvc.perform(get(URI.create(path))),
                400, "INVALID_MOVIE_ID", "유효하지 않은 영화 ID입니다.");
        verify(movieService, never()).getMovie(anyLong());
    }

    @Test
    void returnsInternalErrorWhenDatabaseFails() throws Exception {
        given(movieService.getMovie(1L)).willThrow(
                new DataAccessResourceFailureException("Communications link failure: jdbc:mysql://secret-host password=hunter2"));

        expectError(mockMvc.perform(get("/api/v1/movies/1")),
                500, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.")
                .andExpect(content().string(Matchers.not(Matchers.containsString("jdbc"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("hunter2"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Exception"))));
    }

    private static ResultActions expectError(ResultActions actions, int status, String code, String message)
            throws Exception {
        return actions
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message));
    }
}
