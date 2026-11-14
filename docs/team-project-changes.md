# 팀 프로젝트 변경 사항 (base-ibank 이후)

고령 고객 보호 뱅킹 시스템에서 ibank는 계정계(A)를 맡는다. 이 문서는 `base-ibank` 태그 이후
바뀐 내용을 기능별로 모았다. 서비스 간 API는 [docs/contracts](contracts/README.md)가 기준이다.

- FDS·보호 흐름(B): 거래를 승인·보류할지 판정하고 보류 거래의 실행·취소를 ibank 내부 API로 요청한다
- 합성 데이터·프론트엔드(C): 대량 적재 형식은 [bulk-load-format.md](bulk-load-format.md)
- ibank는 보호자가 누구인지 모른다. 판정과 보호 흐름은 모두 B의 책임이다

## 지킨 원칙

- 불변식: 분개 합계 0, 음수 잔액 없음, 잔액 = 원장 합계, 멱등성 replay/충돌 규칙
- DB 행 락을 쥔 채로 외부 HTTP를 호출하지 않는다. FDS 호출은 락·트랜잭션 밖에서 한다
- 기존 Flyway 마이그레이션은 건드리지 않았다. 새 마이그레이션은 V23~V32
- 홀드는 원장 분개를 만들지 않는다
- 락 순서는 어디서나 **거래 행 → 계좌(계좌번호 오름차순)**

## 마이그레이션

| 버전 | 내용 |
|---|---|
| V23 | `account_holds` (거래당 홀드 1건, ACTIVE 부분 인덱스) |
| V24 | `transactions`에 `channel`, `completed_at`, 판정 결과, `failure_code` |
| V25 | `users.birth_date` (AES-GCM 암호화) |
| V26 | `internal_idempotency_keys`, `transactions.resolution_reason` |
| V27 | `outbox_events`, `outbox_publish_state` |
| V28 | 옛 방식 보류 거래(홀드 없는 HELD) → CANCELLED, `fraud_alerts` 삭제 |
| V29 | `accounts.account_type`, 만기일, 약정 금리, 지급 이자 |
| V30 | `bulk_load_errors` |
| V31 | `reconciliation_check_results` |
| V32 | `accounts.frozen_reason` |

## 1. 홀드와 가용잔액

- 판정 대기·보류 중인 거래 금액을 출금 계좌에 홀드로 묶는다
- 가용잔액 = 원장잔액 − ACTIVE 홀드 합계. 출금·이체의 잔액 검증은 가용잔액 기준
- 홀드 상태: ACTIVE → CONSUMED(실행) / RELEASED(취소·실패) / EXPIRED(만료)
- 금액을 바꾸는 `HoldService` 메서드는 `MANDATORY` 전파라 계좌 락을 쥔 트랜잭션 안에서만 부를 수 있다
- 계좌 조회 응답에 `availableBalance`, `heldAmount` 추가 (같은 스냅샷에서 읽도록 REPEATABLE_READ)

## 2. 2단계 거래 처리와 판정 포트

이체·출금을 세 단계로 나눴다.

1. **가승인**: 짧은 트랜잭션, 계좌 락. 멱등성·소유권·계좌 상태·1일 한도·가용잔액 검증 후
   거래 PENDING_REVIEW + 홀드 ACTIVE를 커밋
2. **판정**: 락도 트랜잭션도 없이 `FraudDecisionPort` 호출
3. **확정**: 락 재획득. APPROVE면 재검증 후 홀드 소비·자금 이동·분개, HOLD면 HELD

- 거래 상태에 PENDING_REVIEW, EXPIRED 추가. 전이는 엔티티 메서드로만 하고 허용표 밖은 예외
- 조율 메서드는 `Propagation.NEVER`라 바깥 트랜잭션 안에서 부를 수 없다
- 확정 단계의 락 대기·커넥션 실패는 3회까지 재시도
- 승인 뒤 재검증에 실패하면 FAILED + 홀드 해제, `failureCode`에 이유
- 1일 한도 사용량 = 오늘 **완료된** 출금·이체(완료 시각 기준) + 묶여 있는 홀드.
  보류됐다 다른 날 실행된 거래도 한도에서 빠지지 않는다. 취소·만료되면 사용량이 즉시 돌아온다
- 날짜 경계는 `ibank.business.zone`(기본 Asia/Seoul)으로 계산한다. JVM 기본 타임존은 바꾸지 않았다
- 멱등성 replay에서도 요청자가 거래 계좌의 주인인지 확인한다(`IdempotencyGuard`).
  이전에는 replay가 소유권 검증보다 먼저 실행돼 다른 사용자가 남의 거래 결과를 받을 수 있었다

## 3. FDS 연동과 장애 대체 정책

- `ibank.fds.mode`: `always-approve`(기본, 판정 서비스 없이 개발할 때) / `http`(운영 기본)
- HTTP 어댑터는 `POST /fds/evaluate`를 호출한다. 서킷 브레이커와 별개로 클라이언트 자체에
  연결·응답 타임아웃(기본 각 300ms)이 있다
- 서킷 브레이커(Resilience4j): 실패율이 임계치를 넘으면 호출하지 않고 바로 대체 정책으로 간다
- 대체 정책: 판정을 못 받으면 **만 65세 이상이거나 나이를 모르면 보류**, 그 외는 승인.
  `decisionSource=FALLBACK`, 사유 코드 `FDS_UNAVAILABLE`, 감사 로그 `FDS_FALLBACK`
- 판정 도중 처리가 끊겨 PENDING_REVIEW로 남은 거래는 복구 배치가 1분 뒤부터 다시 판정해 확정한다
- 대체 정책에 나이가 필요해 고객 생년월일을 이 단계에서 추가했다(회원가입 필수, 암호화 저장, 기존 고객은 null)

## 4. 내부 API와 서비스 간 인증

- JWT 서명을 HS256에서 RS256으로 바꿨다. `kid`로 키를 고르고 직전 공개키를 함께 둘 수 있어 무중단 교체가 된다
- `GET /.well-known/jwks.json`으로 공개키를 공개한다
- 시스템 토큰: `POST /internal/auth/token`(클라이언트 ID·시크릿, 기본 5분). 시크릿 대입은 로그인처럼 잠근다
- 토큰 종류(`typ`)로 구분한다. `/internal/**`는 시스템 토큰(ROLE_SYSTEM)만, `/api/**`는 사용자 토큰만
- ROLE_SYSTEM은 사용자 역할 enum에 넣지 않았다. 사용자 계정에는 저장될 수 없는 권한이다
- 보류 거래: `GET /internal/transactions/{id}`, `POST .../execute`, `POST .../cancel`
- 판정용 정보: `GET /internal/customers/{id}/profile`(나이·가입일·계좌, 개인정보 원본 제외),
  `GET /internal/accounts/{no}/transactions?from=&to=`
- 멱등성: `Idempotency-Key` 필수. 대상 행을 먼저 잠근 뒤 키를 확인하고 처리와 키 저장을 한 트랜잭션에서 커밋한다.
  같은 키·같은 요청은 저장된 응답을 그대로 돌려주고 같은 키에 다른 요청이면(요청 지문 불일치) 409
- 실행 재검증 실패는 422 + 코드. 거래는 HELD로 남고 키는 저장하지 않는다
- 모든 실패에 기계가 읽는 `code`가 붙는다. 고객 API 응답 모양은 바뀌지 않았다

## 5. 거래 이벤트 Outbox

- 상태가 바뀌는 같은 트랜잭션에서 이벤트를 기록한다(`OutboxRecorder`, MANDATORY)
- `GET /internal/events?after={id}&limit={n}`: id 오름차순, at-least-once
- 이벤트 id는 기록 시점이 아니라 **공개 시점**에 매긴다. 진행 중인 가장 오래된 트랜잭션
  (`pg_snapshot_xmin`)보다 앞선 이벤트에만 `(xid, seq)` 순으로 번호를 준다.
  늦게 커밋한 트랜잭션의 이벤트가 소비자 커서 뒤로 밀려 사라지는 일이 없고 번호에 구멍이 없다
- **한계**: 쓰기 트랜잭션이 오래 열려 있으면 그동안 이벤트 공개가 멈춘다. 테스트로 재현해 두었다.
  배치의 최장 쓰기 트랜잭션을 측정해 확인했다(계좌 450개 기준 이자 적립 0.54초, 지급 0.08초, 정산 0.34초)
- 보관 기간(기본 30일)이 지난 이벤트와 내부 멱등성 키는 정리 스케줄러가 지운다

## 6. 기존 룰 기반 FDS 제거

- 탐지 규칙, 경보, 관리자 검토 API(`/api/admin/fraud-alerts`)와 `ibank.fraud.*` 설정을 제거했다
- 규칙 3개의 조건·기준값은 B로 넘기기 위해 [migrated-fraud-rules.md](migrated-fraud-rules.md)에 남겼다
- 옛 방식으로 보류된 거래는 금액이 묶인 적이 없어 V28에서 CANCELLED(`LEGACY_HOLD_WITHOUT_FUNDS`)로 정리했다

## 7. 정기예금

- `POST /api/time-deposits`(가입), `POST /api/time-deposits/{no}/terminate`(해지)
- 가입·해지는 모두 분개로 자금을 옮긴다. 약정 금리는 가입 시점 값으로 고정
- 해지 이자: 만기 전은 중도해지 금리, 만기 이후는 약정 금리, 가입일부터 일할 계산(HALF_EVEN)
- 정기예금 계좌는 직접 입출금·이체가 안 된다. 본인 계좌 간 이동이라 판정과 1일 한도에서 제외
  (거래 유형 `TD_OPEN`, `TD_TERMINATION`). 보류 중인 돈으로는 가입할 수 없다
- `TIME_DEPOSIT_TERMINATED` 이벤트로 B가 "해지 직후 전액 이체" 흐름을 알아볼 수 있다

## 8. 창구 출금

- 역할 ROLE_TELLER, `POST /api/teller/withdrawals`
- 고객 출금과 같은 2단계 처리를 타고 채널만 TELLER로 남는다. 보류되면 응답의 `reasonCodes`로 창구에서 확인할 수 있다
- 판정 요청과 이벤트의 고객은 계좌 주인이고 처리한 직원은 감사 로그에 남는다
- 채널이 요청 지문에 들어가 있어 앱에서 쓴 멱등성 키를 창구에서 다시 쓰면 충돌로 거부된다
- 직원 계정은 설정(`ibank.bootstrap.staff.<loginId>.*`)으로만 만든다

## 9. 홀드 만료

- 최대 보류 시간(기본 24시간)이 지나면 홀드를 EXPIRED로 푼다
  - HELD → EXPIRED, `TRANSACTION_EXPIRED` 이벤트
  - PENDING_REVIEW → FAILED(`HOLD_EXPIRED`). 원래는 복구 배치가 먼저 처리한다
- 돈은 움직이지 않고 가용잔액과 1일 한도 사용량만 돌아온다
- 락 순서가 실행·취소와 같아 만료와 실행이 동시에 와도 한쪽만 적용된다

## 10. 합성 데이터 대량 적재

- `bulk-load` 프로파일에서 Spring Batch로 고객 → 계좌 → 거래를 적재한다(청크 500행)
- 판정·한도·홀드·이벤트는 건너뛰고 원장 분개는 평소대로 남긴다. 과거 시각을 그대로 넣는다
- 문제 행은 그 행만 건너뛰고 `bulk_load_errors`에 줄 번호와 이유를 남긴다
- 같은 디렉터리로 다시 실행하면 실패한 곳부터 이어 가고 이미 들어간 행은 건너뛴다
- 끝나면 정산 배치를 돌려 결과를 출력한다

이 작업 중 Spring Boot 4의 기본 배치 스타터가 잡 메타데이터를 메모리에만 두고 있었다는 것을 발견해
`spring-boot-starter-batch-jdbc`로 바꿨다(V7의 `BATCH_*` 테이블이 이제 실제로 쓰인다).
그러자 이자 지급 스텝의 바깥 트랜잭션이 배치 내내 열려 Outbox를 막아, 이 스텝은 DB 트랜잭션으로 감싸지 않게 고쳤다.

## 11. 정산 배치 검증 확장

계좌 단위 대사 외에 다음 6가지를 실행마다 검사하고 `reconciliation_check_results`에 남긴다.
하나라도 위반이면 잡은 FAILED.

| 항목 | 내용 |
|---|---|
| UNBALANCED_JOURNALS | 합계가 0이 아닌 분개 |
| TRIAL_BALANCE | 전체 시산표 불일치 |
| HOLD_SUM_EXCEEDS_BALANCE | ACTIVE 홀드 합계 > 원장잔액 |
| ACTIVE_HOLD_WITHOUT_OPEN_TRANSACTION | 대기·보류가 아닌 거래에 걸린 ACTIVE 홀드 |
| OPEN_TRANSACTION_WITHOUT_ACTIVE_HOLD | ACTIVE 홀드가 없는 대기·보류 거래 |
| UNEXECUTED_TRANSACTION_WITH_LEDGER | 취소·만료·실패 거래에 달린 분개 |

## 12. 계좌 지급정지

- `POST /internal/accounts/{no}/freeze`, `/unfreeze` (사유 코드 필수, 멱등)
- FROZEN은 돈이 나가는 것만 막는다. 출금·이체 출금·정기예금 가입은 `ACCOUNT_FROZEN`,
  입금·이체 입금·이자 적립은 그대로
- 정지된 계좌의 HELD 거래 실행은 422 `ACCOUNT_FROZEN`(HELD 유지), 취소는 가능
- 판정 대기 중에 정지되면 승인이 와도 FAILED(`failureCode=ACCOUNT_FROZEN`)
- 상태가 실제로 바뀔 때만 `ACCOUNT_FROZEN` / `ACCOUNT_UNFROZEN` 이벤트

## 지시서와 다르게 한 부분

| 항목 | 지시서 | 실제 | 이유 |
|---|---|---|---|
| ROLE_SYSTEM | 사용자 역할에 추가 | 시스템 토큰에만 붙는 권한 | 사용자 계정에 저장될 수 없게 하려고 |
| 생년월일 | 1-7 | 4b에서 먼저 추가 | 대체 정책이 나이를 써야 해서 |
| 만료된 PENDING_REVIEW | EXPIRED | FAILED(`HOLD_EXPIRED`) | PENDING_REVIEW → EXPIRED는 허용 전이가 아님 |
| 분개 달린 미실행 거래 검사 | CANCELLED·EXPIRED | FAILED 포함 | 셋 다 자금이 움직이지 않은 종료 상태 |
| 대량 적재 이벤트 | — | 이벤트를 남기지 않음 | 과거 데이터라 B가 실시간으로 받을 이유가 없음 |

## 동작이 바뀌어 고친 기존 테스트

| 테스트 | 바뀐 내용 | 이유 |
|---|---|---|
| 테스트 22개의 정리 코드 | `deleteAll()` 체인 → `DatabaseCleaner`(TRUNCATE CASCADE) | 거래를 참조하는 테이블이 늘 때마다 삭제 순서를 고치지 않으려고. 검증 내용은 그대로 |
| `AccountControllerTest` | 생성자 호출 수정 | 계좌 응답에 `availableBalance`, `heldAmount`, `accountType` 추가 |
| `TransactionControllerTest` | 생성자 호출 수정 | 이체 응답에 `reasonCodes` 등 추가 |
| `FraudReviewTest` "승인 시점 잔액 부족" | 묶인 금액은 소진할 수 없어 승인이 항상 성공한다는 내용으로 교체(이후 FDS 제거 때 삭제) | 홀드 도입으로 그 상황 자체가 불가능해짐 |
| `TransferPoolSaturationInvariantTest` | 미확정 거래와 홀드의 짝, 가용잔액 비음수 단언 추가 | 새 불변식 검증 |
| 회원가입을 쓰는 테스트(생성자 8곳, 요청 본문 4곳), k6 스크립트 | 생년월일 추가 | 회원가입에 생년월일이 필수가 됨 |
| k6 스크립트 계좌 개설 | 멱등성 키 추가 | 키가 없으면 현재 API가 400 |
| `fraud/*`, `FraudAlertControllerTest` | 삭제 | 룰 기반 FDS 제거. 같은 동작은 `TwoPhaseTransactionTest`, `InternalTransactionApiTest`, `FdsFallbackTest`가 검증 |
| `FdsFallbackTest`, `HttpFraudDecisionAdapterTest` | 정상 경로 타임아웃 2초, 타임아웃 시나리오 서버 지연 8초 | 느린 머신에서 정상 응답이 500ms를 넘겨 실패한 적이 있음. 검증 내용은 그대로 |

## 테스트 현황

전체 305개 통과. 지시서의 완료 시나리오 8개는 모두 통합 테스트로 있다.

| # | 시나리오 | 테스트 |
|---|---|---|
| 1 | 승인 | `TwoPhaseTransactionTest` |
| 2 | 보류 → 실행 | `TwoPhaseTransactionTest`, `InternalTransactionApiTest` |
| 3 | 보류 → 취소 | `TwoPhaseTransactionTest`, `InternalTransactionApiTest` |
| 4 | FDS 장애 시 대체 정책(65세 기준) | `FdsFallbackTest` |
| 5 | 묶인 금액 이중 사용 실패 | `TwoPhaseTransactionTest` |
| 6 | 실행·취소 동시 요청 중 하나만 성공 | `TwoPhaseTransactionTest`, `InternalTransactionApiTest` |
| 7 | 홀드 만료 | `HoldExpiryTest` |
| 8 | 정기예금 중도해지 → 입금 → 전액 이체 이벤트 순서 | `TimeDepositTest` |

## 비기능 검증

- **홀드가 섞인 동시 이체 1,000건 정합성** (`HeldTransferReconciliationTest`): 계좌 10개 사이 이체 1,000건을 16개 스레드로 동시에 보내고(약 3분의 1 보류),
  보류 거래의 실행·취소·실행과 취소 경합을 동시에 처리한 뒤 정산 배치를 돌린다. 계좌별 불일치 0건, 불변식 6항목 위반 0건,
  고객 잔액 총합 보존, 묶인 금액 = 남은 보류 거래 금액을 확인한다
- **k6 측정** ([load-test/results.md](../load-test/results.md)): 같은 계좌 한 곳에 50 VU가 몰리는 최악 경합에서
  기준 약 98 TPS → 2단계 약 43~47 TPS. FDS 지연 100ms와 FDS 무응답은 처리량을 더 떨어뜨리지 않았다(판정을 락 밖에서 기다리므로).
  FDS 무응답 조건에서는 모든 이체가 대체 정책으로 승인됐다
- **docker-compose.yml**: FDS 모드·주소·토큰, 스케줄러 활성화, 내부 API 클라이언트 시크릿, 창구 직원 계정, JWT 키를 환경변수로 받는다.
  기본값은 FDS 서비스 없이 ibank만 뜨는 `always-approve`

## 남은 작업

- 2순위 확장(대출, 용도 제한 계좌)은 시작하지 않았다
- [contracts/README.md](contracts/README.md)의 협의 항목 6개를 B 담당자와 확정해야 한다