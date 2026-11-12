package com.ibank.internal.event;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 이벤트 조회 응답.
 *
 * @param events    id 오름차순
 * @param nextAfter 다음 조회의 after 값. 돌려준 이벤트가 없으면 요청의 after 그대로다.
 * @param hasMore   지금 바로 더 가져갈 이벤트가 있는지. false는 "공개된 것이 더 없다"는 뜻이지
 *                  "발생한 이벤트가 없다"는 뜻이 아니다(아직 공개되지 않은 이벤트가 있을 수 있다).
 */
public record InternalEventsResponse(List<Event> events, long nextAfter, boolean hasMore) {

    public record Event(long id, String eventType, String aggregateId, String occurredAt, JsonNode payload) {}
}
