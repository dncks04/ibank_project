-- 탐지·보류 판단 책임이 FDS 서비스로 넘어가면서 ibank의 이상거래 경보를 없앤다.
-- 보류 거래의 실행·취소는 내부 API(/internal/transactions/{id}/execute|cancel)가 대신한다.

-- 1) 옛 방식으로 보류된 거래 정리.
--    2단계 처리 이전의 HELD 거래는 금액을 묶지 않았다(홀드 행이 없다). 새 구조에서는 "HELD 거래는 반드시
--    ACTIVE 홀드를 가진다"가 불변식이고, 내부 API의 실행·취소도 홀드를 전제로 한다. 그래서 그런 거래는
--    처리할 수도 없고 정산 검증에도 걸린다.
--    돈이 묶인 적이 없으므로 되돌릴 것이 없다 — 취소로 확정한다. 필요하면 고객이 다시 요청하면 된다.
--    대상은 HELD인데 ACTIVE 홀드가 없는 거래다. (PENDING_REVIEW는 2단계 처리와 함께 생긴 상태라 해당 없음)
UPDATE transactions t
SET status            = 'CANCELLED',
    resolution_reason = 'LEGACY_HOLD_WITHOUT_FUNDS'
WHERE t.status = 'HELD'
  AND NOT EXISTS (SELECT 1
                  FROM account_holds h
                  WHERE h.transaction_id = t.id
                    AND h.status = 'ACTIVE');

-- 2) 경보 테이블 삭제. 경보의 내용(어떤 규칙에 걸렸는지)은 감사 로그와 거래의 판정 사유에 남아 있다.
DROP TABLE fraud_alerts;
