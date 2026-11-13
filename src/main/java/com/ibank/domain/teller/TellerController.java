package com.ibank.domain.teller;

import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 창구 API. {@code ROLE_TELLER}만 접근한다(SecurityConfig의 {@code /api/teller/**}).
 *
 * <p>POST /api/teller/withdrawals — 창구 직원이 고객 계좌에서 현금을 출금한다.
 * 고객 본인의 출금과 같은 2단계 처리(가승인 → 판정 → 확정)를 타되, 채널이 {@code TELLER}로 기록되고
 * 계좌 소유권 대신 직원 권한으로 처리된다. 판정이 보류면 응답의 {@code status=HELD}와 {@code reasonCodes}를 보고
 * 창구 화면이 고객에게 확인 질문을 띄운다. 처리한 직원은 감사 로그의 actor로 남는다.
 */
@RestController
@RequestMapping("/api/teller")
@RequiredArgsConstructor
public class TellerController {

    private final TransferService transferService;

    @PostMapping("/withdrawals")
    public ResponseEntity<ApiResponse<TransferResponse>> withdraw(@Valid @RequestBody WithdrawRequest request) {
        return ResponseEntity.ok(ApiResponse.success(transferService.withdrawAtTeller(request)));
    }
}
