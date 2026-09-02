package com.ibank.global.response;

import java.util.List;

/**
 * 페이징 응답. Spring Data의 {@code Page}는 JPA 리포지토리가 만들어 주지만 MyBatis 조회에는
 * 그 장치가 없어, 목록 쿼리와 count 쿼리를 조합해 직접 만든다.
 *
 * <p>OFFSET 페이징은 뒤 페이지로 갈수록 건너뛸 앞 행을 세느라 느려진다(deep paging).
 * 데이터가 커지면 {@code created_at}·{@code id} 기준 keyset(seek) 페이징으로 옮기는 편이 맞다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        long totalPages
) {
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        long totalPages = size == 0 ? 0 : (totalElements + size - 1) / size;
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
