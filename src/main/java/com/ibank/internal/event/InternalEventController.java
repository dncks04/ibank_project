package com.ibank.internal.event;

import com.ibank.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 거래 이벤트 조회 API. {@code GET /internal/events?after={id}&limit={n}} — {@code ROLE_SYSTEM} 전용.
 */
@RestController
@RequestMapping("/internal/events")
@RequiredArgsConstructor
public class InternalEventController {

    private static final int MAX_LIMIT = 1_000;

    private final InternalEventService service;

    @GetMapping
    public ResponseEntity<ApiResponse<InternalEventsResponse>> read(
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        if (after < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("after는 0 이상, limit은 1~" + MAX_LIMIT + " 범위여야 합니다.");
        }
        return ResponseEntity.ok(ApiResponse.success(service.read(after, limit)));
    }
}
