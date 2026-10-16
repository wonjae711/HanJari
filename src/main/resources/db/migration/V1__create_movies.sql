CREATE TABLE movies (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    title           VARCHAR(200) NOT NULL,
    runtime_minutes INT          NOT NULL,
    age_rating      VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_movies_runtime_positive CHECK (runtime_minutes > 0),
    CONSTRAINT ck_movies_age_rating CHECK (age_rating IN ('ALL', 'AGE_12', 'AGE_15', 'ADULT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
