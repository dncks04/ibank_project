# 서비스 간 계약서

ibank(계정계, A)와 FDS·보호 흐름 서비스(B) 사이의 API 계약이다. 구현보다 이 명세가 먼저다.
구현 중 명세와 달라져야 하면 **명세를 먼저 고치고** B 담당자에게 알린 뒤 코드를 바꾼다.

| 파일 | 제공 | 호출 | 내용 |
|---|---|---|---|
| [fds-evaluate.openapi.yaml](fds-evaluate.openapi.yaml) | B | ibank | 거래 실행 여부 판정 (`POST /fds/evaluate`) |
| [ibank-internal.openapi.yaml](ibank-internal.openapi.yaml) | ibank | B | 보류 거래 실행·취소, 이벤트 조회, 판정용 고객·거래 정보, 시스템 토큰 |

## 거래 한 건의 흐름

```
고객 → ibank  POST /api/transactions/transfer
                ├─ [트랜잭션 1] 검증 → 거래 PENDING_REVIEW + 홀드 ACTIVE → TRANSACTION_REQUESTED 이벤트
                ├─ ibank → B  POST /fds/evaluate          (락·커넥션 없이 호출)
                └─ [트랜잭션 2] APPROVE → 자금 이동·분개 → COMPLETED → TRANSACTION_COMPLETED 이벤트
                                HOLD    → HELD(홀드 유지)   → TRANSACTION_HELD 이벤트

B → ibank     POST /internal/transactions/{id}/execute   → COMPLETED
B → ibank     POST /internal/transactions/{id}/cancel    → CANCELLED
(무응답)       ibank 만료 배치                              → EXPIRED   (최대 보류 시간, 기본 24시간)
```

- ibank는 보호자가 누구인지 모른다. B의 판정과 실행·취소 요청만 받는다.
- HELD 동안 금액은 홀드로 묶인다. 원장잔액은 그대로이고 가용잔액만 줄어든다.
- ibank는 B가 응답하지 않아도 돈이 영원히 묶이지 않도록 최대 보류 시간만 강제한다. 실제 만료 정책은 B가 그 전에 실행·취소로 결정한다.

## B가 알아야 할 동작

1. **`/fds/evaluate`는 같은 `transactionId`로 다시 호출될 수 있다.** ibank가 판정 직후 죽으면 복구 배치가 재판정한다.
2. **FDS가 느리거나 죽으면 ibank가 대신 판정한다.** 65세 이상(또는 나이 미상)은 HOLD, 그 외는 APPROVE.
   이때 이벤트의 `decision.decisionSource`가 `FALLBACK`이고 `decisionId`는 null이다.
   대체 정책이 보류한 거래의 사유 코드는 `FDS_UNAVAILABLE` 하나다.
   B는 FALLBACK으로 HELD된 거래를 이벤트로 보고 뒤늦게 판단해 실행·취소할 수 있다.
3. **이벤트는 at-least-once다.** `id`로 중복을 제거한다. 모르는 `eventType`은 건너뛴다.
4. **이벤트 전달은 지연될 수 있다.** ibank DB에 오래 열린 트랜잭션이 있으면 그 뒤 이벤트는 그 트랜잭션이 끝난 뒤에 보인다.
5. **실행(`/execute`)은 실패할 수 있다.** 422면 거래는 HELD로 남는다. 사유 코드를 보고 재시도하거나 취소한다.
6. **내부 API의 `Idempotency-Key`는 B가 저장해 둔다.** 응답을 못 받았으면 같은 키로 재시도한다.
   키를 잃어버리고 새 키로 다시 보내면, 이미 처리된 거래는 `409 TRANSACTION_NOT_HELD`가 된다
   (이때는 `GET /internal/transactions/{id}`로 현재 상태를 확인한다).

## 협의가 필요한 항목

아래는 ibank 쪽 초안으로 정해 둔 것이다. B 담당자와 확정해야 한다.

| # | 항목 | 초안 |
|---|---|---|
| 1 | ibank → B 호출 인증 | ibank 설정의 고정 토큰을 `Authorization: Bearer`로 전달. B가 ibank 시스템 토큰(JWKS 검증)을 원하면 변경 |
| 2 | 타임아웃 | 연결 300ms, 응답 300ms. B의 ML 추론 시간에 맞춰 조정 |
| 3 | 판정용 고객 정보에 잔액 포함 여부 | 미포함(지시서 기준). "전액 이체" 탐지에 필요하면 계좌별 `balance`·`availableBalance` 추가 |
| 4 | 판정용 거래 이력의 페이지 크기 | 기본 100, 최대 500 |
| 5 | 이벤트 보관 기간 | 30일(설정값). B가 이보다 오래 뒤처지면 이벤트를 잃는다 |
| 6 | 고객 토큰을 B가 직접 검증할 때의 무효화 지연 | 서명만 검증하면 최대 15분 지연. 즉시성이 필요하면 ibank에 검증 엔드포인트 추가 |

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| 0.1.0 | 2026-11-11 | 초안. 구현 전 작성 |
