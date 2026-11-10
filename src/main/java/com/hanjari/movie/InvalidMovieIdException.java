package com.hanjari.movie;

public class InvalidMovieIdException extends RuntimeException {

    public InvalidMovieIdException() {
        super("Invalid movie id");
    }
}
