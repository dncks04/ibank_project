## 개인 프로젝트 - ibank (아주대학교 신우찬)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

계좌 개설부터 입출금, 이체, 거래내역 조회 등의 기술을 구현
동시성 제어, 멱등성, 정합성 검증에 중점을 두었으며 그 외에도 금융권에서 중요시하는 개념들을 실제로 구현하고 싶은 생각에서 시작되었습니다.
**여러 요청이 같은 계좌를 동시에 건드려도 잔액이 깨지지 않는가** 라는 질문이 프로젝트의 핵심이며, 실무에 가깝게 구현하려 노력했습니다.

# 기술 스택

| 구분 |  |
|------|------|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 4.0.6 (Web MVC, Data JPA, Security, Validation, Retry, Actuator, Batch) |
| DB | PostgreSQL 16 |
| Auth | JWT(jjwt) + 회전형 Refresh Token |
| Build | Gradle 8.14 (Kotlin DSL) |
| CI | GitHub Actions |

## API
| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:---:|
| POST | `/api/auth/register` | 회원가입 | — |
| POST | `/api/auth/login` | 로그인 (access + refresh 발급) | — |
| POST | `/api/auth/refresh` | 토큰 회전(재발급) | — |
| POST | `/api/auth/logout` | refresh 토큰 폐기 | — |
| POST | `/api/auth/sessions/invalidate` | 전체 세션 즉시 무효화(계정 도용 신고) | O |
| POST | `/api/accounts` | 계좌 개설 | O |
| GET | `/api/accounts/me` | 내 계좌 목록 | O |
| GET | `/api/accounts/{no}` | 계좌 단건 조회 | O |
| DELETE | `/api/accounts/{no}` | 계좌 해지 (잔액 0 필요) | O |
| POST | `/api/transactions/deposit` | 입금 | O |
| POST | `/api/transactions/withdraw` | 출금 | O |
| POST | `/api/transactions/transfer` | 이체 | O |
| GET | `/api/accounts/{no}/transactions` | 거래내역(페이징·기간필터) | O |

## 호출 제한 (Rate limit)

로그인은 실패 누적 기반 brute-force 방어가 별도로 있고, 그 외 일반 API는 보호가 없던 문제를 보완했습니다.

## 동시성 제어

이체는 비관적 락(`SELECT ... FOR UPDATE`)으로 처리하며, 두 계좌를 항상 계좌번호 오름차순으로 잠가 데드락을 차단합니다. 양방향 이체 100건을 동시에 던지는 테스트로 확인했습니다. 락을 쥔 트랜잭션이 멈추면 뒤 이체가 전부 같이 멈추므로 DB에 `lock_timeout` 3초를 걸어 빨리 실패하고 재시도하는 쪽을 택했습니다.

## 멱등성

거래 요청마다 멱등성 키를 받고, 같은 키가 다시 오면 처리 없이 저장된 결과를 돌려줍니다. 검증은 락 전후 두 번 합니다. 같은 키를 든 요청 둘이 동시에 들어오면 늦은 쪽이 락 대기 중 먼저 온 쪽의 커밋을 두 번째 검증에서 보게 됩니다. 같은 키를 다른 금액에 재사용하는 경우는 요청 지문(타입·계좌·금액 해시)을 함께 저장해 두었다가 409로 거부합니다.

## 원장과 정산

모든 거래는 복식부기 원장에 DEBIT/CREDIT 두 줄로 남고, `잔액 == CREDIT 합 − DEBIT 합` 불변식을 Spring Batch 정산 잡이 주기적으로 검사합니다. 잔액과 원장 합계는 단일 쿼리로 같은 스냅샷에서 읽습니다. 나눠 읽으면 그 사이 커밋된 이체가 read skew 오탐을 만들기 때문입니다. 잔액 음수 금지, 0원 이하 거래 금지, 동일 계좌 이체 금지는 DB CHECK 제약으로도 걸어 두었습니다.


## 테스트

동시성은 Testcontainers로 실제 PostgreSQL을 띄워서 검증합니다.

- 동시 이체 후 총 잔액 보존
- 양방향 동시 이체에서 데드락 부재
- 같은 멱등성 키 동시 요청 시 정확히 1건만 처리
- 잔액 5,000원에 1,000원 출금 10건이 몰리면 정확히 5건 성공

이 네 가지가 깨지면 위 설계가 거짓말이 되므로 CI(GitHub Actions)에서 매번 돌립니다.
출발지 IP 단위 토큰 버킷으로 모든 api 요청의 호출 빈도를 제한하며, 초과 시 `429`와 `Retry-After`를 반환합니다.
인증보다 앞단(필터)에서 동작해 과도한 요청을 DB 작업 전에 차단합니다.
`ibank.rate-limit.*`로 용량·처리율을 조정하고, 운영(prod)에서 활성화됩니다(개발/부하측정 영향 없음).

부하 테스트의 경우 k6를 이용하였고 동일한 계좌에 2639건의 이체요청이 몰렸을때 실패가 0%임을 확인하였습니다.
