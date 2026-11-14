# 합성 데이터 대량 적재 입력 형식

합성 고객·계좌·거래 데이터를 ibank(계정계)에 한꺼번에 넣을 때의 입력 형식이다.
데이터를 만드는 쪽(C)과 적재하는 쪽(ibank)이 이 문서를 기준으로 맞춘다.

## 한눈에

| 항목 | 내용 |
|---|---|
| 형식 | JSON Lines (`.jsonl`) — 한 줄에 JSON 객체 하나, UTF-8 |
| 파일 | 한 디렉터리에 `customers.jsonl`, `accounts.jsonl`, `transactions.jsonl` 세 개 (모두 있어야 한다. 내용이 없으면 빈 파일) |
| 적재 순서 | 고객 → 계좌 → 거래. 파일 안에서는 위에서 아래로 |
| 금액 | 십진 문자열, 소수 둘째 자리까지 (`"1500000"`, `"1500000.50"`). 숫자로 줘도 받지만 문자열을 권장 |
| 시각 | 오프셋을 포함한 ISO-8601 (`"2026-03-01T09:30:00+09:00"`). 미래 시각은 거부 |
| 실행 | `java -jar ibank.jar --spring.profiles.active=bulk-load --ibank.bulk-load.input-dir=<디렉터리>` |

## 파일별 필드

### customers.jsonl

```json
{"loginId":"elder0001","name":"김영희","email":"elder0001@synthetic.test","birthDate":"1951-04-12","joinedAt":"2019-06-03T10:15:00+09:00"}
```

| 필드 | 필수 | 규칙 |
|---|---|---|
| `loginId` | O | 4~20자. 고객의 식별자이며 계좌 파일이 이 값으로 고객을 가리킨다. 전체에서 유일 |
| `name` | O | 30자 이하 |
| `email` | O | 전체에서 유일(대소문자·앞뒤 공백 무시). 암호화되어 저장된다 |
| `birthDate` | O | `yyyy-MM-dd`. 고령 고객 판정의 기준. 암호화되어 저장된다 |
| `joinedAt` | O | 가입 시각 |
| `password` | - | 이 고객으로 로그인해야 하면 지정. 없으면 적재 설정의 기본 비밀번호(`ibank.bulk-load.default-password`)가 쓰이고, 그것도 없으면 로그인할 수 없는 계정이 된다 |

### accounts.jsonl

```json
{"accountNumber":"20190603000001","customerLoginId":"elder0001","initialBalance":"0","openedAt":"2019-06-03T10:20:00+09:00"}
```

| 필드 | 필수 | 규칙 |
|---|---|---|
| `accountNumber` | O | 숫자 10~20자리. 전체에서 유일 |
| `customerLoginId` | O | `customers.jsonl`에 있는(또는 이미 적재된) 고객의 `loginId` |
| `initialBalance` | O | 0 이상. 0보다 크면 개설 시점의 입금으로 원장에 기록된다 |
| `openedAt` | O | 개설 시각 |

입출금 계좌만 적재한다. 정기예금은 적재 후 API(`POST /api/time-deposits`)로 가입시킨다.

### transactions.jsonl

```json
{"txRef":"T000000001","type":"DEPOSIT","toAccountNumber":"20190603000001","amount":"3000000","occurredAt":"2019-06-25T09:00:00+09:00","channel":"MOBILE","description":"연금"}
{"txRef":"T000000002","type":"TRANSFER","fromAccountNumber":"20190603000001","toAccountNumber":"20200110000007","amount":"500000","occurredAt":"2019-07-01T14:30:00+09:00"}
{"txRef":"T000000003","type":"WITHDRAWAL","fromAccountNumber":"20190603000001","amount":"200000","occurredAt":"2019-07-03T11:00:00+09:00","channel":"TELLER"}
```

| 필드 | 필수 | 규칙 |
|---|---|---|
| `txRef` | O | 거래의 고유 참조값, 31자 이하. 전체에서 유일. 재적재 때 "이미 넣은 거래"를 알아보는 키다 |
| `type` | O | `DEPOSIT` / `WITHDRAWAL` / `TRANSFER` |
| `fromAccountNumber` | 유형별 | `WITHDRAWAL`, `TRANSFER`에 필수. `DEPOSIT`에는 넣지 않는다 |
| `toAccountNumber` | 유형별 | `DEPOSIT`, `TRANSFER`에 필수. `WITHDRAWAL`에는 넣지 않는다. `TRANSFER`는 두 계좌가 달라야 한다 |
| `amount` | O | 0보다 커야 한다 |
| `occurredAt` | O | 거래 시각. 거래의 요청 시각·완료 시각과 원장 분개 시각이 모두 이 값이 된다 |
| `channel` | - | `MOBILE`(기본) / `TELLER` / `ATM` |
| `description` | - | 200자 이하 |

**거래는 반드시 시간순으로 정렬해 둔다.** 적재는 파일 순서대로 잔액을 움직이므로, 입금보다 출금이 먼저 나오면
그 출금은 잔액 부족으로 건너뛴다. 모든 거래는 완료(`COMPLETED`) 상태로 적재된다.

## 적재가 평소 거래와 다른 점

과거 데이터라 아래 항목은 **건너뛴다.**

- FDS 판정과 보류 (모든 거래가 바로 완료 상태로 들어간다)
- 단건·1일 거래 한도
- 호출 제한 (HTTP를 거치지 않는다)
- Outbox 이벤트 (FDS 서비스에는 이 거래들의 이벤트가 가지 않는다. FDS 쪽 과거 데이터는 같은 합성 데이터를 직접 받아 쓴다)

아래는 **평소와 똑같이 지킨다.**

- 원장: 계좌 개설과 모든 거래가 합이 0인 분개로 기록된다
- 잔액은 음수가 될 수 없다 (잔액이 모자란 출금·이체 행은 건너뛴다)
- 암호화: 이메일·생년월일은 암호화되어 저장된다

적재가 끝나면 정산 배치가 자동으로 돌고 불일치가 하나라도 있으면 결과 요약에 표시된다.

## 실패한 행

한 행의 문제는 그 행만 건너뛰고 나머지는 계속 적재한다. 건너뛴 행은 `bulk_load_errors` 테이블에 남는다.

```sql
SELECT step, line_number, reason, raw_line
FROM bulk_load_errors
WHERE job_execution_id = <요약에 출력된 값>
ORDER BY step, line_number;
```

| 흔한 이유 | 원인 |
|---|---|
| `JSON 형식 오류` | 한 줄이 완전한 JSON 객체가 아님 (줄바꿈이 값 안에 들어갔는지 확인) |
| `필수 값이 없습니다: <필드>` | 필드 누락 또는 빈 문자열 |
| `고객이 없습니다` / `계좌가 없습니다` | 참조하는 고객·계좌가 앞 단계에서 적재되지 않음 (앞 단계에서 그 행을 건너뛰었는지 확인) |
| `잔액 부족 ...` | 거래가 시간순이 아니거나, 합성 데이터의 잔액 흐름이 음수가 됨 |
| `이미 쓰이는 이메일입니다` | 서로 다른 고객이 같은 이메일 |
| `...가 미래 시각입니다` | 적재 시점보다 뒤의 시각 |

## 다시 적재하기

- **같은 디렉터리로 다시 실행**: 중간에 실패했다면 마지막으로 커밋된 지점부터 이어 간다. 끝까지 성공한 디렉터리는 다시 돌지 않는다.
- **고친 데이터를 새 디렉터리에 두고 실행**: 이미 적재된 고객(`loginId`)·계좌(`accountNumber`)·거래(`txRef`)는 건너뛰고(요약의 "이미 있음"),
  새로 추가되거나 고쳐진 행만 적재된다. 같은 데이터가 두 번 들어가지 않는다.
- 이미 적재된 행의 내용을 바꿔 다시 넣는 것은 지원하지 않는다(키가 같으면 건너뛴다). 처음부터 다시 하려면 DB를 비우고 적재한다.

## 결과 요약 예시

```
========== 합성 데이터 적재 결과 ==========
  customers     적재 1,000건 / 이미 있음 0건 / 건너뜀 0건
  accounts      적재 1,420건 / 이미 있음 0건 / 건너뜀 2건
  transactions  적재 98,731건 / 이미 있음 0건 / 건너뜀 14건
  적재 잡: COMPLETED (jobExecutionId=12)
  정산 배치: COMPLETED — 불일치 계좌 0, 불균형 분개 0, 시산표 0.00
  건너뛴 행 16건의 이유: SELECT step, line_number, reason FROM bulk_load_errors WHERE job_execution_id = 12 ...
  결과: 정합성 이상 없음
============================================
```

"건너뜀"이 있어도 정합성과는 별개다. 건너뛴 행은 아예 들어가지 않았고, 들어간 데이터끼리는 원장이 맞는다.

## 설정

| 설정 | 기본값 | 설명 |
|---|---|---|
| `ibank.bulk-load.input-dir` | (필수) | 세 파일이 있는 디렉터리 |
| `ibank.bulk-load.chunk-size` | 500 | 한 번에 커밋할 행 수 |
| `ibank.bulk-load.default-password` | (없음) | `password`가 없는 고객의 비밀번호 |
| `ibank.bulk-load.exit-on-finish` | true | 끝나면 프로세스 종료 (종료 코드: 정합성 이상 없으면 0, 아니면 1) |
