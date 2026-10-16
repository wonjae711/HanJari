package com.hanjari.movie;

/** 영화 상세 응답. 엔티티를 직접 직렬화하지 않고 API 계약의 네 필드로 고정한다. */
public record MovieDetailResponse(long id, String title, int runtimeMinutes, String ageRating) {

    public static MovieDetailResponse from(Movie movie) {
        return new MovieDetailResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getRuntimeMinutes(),
                movie.getAgeRating().name());
    }
}
