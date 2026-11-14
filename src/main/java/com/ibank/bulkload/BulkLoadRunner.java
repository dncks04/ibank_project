package com.ibank.bulkload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * {@code bulk-load} 프로파일로 띄우면 기동 직후 적재를 시작한다.
 *
 * <pre>
 * java -jar ibank.jar --spring.profiles.active=bulk-load --ibank.bulk-load.input-dir=/data/synthetic
 * </pre>
 *
 * 끝나면 요약을 출력하고 프로세스를 종료한다(종료 코드: 정합성 이상 없으면 0, 아니면 1).
 * 종료하지 않고 띄워 두려면 {@code ibank.bulk-load.exit-on-finish=false}.
 */
@Slf4j
@Component
@Profile("bulk-load")
@RequiredArgsConstructor
public class BulkLoadRunner implements ApplicationRunner {

    private final BulkLoadProperties properties;
    private final BulkLoadService service;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.autoRun()) {
            return;
        }
        if (properties.inputDir() == null || properties.inputDir().isBlank()) {
            throw new IllegalStateException("bulk-load 프로파일에는 ibank.bulk-load.input-dir이 필요합니다.");
        }
        int code;
        try {
            code = service.load(Path.of(properties.inputDir())).consistent() ? 0 : 1;
        } catch (BulkLoadService.AlreadyLoadedException e) {
            log.warn(e.getMessage()); // 할 일이 없는 것은 실패가 아니다
            code = 0;
        }
        if (properties.exitOnFinish()) {
            int exitCode = code;
            System.exit(SpringApplication.exit(context, () -> exitCode));
        }
    }
}
