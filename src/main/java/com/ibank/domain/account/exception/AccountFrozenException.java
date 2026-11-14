package com.ibank.domain.account.exception;

/**
 * 지급정지된 계좌에서 돈을 내보내려 했다. 지급정지는 출금만 막고 입금은 허용한다.
 *
 * <p>{@link InactiveAccountException}의 하위 타입이다. "이 계좌로는 이 거래를 할 수 없다"는 점에서 같은 부류이고,
 * 그 예외를 처리하는 기존 코드(실행 시점 재검증 실패 처리 등)가 지급정지도 함께 다루게 된다.
 * 구분이 필요한 곳(실패 사유 코드, 내부 API 오류 코드)에서는 이 타입을 먼저 확인한다.
 */
public class AccountFrozenException extends InactiveAccountException {

    public AccountFrozenException(String accountNumber) {
        super("지급정지된 계좌입니다.", "지급정지된 계좌에서 출금 시도: " + accountNumber);
    }
}
