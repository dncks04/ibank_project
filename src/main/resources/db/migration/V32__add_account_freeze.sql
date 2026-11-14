-- 계좌 지급정지.
--
-- 계좌 상태에 FROZEN이 추가된다(status 컬럼에는 CHECK가 없어 값 추가에 스키마 변경이 필요 없다).
--   FROZEN: 돈이 나가는 것만 막는다. 출금·이체 출금·정기예금 가입 자금 인출이 불가하고, 입금은 허용한다.
--           사기 피해가 의심될 때 추가 유출을 막으면서, 들어오는 돈(연금, 환급 등)은 받을 수 있게 하기 위함이다.
--
-- frozen_reason: 지급정지를 요청한 쪽(FDS 서비스)이 보낸 사유 코드. 해제하면 NULL로 돌아간다.
ALTER TABLE accounts
    ADD COLUMN frozen_reason VARCHAR(50);
