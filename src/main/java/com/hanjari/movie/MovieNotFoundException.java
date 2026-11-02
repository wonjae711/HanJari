package com.hanjari.movie;

public class MovieNotFoundException extends RuntimeException {

    public MovieNotFoundException(long movieId) {
        super("Movie not found: " + movieId);
    }
}
