package com.hanjari.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MovieIdParserTest {

    @Test
    void parsesMinimumId() {
        assertThat(MovieIdParser.parse("1")).isEqualTo(1L);
    }

    @Test
    void parsesMaximumId() {
        assertThat(MovieIdParser.parse("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "abc", "0", "-1", "01", "+1", " 1", "1 ", "1.0", "1e3",
            "9223372036854775808", "99999999999999999999",
            "١" // 아랍-인도 숫자 1: Character.isDigit은 참이지만 십진 ASCII가 아니다.
    })
    void rejectsInvalidIds(String raw) {
        assertThatThrownBy(() -> MovieIdParser.parse(raw)).isInstanceOf(InvalidMovieIdException.class);
    }
}
