package com.ibank.bulkload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 합성 데이터 대량 적재 설정. {@code bulk-load} 프로파일에서만 쓰인다.
 *
 * @param inputDir        customers.jsonl, accounts.jsonl, transactions.jsonl이 있는 디렉터리
 * @param chunkSize       한 트랜잭션으로 커밋할 행 수
 * @param defaultPassword 입력에 비밀번호가 없는 고객에게 줄 비밀번호. 시연에서 합성 고객으로 로그인할 때 쓴다.
 *                        비어 있으면 그런 고객은 로그인할 수 없는 계정으로 만들어진다.
 * @param autoRun         기동 시 자동으로 적재를 시작할지
 * @param exitOnFinish    적재가 끝나면 프로세스를 종료할지(적재 전용으로 띄운 경우)
 */
@ConfigurationProperties(prefix = "ibank.bulk-load")
public record BulkLoadProperties(
        String inputDir,
        @DefaultValue("500") int chunkSize,
        String defaultPassword,
        @DefaultValue("true") boolean autoRun,
        @DefaultValue("true") boolean exitOnFinish
) {}
