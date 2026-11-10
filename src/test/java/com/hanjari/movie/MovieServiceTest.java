package com.hanjari.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MovieServiceTest {

    private final MovieRepository movieRepository = mock(MovieRepository.class);
    private final MovieService movieService = new MovieService(movieRepository);

    @Test
    void returnsDetailForExistingMovie() {
        Movie movie = spy(new Movie("한자리 테스트 영화", 120, AgeRating.AGE_12));
        given(movie.getId()).willReturn(1L);
        given(movieRepository.findById(1L)).willReturn(Optional.of(movie));

        MovieDetailResponse response = movieService.getMovie(1L);

        assertThat(response).isEqualTo(new MovieDetailResponse(1L, "한자리 테스트 영화", 120, "AGE_12"));
    }

    @Test
    void throwsNotFoundForMissingMovie() {
        given(movieRepository.findById(2L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> movieService.getMovie(2L)).isInstanceOf(MovieNotFoundException.class);
    }
}
