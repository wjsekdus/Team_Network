package com.nettwork.policethief.server.matchmaking

import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.rules.GameRules
import java.time.Instant
import java.util.UUID

/** 매칭 대기자 한 명(판정에 필요한 정보만). */
data class Waiter(val userId: UUID, val point: GeoPoint, val joinedAt: Instant)

/**
 * 4인 매칭 알고리즘(순수 함수, DB 를 모른다).
 *
 * 조건(지시서 7.2)
 * 1. 4명이 서로 500m 이내: 누구의 "현재 위치 기준 반경 500m" 로 보더라도 나머지 3명이 들어온다.
 * 2. 4명의 중심점에서 반경 500m 안에 4명 모두 포함(초기 게임 구역을 만들 수 있음).
 *    (1 을 만족하면 2 도 수학적으로 만족하지만, 구역 생성 실패를 막기 위해 따로 다시 확인한다.)
 * 거리는 위도·경도 차이가 아니라 Haversine 공식의 실제 지표면 거리(미터)다.
 *
 * 공정성: 가장 오래 기다린 사람부터 기준(anchor)으로 삼고, 그 주변 후보도 오래 기다린 순서로 조합을 시도한다.
 * 조건을 만족하지 못하면 다른 조합 → 다른 기준 순으로 탐색한다.
 */
object MatchFinder {
    /** 기준 1명당 살펴볼 최대 후보 수. C(24,3)=2024 조합이면 충분히 빠르다. */
    const val MAX_CANDIDATES_PER_ANCHOR = 24

    /** 겹치지 않는 그룹을 가능한 만큼 만든다. */
    fun findGroups(
        waiters: List<Waiter>,
        size: Int = GameRules.PLAYERS_PER_GAME,
        radiusMeters: Double = GameRules.MATCH_RADIUS_METERS,
    ): List<List<Waiter>> {
        val remaining = waiters.distinctBy { it.userId }.sortedWith(compareBy<Waiter>({ it.joinedAt }, { it.userId })).toMutableList()
        val groups = mutableListOf<List<Waiter>>()
        while (true) {
            val group = findGroup(remaining, size, radiusMeters) ?: break
            groups += group
            remaining.removeAll { member -> group.any { it.userId == member.userId } }
        }
        return groups
    }

    fun findGroup(
        waiters: List<Waiter>,
        size: Int = GameRules.PLAYERS_PER_GAME,
        radiusMeters: Double = GameRules.MATCH_RADIUS_METERS,
    ): List<Waiter>? {
        if (waiters.size < size) return null
        val ordered = waiters.sortedWith(compareBy<Waiter>({ it.joinedAt }, { it.userId }))
        for (anchor in ordered) {
            val candidates = ordered
                .filter { it.userId != anchor.userId && distance(anchor, it) <= radiusMeters }
                .take(MAX_CANDIDATES_PER_ANCHOR)
            if (candidates.size < size - 1) continue
            search(listOf(anchor), candidates, 0, size, radiusMeters)?.let { return it }
        }
        return null
    }

    /** 그룹이 매칭 조건을 모두 만족하는지(테스트·재검증용). */
    fun isValidGroup(group: List<Waiter>, radiusMeters: Double = GameRules.MATCH_RADIUS_METERS): Boolean {
        val pairwise = group.indices.all { i -> (i + 1 until group.size).all { j -> distance(group[i], group[j]) <= radiusMeters } }
        if (!pairwise) return false
        val center = GeoMath.centroid(group.map { it.point })
        return group.all { GeoMath.distanceMeters(it.point, center) <= GameRules.INITIAL_ZONE_RADIUS_METERS }
    }

    private fun search(chosen: List<Waiter>, candidates: List<Waiter>, start: Int, size: Int, radius: Double): List<Waiter>? {
        if (chosen.size == size) return chosen.takeIf { isValidGroup(it, radius) }
        for (i in start until candidates.size) {
            val next = candidates[i]
            if (chosen.all { distance(it, next) <= radius }) {
                search(chosen + next, candidates, i + 1, size, radius)?.let { return it }
            }
        }
        return null
    }

    private fun distance(a: Waiter, b: Waiter) = GeoMath.distanceMeters(a.point, b.point)
}
