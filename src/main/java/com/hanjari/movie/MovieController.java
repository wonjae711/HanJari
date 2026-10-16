package com.hanjari.movie;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/movies")
public class MovieController {

    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    // 문자열로 받아 직접 검증한다. Long 바인딩은 "+1", "01"을 허용하므로 계약과 맞지 않는다.
    @GetMapping(value = "/{movieId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public MovieDetailResponse getMovie(@PathVariable("movieId") String movieId) {
        return movieService.getMovie(MovieIdParser.parse(movieId));
    }
}
