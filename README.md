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
출발지 IP 단위 토큰 버킷으로 모든 `/api/**` 요청의 호출 빈도를 제한하며, 초과 시 `429`와 `Retry-After`를 반환합니다.
인증보다 앞단(필터)에서 동작해 과도한 요청을 DB 작업 전에 차단합니다.
`ibank.rate-limit.*`로 용량·처리율을 조정하고, 운영(prod)에서 활성화됩니다(개발/부하측정 영향 없음).

부하 테스트의 경우 k6를 이용하였고 동일한 계좌에 2639건의 이체요청이 몰렸을때 실패가 0%임을 확인하였습니다.
