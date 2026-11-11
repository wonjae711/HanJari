package com.hanjari.operations;

import com.hanjari.common.RequestValues;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 최소 운영 API. 권한(OPERATOR)·Origin·CSRF 검사는 SecurityConfig가 컨트롤러 진입 전에 수행한다
 * (미인증 401, 일반 회원 403). 계정·권한 변경, 상영관·좌석 편집, 영화 삭제 API는 없다.
 */
@RestController
@RequestMapping(value = "/api/v1/ops", produces = MediaType.APPLICATION_JSON_VALUE)
public class OpsController {

    private final OpsService opsService;

    public OpsController(OpsService opsService) {
        this.opsService = opsService;
    }

    @PostMapping("/movies")
    public ResponseEntity<OpsService.OpsMovie> createMovie(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(opsService.createMovie(OpsRequestPolicy.movieCreate(body)));
    }

    @PatchMapping("/movies/{movieId}")
    public OpsService.OpsMovie updateMovie(@PathVariable("movieId") String movieId,
            @RequestBody(required = false) Map<String, Object> body) {
        long id = RequestValues.positiveId(movieId, "INVALID_MOVIE_ID", "유효하지 않은 영화 ID입니다.");
        return opsService.updateMovie(id, OpsRequestPolicy.moviePatch(body));
    }

    @PostMapping("/screenings")
    public ResponseEntity<OpsService.OpsScreening> createScreening(
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(opsService.createScreening(OpsRequestPolicy.screeningCreate(body)));
    }

    @PatchMapping("/screenings/{screeningId}")
    public OpsService.OpsScreening updateScreening(@PathVariable("screeningId") String screeningId,
            @RequestBody(required = false) Map<String, Object> body) {
        long id = screeningId(screeningId);
        return opsService.updateScreening(id, OpsRequestPolicy.screeningPatch(body));
    }

    @DeleteMapping("/screenings/{screeningId}")
    public ResponseEntity<Void> deleteScreening(@PathVariable("screeningId") String screeningId) {
        opsService.deleteScreening(screeningId(screeningId));
        return ResponseEntity.noContent().build();
    }

    private static long screeningId(String raw) {
        return RequestValues.positiveId(raw, "INVALID_SCREENING_ID", "유효하지 않은 회차 ID입니다.");
    }
}
