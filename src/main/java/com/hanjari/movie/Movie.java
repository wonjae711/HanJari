package com.hanjari.movie;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "movies")
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "runtime_minutes", nullable = false)
    private int runtimeMinutes;

    // MySQL 방언의 네이티브 ENUM 매핑 대신 마이그레이션의 VARCHAR(20)과 일치시킨다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "age_rating", nullable = false, length = 20)
    private AgeRating ageRating;

    protected Movie() {
    }

    public Movie(String title, int runtimeMinutes, AgeRating ageRating) {
        this.title = title;
        this.runtimeMinutes = runtimeMinutes;
        this.ageRating = ageRating;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public int getRuntimeMinutes() {
        return runtimeMinutes;
    }

    public AgeRating getAgeRating() {
        return ageRating;
    }
}
