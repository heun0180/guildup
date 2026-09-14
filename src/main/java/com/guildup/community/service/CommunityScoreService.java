package com.guildup.community.service;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberScore;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.domain.CommunityScoreHistory;
import com.guildup.community.domain.CommunityScoreReferenceType;
import com.guildup.community.domain.CommunityScoreType;
import com.guildup.community.repository.CommunityMemberScoreRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityScoreHistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** 모든 커뮤니티 활동이 공유하는 점수 원장과 현재 총점 갱신 진입점이다. */
@Service
public class CommunityScoreService {
    private static final ZoneId SCORE_DAY_ZONE = ZoneId.of("Asia/Seoul");
    private final CommunityScoreHistoryRepository histories;
    private final CommunityMemberScoreRepository scores;
    private final CommunityMemberRepository members;

    public CommunityScoreService(CommunityScoreHistoryRepository histories,
                                 CommunityMemberScoreRepository scores,
                                 CommunityMemberRepository members) {
        this.histories = histories;
        this.scores = scores;
        this.members = members;
    }

    @Transactional
    public int addScore(CommunityMember member, CommunityScoreType type, int amount,
                        String description, CommunityScoreReferenceType referenceType,
                        Long referenceId, Instant occurredAt) {
        if (amount <= 0) throw new IllegalArgumentException("지급 점수는 1 이상이어야 합니다.");
        if (referenceId == null) throw new IllegalArgumentException("점수 참조 ID가 필요합니다.");

        CommunityMember lockedMember = members.findForUpdate(
                member.getCommunity().getId(), member.getId(), CommunityMemberStatus.ACTIVE
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "활성 클랜원에게만 점수를 지급할 수 있습니다."
        ));

        histories.save(new CommunityScoreHistory(
                lockedMember, amount, type, description, referenceType, referenceId, occurredAt
        ));
        CommunityMemberScore score = scores.findByCommunityMemberId(lockedMember.getId())
                .orElseGet(() -> new CommunityMemberScore(lockedMember, occurredAt));
        score.add(amount, occurredAt);
        scores.save(score);
        return score.getTotalScore();
    }

    /** 킬내기 점수는 멤버 잠금 안에서 동일 대회 중복과 서울 날짜별 한도를 함께 검사한다. */
    @Transactional
    public boolean addKillCompetitionWinIfEligible(CommunityMember member, Long competitionId, Instant occurredAt) {
        CommunityMember lockedMember = members.findForUpdate(
                member.getCommunity().getId(), member.getId(), CommunityMemberStatus.ACTIVE
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "활성 클랜원에게만 점수를 지급할 수 있습니다."
        ));
        if (histories.existsByCommunityMemberIdAndScoreTypeAndReferenceId(
                lockedMember.getId(), CommunityScoreType.KILL_COMPETITION_WIN, competitionId
        )) return false;
        var date = occurredAt.atZone(SCORE_DAY_ZONE).toLocalDate();
        Instant dayStart = date.atStartOfDay(SCORE_DAY_ZONE).toInstant();
        Instant nextDayStart = date.plusDays(1).atStartOfDay(SCORE_DAY_ZONE).toInstant();
        if (histories.existsByCommunityMemberIdAndScoreTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                lockedMember.getId(), CommunityScoreType.KILL_COMPETITION_WIN, dayStart, nextDayStart
        )) return false;
        histories.save(new CommunityScoreHistory(
                lockedMember, 3, CommunityScoreType.KILL_COMPETITION_WIN, "킬내기 우승",
                CommunityScoreReferenceType.KILL_COMPETITION, competitionId, occurredAt
        ));
        CommunityMemberScore score = scores.findByCommunityMemberId(lockedMember.getId())
                .orElseGet(() -> new CommunityMemberScore(lockedMember, occurredAt));
        score.add(3, occurredAt);
        scores.save(score);
        return true;
    }

    @Transactional(readOnly = true)
    public int getTotalScore(CommunityMember member) {
        return scores.findByCommunityMemberId(member.getId())
                .map(CommunityMemberScore::getTotalScore)
                .orElse(0);
    }

    @Transactional(readOnly = true)
    public List<CommunityScoreHistory> getScoreHistory(CommunityMember member) {
        return histories.findByCommunityMemberIdOrderByCreatedAtDescIdDesc(member.getId());
    }
}
