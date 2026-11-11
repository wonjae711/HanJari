-- HJ-004: 합성 영화·상영관·좌석 초기 데이터(dev 프로필 전용, Flyway 반복 마이그레이션).
-- 몇 번을 다시 적용해도 결과가 같다(이미 있는 행은 건너뛴다). 계정·세션·회차는 만들지 않는다.
-- 회차는 운영 API 또는 테스트 fixture로 만든다.

INSERT INTO movies (title, runtime_minutes, age_rating)
SELECT seed.title, seed.runtime_minutes, seed.age_rating
FROM (SELECT '한자리 합성 영화 가' AS title, 100 AS runtime_minutes, 'ALL' AS age_rating
      UNION ALL SELECT '한자리 합성 영화 나', 135, 'AGE_15') seed
LEFT JOIN movies existing ON existing.title = seed.title
WHERE existing.id IS NULL
ORDER BY seed.title;

INSERT INTO theaters (name)
SELECT seed.name
FROM (SELECT '1관' AS name UNION ALL SELECT '2관' UNION ALL SELECT '3관') seed
LEFT JOIN theaters existing ON existing.name = seed.name
WHERE existing.id IS NULL
ORDER BY seed.name;

-- 1관 5행x10석, 2관 4행x8석, 3관 3행x6석.
INSERT INTO seats (theater_id, row_label, seat_number)
SELECT th.id, r.label, n.num
FROM (SELECT '1관' AS name, 5 AS row_count, 10 AS per_row
      UNION ALL SELECT '2관', 4, 8
      UNION ALL SELECT '3관', 3, 6) layout
JOIN theaters th ON th.name = layout.name
JOIN (SELECT 1 AS idx, 'A' AS label UNION ALL SELECT 2, 'B' UNION ALL SELECT 3, 'C'
      UNION ALL SELECT 4, 'D' UNION ALL SELECT 5, 'E') r ON r.idx <= layout.row_count
JOIN (SELECT 1 AS num UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
      UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10) n
    ON n.num <= layout.per_row
LEFT JOIN seats existing
    ON existing.theater_id = th.id AND existing.row_label = r.label AND existing.seat_number = n.num
WHERE existing.id IS NULL
ORDER BY th.id, r.idx, n.num;
