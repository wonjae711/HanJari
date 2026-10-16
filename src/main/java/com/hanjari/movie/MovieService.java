package com.hanjari.movie;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MovieService {

    private final MovieRepository movieRepository;

    public MovieService(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    @Transactional(readOnly = true)
    public MovieDetailResponse getMovie(long movieId) {
        return movieRepository.findById(movieId)
                .map(MovieDetailResponse::from)
                .orElseThrow(() -> new MovieNotFoundException(movieId));
    }
}
