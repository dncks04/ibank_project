package com.ibank.bulkload;

/**
 * 입력의 한 행을 적재할 수 없다. 그 행만 건너뛰고 {@code bulk_load_errors}에 이유를 남긴다.
 *
 * <p>이 예외는 반드시 <b>DB를 바꾸기 전에</b> 던진다. 행을 적용하는 코드는 검증을 모두 끝낸 뒤에만 변경을 시작하므로,
 * 건너뛴 행이 반쯤 적용된 채 청크와 함께 커밋되는 일이 없다.
 */
public class BulkLoadRowException extends RuntimeException {

    public BulkLoadRowException(String reason) {
        super(reason);
    }
}
