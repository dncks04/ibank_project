-- ShedLock 분산 락 저장소. 다중 인스턴스 배포 시 @Scheduled 배치가 한 노드에서만 실행되도록
-- name(=락 이름)을 PK로 점유한다. lock_until 까지 다른 노드는 같은 이름의 잡을 실행하지 못한다.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
