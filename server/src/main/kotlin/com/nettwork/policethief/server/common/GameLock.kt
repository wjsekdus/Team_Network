package com.nettwork.policethief.server.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 매칭·경기 상태를 바꾸는 모든 처리를 한 줄로 세우는 서버 전역 잠금.
 *
 * 설계 의도(동시성 전략)
 * - 서버는 개발자 PC 의 단일 프로세스로 실행된다(docs/architecture/ARCHITECTURE.md). 이 규모에서는
 *   "모든 상태 변경을 한 번에 하나씩" 처리하는 것이 가장 단순하고 확실하다: 같은 사용자를 두 경기에 넣거나,
 *   체포와 구출이 엇갈려 상태가 꼬이는 일이 구조적으로 생기지 않는다.
 * - 잠금 안에서 DB 트랜잭션을 열고, DB 에도 고유 제약(active_participations 기본키, game_results 기본키,
 *   경찰 1명 = 호송 대상 1칸)을 두어 잠금이 없는 경로가 생겨도 데이터가 깨지지 않게 했다.
 * - 서버를 여러 대로 늘리면 이 잠금만으로는 부족하다. 그때는 PostgreSQL advisory lock 또는 행 잠금으로 바꿔야 한다.
 * - WebSocket 전송처럼 느린 작업은 잠금을 푼 뒤에 한다(Outbox).
 */
class GameLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
