package com.guildup.bingo.service;

import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.*;
import com.guildup.bingo.mission.BingoItemCatalog;
import com.guildup.bingo.mission.BingoMapCatalog;
import com.guildup.bingo.repository.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.community.service.CommunityGameAccessService;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BingoEventService {
    private final BingoEventRepository events; private final BingoParticipantRepository participants;
    private final BingoProgressRepository progress; private final BingoLineCompletionRepository lines;
    private final CommunityRepository communities; private final CommunityAccessService access;
    private final CommunityGameAccessService gameAccess;
    private final BingoParticipantEnrollmentService enrollment; private final Clock clock;

    public BingoEventService(BingoEventRepository events, BingoParticipantRepository participants,
                             BingoProgressRepository progress, BingoLineCompletionRepository lines,
                             CommunityRepository communities, CommunityAccessService access,
                             CommunityGameAccessService gameAccess,
                             BingoParticipantEnrollmentService enrollment, Clock clock) {
        this.events = events; this.participants = participants; this.progress = progress; this.lines = lines;
        this.communities = communities; this.access = access; this.gameAccess = gameAccess; this.enrollment = enrollment; this.clock = clock;
    }

    @Transactional
    public BingoDetailResponse create(Long userId, Long communityId, Long communityGameId, BingoEventRequest request) {
        CommunityGame game = gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.BINGO);
        CommunityUser admin = access.requireCommunityAdmin(userId, communityId); validate(request);
        Community community = lockCommunity(communityId); Instant now = clock.instant();
        List<BingoEvent> existing = refreshCommunityStatuses(communityGameId, now);
        BingoStatus status = request.status() == BingoStatus.DRAFT ? BingoStatus.DRAFT
                : request.startsAt().isAfter(now) ? BingoStatus.SCHEDULED : BingoStatus.ACTIVE;
        requireAvailableStatus(existing, status, null);
        BingoEvent event = new BingoEvent(community, game, admin, request.title().trim(), clean(request.description()),
                request.boardSize(), request.targetLines(), request.blackoutEnabled(), request.allowLateJoin(),
                request.startsAt(), request.endsAt(), status, now);
        addCells(event, request.cells()); events.save(event);
        if (status == BingoStatus.ACTIVE) enrollment.enrollEligible(event, now);
        return detail(event, userId, admin, false);
    }

    public BingoDetailResponse create(Long userId, Long communityId, BingoEventRequest request) {
        Long gameId = gameAccess.requireOnlyManageable(userId, communityId, GameCapability.BINGO).getId();
        return create(userId, communityId, gameId, request);
    }

    @Transactional
    public List<BingoSummaryResponse> list(Long userId, Long communityId, Long communityGameId) {
        gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.BINGO);
        CommunityUser membership = access.requireCommunityAdmin(userId, communityId); Instant now = clock.instant();
        lockCommunity(communityId); refreshCommunityStatuses(communityGameId, now);
        return events.findByCommunityGameIdOrderByStartsAtDesc(communityGameId).stream().map(event -> {
            ensureParticipation(event, membership, now);
            List<BingoParticipant> rows = participants.findByEventIdOrderByIdAsc(event.getId());
            BingoParticipant me = rows.stream().filter(p -> p.getCommunityUser().getUser().getId().equals(userId)).findFirst().orElse(null);
            Integer completed = me == null ? null : (int) progress.findByParticipantIdOrderByCellPositionAsc(me.getId()).stream().filter(BingoProgress::isCompleted).count();
            return BingoSummaryResponse.from(event, rows.size(), completed, me == null ? null : me.getLineCount());
        }).toList();
    }
    public List<BingoSummaryResponse> list(Long userId, Long communityId) {
        return list(userId, communityId, gameAccess.requireOnlyManageable(userId, communityId, GameCapability.BINGO).getId());
    }

    @Transactional
    public BingoCurrentResponse current(Long userId, Long communityId, Long communityGameId) {
        gameAccess.requireAccessible(userId, communityId, communityGameId, GameCapability.BINGO);
        CommunityUser membership = access.requireCommunityMember(userId, communityId); Instant now = clock.instant();
        lockCommunity(communityId);
        List<BingoEvent> communityEvents = refreshCommunityStatuses(communityGameId, now);
        BingoEvent active = communityEvents.stream().filter(event -> event.getStatus() == BingoStatus.ACTIVE)
                .min(Comparator.comparing(BingoEvent::getStartsAt).thenComparing(BingoEvent::getId)).orElse(null);
        if (active != null) {
            ensureParticipation(active, membership, now);
            return BingoCurrentResponse.active(detail(active, userId, membership, true));
        }
        BingoEvent scheduled = communityEvents.stream().filter(event -> event.getStatus() == BingoStatus.SCHEDULED)
                .min(Comparator.comparing(BingoEvent::getStartsAt).thenComparing(BingoEvent::getId)).orElse(null);
        return scheduled == null ? BingoCurrentResponse.none()
                : BingoCurrentResponse.scheduled(detail(scheduled, userId, membership, false));
    }
    public BingoCurrentResponse current(Long userId, Long communityId) {
        return current(userId, communityId, gameAccess.requireOnlyAccessible(userId, communityId, GameCapability.BINGO).getId());
    }

    @Transactional
    public BingoDetailResponse get(Long userId, Long communityId, Long communityGameId, Long bingoId) {
        gameAccess.requireAccessible(userId, communityId, communityGameId, GameCapability.BINGO);
        CommunityUser membership = access.requireCommunityMember(userId, communityId);
        Instant now = clock.instant(); lockCommunity(communityId); refreshCommunityStatuses(communityGameId, now);
        BingoEvent event = requireEvent(communityId, communityGameId, bingoId);
        requireVisible(event, membership); ensureParticipation(event, membership, now);
        return detail(event, userId, membership, true);
    }
    public BingoDetailResponse get(Long userId, Long communityId, Long bingoId) {
        return get(userId, communityId, gameAccess.requireOnlyAccessible(userId, communityId, GameCapability.BINGO).getId(), bingoId);
    }

    @Transactional
    public BingoDetailResponse update(Long userId, Long communityId, Long communityGameId, Long bingoId, BingoEventRequest request) {
        gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.BINGO);
        CommunityUser admin = access.requireCommunityAdmin(userId, communityId);
        Instant now = clock.instant(); lockCommunity(communityId);
        List<BingoEvent> existing = refreshCommunityStatuses(communityGameId, now);
        BingoEvent event = requireEventForUpdate(communityId, communityGameId, bingoId);
        if (event.getStatus() == BingoStatus.COMPLETED || event.getStatus() == BingoStatus.CANCELLED || event.getStatus() == BingoStatus.SETTLING)
            throw conflict("종료되었거나 정산 중인 빙고는 수정할 수 없습니다.");
        if (event.getStatus() == BingoStatus.ACTIVE) {
            if (request.endsAt() == null || !request.endsAt().isAfter(now) || request.endsAt().isBefore(event.getEndsAt()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "진행 중인 빙고의 종료 시간은 현재 종료 시간보다 뒤로만 연장할 수 있습니다.");
            event.updateActive(clean(request.description()), request.endsAt(), now);
        } else {
            validate(request);
            BingoStatus status = request.status() == BingoStatus.DRAFT ? BingoStatus.DRAFT
                    : request.startsAt().isAfter(now) ? BingoStatus.SCHEDULED : BingoStatus.ACTIVE;
            requireAvailableStatus(existing, status, event.getId());
            event.updateDraft(request.title().trim(), clean(request.description()), request.boardSize(), request.targetLines(),
                    request.blackoutEnabled(), request.allowLateJoin(), request.startsAt(), request.endsAt(), status, now);
            event.replaceCells(new ArrayList<>()); addCells(event, request.cells());
            if (status == BingoStatus.ACTIVE) enrollment.enrollEligible(event, now);
        }
        return detail(event, userId, admin, true);
    }
    public BingoDetailResponse update(Long userId, Long communityId, Long bingoId, BingoEventRequest request) {
        return update(userId, communityId, gameAccess.requireOnlyManageable(userId, communityId, GameCapability.BINGO).getId(), bingoId, request);
    }

    @Transactional
    public void deleteOrCancel(Long userId, Long communityId, Long communityGameId, Long bingoId) {
        gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.BINGO);
        access.requireCommunityAdmin(userId, communityId); Instant now = clock.instant(); lockCommunity(communityId);
        refreshCommunityStatuses(communityGameId, now); BingoEvent event = requireEventForUpdate(communityId, communityGameId, bingoId);
        if (event.getStatus() == BingoStatus.DRAFT || event.getStatus() == BingoStatus.SCHEDULED) events.delete(event);
        else if (event.getStatus() != BingoStatus.COMPLETED) event.cancel(clock.instant());
        else throw conflict("완료된 빙고는 삭제할 수 없습니다.");
    }
    public void deleteOrCancel(Long userId, Long communityId, Long bingoId) {
        deleteOrCancel(userId, communityId, gameAccess.requireOnlyManageable(userId, communityId, GameCapability.BINGO).getId(), bingoId);
    }

    @Transactional
    public List<BingoCellCompletionResponse> completions(Long userId, Long communityId, Long communityGameId, Long bingoId, Long cellId) {
        gameAccess.requireAccessible(userId, communityId, communityGameId, GameCapability.BINGO);
        CommunityUser membership = access.requireCommunityMember(userId, communityId); Instant now = clock.instant();
        lockCommunity(communityId); refreshCommunityStatuses(communityGameId, now);
        BingoEvent event = requireEvent(communityId, communityGameId, bingoId); requireVisible(event, membership);
        if (event.getCells().stream().noneMatch(cell -> cell.getId().equals(cellId))) throw notFound();
        return progress.findCompletions(cellId).stream().map(row -> new BingoCellCompletionResponse(
                row.getParticipant().getCommunityUser().getUser().getNickname(), row.getCompletedAt())).toList();
    }
    public List<BingoCellCompletionResponse> completions(Long userId, Long communityId, Long bingoId, Long cellId) {
        return completions(userId, communityId, gameAccess.requireOnlyAccessible(userId, communityId, GameCapability.BINGO).getId(), bingoId, cellId);
    }

    @Transactional
    public BingoDetailResponse.PlayerBoard participantBoard(Long userId, Long communityId, Long communityGameId, Long bingoId, Long participantId) {
        gameAccess.requireAccessible(userId, communityId, communityGameId, GameCapability.BINGO);
        CommunityUser membership = access.requireCommunityMember(userId, communityId); Instant now = clock.instant();
        lockCommunity(communityId); refreshCommunityStatuses(communityGameId, now);
        BingoEvent event = requireEvent(communityId, communityGameId, bingoId); requireVisible(event, membership);
        BingoParticipant participant = participants.findById(participantId)
                .filter(value -> value.getEvent().getId().equals(bingoId))
                .orElseThrow(this::notFound);
        return board(participant, progress.findByParticipantIdOrderByCellPositionAsc(participantId));
    }
    public BingoDetailResponse.PlayerBoard participantBoard(Long userId, Long communityId, Long bingoId, Long participantId) {
        return participantBoard(userId, communityId, gameAccess.requireOnlyAccessible(userId, communityId, GameCapability.BINGO).getId(), bingoId, participantId);
    }

    private void ensureParticipation(BingoEvent event, CommunityUser membership, Instant now) {
        if (event.getStatus() == BingoStatus.ACTIVE || event.getStatus() == BingoStatus.SETTLING) {
            enrollment.enrollEligible(event, now);
            if (event.isAllowLateJoin()) enrollment.enrollLateUser(event, membership, now);
        }
    }

    private Community lockCommunity(Long communityId) {
        return communities.findForUpdate(communityId).orElseThrow(this::notFound);
    }

    private List<BingoEvent> refreshCommunityStatuses(Long communityGameId, Instant now) {
        List<BingoEvent> communityEvents = events.findByCommunityGameIdForUpdate(communityGameId);
        communityEvents.stream().filter(event -> event.getStatus() == BingoStatus.ACTIVE)
                .forEach(event -> event.refreshStatus(now));
        boolean activeExists = communityEvents.stream().anyMatch(event -> event.getStatus() == BingoStatus.ACTIVE);
        if (!activeExists) {
            communityEvents.stream()
                    .filter(event -> event.getStatus() == BingoStatus.SCHEDULED && !now.isBefore(event.getStartsAt()))
                    .min(Comparator.comparing(BingoEvent::getStartsAt).thenComparing(BingoEvent::getId))
                    .ifPresent(event -> {
                        event.refreshStatus(now);
                        enrollment.enrollEligible(event, now);
                    });
        }
        return communityEvents;
    }

    private void requireAvailableStatus(List<BingoEvent> existing, BingoStatus status, Long excludedId) {
        if (status != BingoStatus.ACTIVE && status != BingoStatus.SCHEDULED) return;
        boolean occupied = existing.stream().anyMatch(event -> !Objects.equals(event.getId(), excludedId)
                && event.getStatus() == status);
        if (!occupied) return;
        throw conflict(status == BingoStatus.ACTIVE
                ? "이미 진행 중인 빙고가 있습니다."
                : "이미 예정된 빙고가 있습니다.");
    }

    private void requireVisible(BingoEvent event, CommunityUser membership) {
        if (!isAdmin(membership) && event.getStatus() != BingoStatus.ACTIVE
                && event.getStatus() != BingoStatus.SCHEDULED) throw notFound();
    }
    private BingoDetailResponse detail(BingoEvent event, Long userId, CommunityUser membership, boolean includeParticipants) {
        List<BingoParticipant> participantRows = participants.findByEventIdOrderByIdAsc(event.getId());
        Map<Long, List<BingoProgress>> byParticipant = participantRows.stream().collect(Collectors.toMap(
                BingoParticipant::getId, p -> progress.findByParticipantIdOrderByCellPositionAsc(p.getId())));
        BingoParticipant me = participantRows.stream().filter(p -> p.getCommunityUser().getUser().getId().equals(userId)).findFirst().orElse(null);
        List<BingoDetailResponse.Cell> cells = event.getCells().stream().map(cell -> new BingoDetailResponse.Cell(
                cell.getId(), cell.getPosition(), cell.getMissionType().name(), cell.getAggregationType().name(),
                cell.getOperator().name(), cell.getTargetValue(), cell.getOccurrenceTarget(), cell.getOptions(),
                cell.getCustomTitle(), title(cell))).toList();
        List<BingoDetailResponse.Participant> summaries = includeParticipants ? participantRows.stream().map(p -> {
            int completed = (int) byParticipant.get(p.getId()).stream().filter(BingoProgress::isCompleted).count();
            return new BingoDetailResponse.Participant(p.getId(), p.getCommunityUser().getUser().getNickname(), completed,
                    p.getLineCount(), p.getTargetLinesCompletedAt(), p.getBlackoutCompletedAt());
        }).toList() : List.of();
        return new BingoDetailResponse(event.getId(), event.getTitle(), event.getDescription(), event.getBoardSize(),
                event.getTargetLines(), event.isBlackoutEnabled(), event.isAllowLateJoin(), event.getStartsAt(), event.getEndsAt(),
                event.getStatus().name(), event.getLastAggregatedAt(), isAdmin(membership), cells,
                me == null ? null : board(me, byParticipant.get(me.getId())), summaries);
    }
    private BingoDetailResponse.PlayerBoard board(BingoParticipant p, List<BingoProgress> rows) {
        List<BingoDetailResponse.Progress> values = rows.stream().map(row -> new BingoDetailResponse.Progress(
                row.getCell().getId(), row.getCurrentValue(), row.getOccurrenceCount(), row.isCompleted(),
                row.getCompletedAt(), row.getEvidenceMatchId(), row.getEvidenceEventAt())).toList();
        return new BingoDetailResponse.PlayerBoard(p.getId(), p.getCommunityUser().getUser().getNickname(),
                p.getPubgAccountId() != null, p.getPubgNickname(), (int) rows.stream().filter(BingoProgress::isCompleted).count(),
                p.getLineCount(), p.getTargetLinesCompletedAt(), p.getBlackoutCompletedAt(), values);
    }
    private void validate(BingoEventRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) bad("빙고 이름을 입력해 주세요.");
        if (!Set.of(3,4,5).contains(request.boardSize())) bad("빙고 크기는 3, 4, 5 중 하나여야 합니다.");
        if (request.startsAt() == null || request.endsAt() == null || !request.startsAt().isBefore(request.endsAt())) bad("시작 시간은 종료 시간보다 빨라야 합니다.");
        int maxLines = request.boardSize() * 2 + 2;
        if (request.targetLines() < 1 || request.targetLines() > maxLines) bad("목표 빙고 줄 수가 올바르지 않습니다.");
        int required = request.boardSize() * request.boardSize();
        if (request.cells() == null || request.cells().size() != required) bad("빙고 크기에 맞는 미션 수가 필요합니다.");
        Set<Integer> positions = request.cells().stream().map(BingoEventRequest.Cell::position).collect(Collectors.toSet());
        if (positions.size() != required || positions.stream().anyMatch(p -> p < 0 || p >= required)) bad("빙고 칸 위치가 올바르지 않습니다.");
        request.cells().forEach(cell -> {
            if (cell.missionType() == null || cell.aggregationType() == null || cell.targetValue() == null || cell.targetValue().signum() < 0) bad("미션 조건을 모두 입력해 주세요.");
            if (cell.missionType() == BingoMissionType.KILL_BET_WIN) {
                if (cell.aggregationType() != BingoAggregationType.EVENT_TOTAL || cell.occurrenceTarget() != null)
                    bad("킬내기 승리는 기간 누적으로만 집계할 수 있습니다.");
                if (cell.operator() != null && cell.operator() != BingoOperator.GREATER_THAN_OR_EQUAL)
                    bad("킬내기 승리는 목표 횟수 이상으로만 판정할 수 있습니다.");
                if (cell.targetValue().compareTo(BigDecimal.ONE) < 0 || cell.targetValue().stripTrailingZeros().scale() > 0)
                    bad("킬내기 목표 승리 횟수는 1 이상의 정수여야 합니다.");
            }
            if (cell.aggregationType() == BingoAggregationType.MATCH_OCCURRENCES && (cell.occurrenceTarget() == null || cell.occurrenceTarget() < 1)) bad("달성 경기 수를 입력해 주세요.");
            validateOptions(cell);
        });
    }
    private void validateOptions(BingoEventRequest.Cell cell) {
        Map<String,Object> options = cell.options() == null ? Map.of() : cell.options();
        if (cell.missionType() == BingoMissionType.KILL_BET_WIN && !options.isEmpty())
            bad("킬내기 승리에는 PUBG 경기 조건을 설정할 수 없습니다.");
        String itemId = Objects.toString(options.get("itemId"), null);
        if (itemId != null && itemId.isBlank()) itemId = null;
        if ((cell.missionType() == BingoMissionType.ITEM_PICKUP || cell.missionType() == BingoMissionType.ITEM_USE) && itemId == null)
            bad("아이템을 선택해 주세요.");
        if (itemId != null && !BingoItemCatalog.supported(itemId)) bad("지원하지 않는 PUBG 아이템입니다.");
        if (cell.missionType() == BingoMissionType.ITEM_USE && !BingoItemCatalog.usable(itemId)) bad("사용 이벤트를 확인할 수 없는 아이템입니다.");
        String map = Objects.toString(options.get("map"), null);
        if (map != null && !map.isBlank() && !BingoMapCatalog.supported(map)) bad("지원하지 않는 PUBG 맵입니다.");
        if ((cell.missionType() == BingoMissionType.PLAY_WITH_CLAN_MEMBERS || cell.missionType() == BingoMissionType.RIDE_WITH_CLAN_MEMBERS)
                && optionNumber(options, "clanMemberCount", 0) < 1) bad("필요한 클랜원 수를 입력해 주세요.");
    }
    private void addCells(BingoEvent event, List<BingoEventRequest.Cell> cells) {
        cells.stream().sorted(Comparator.comparingInt(BingoEventRequest.Cell::position)).forEach(cell -> event.addCell(new BingoCell(
                event, cell.position(), cell.missionType(), cell.aggregationType(), cell.operator() == null ? BingoOperator.GREATER_THAN_OR_EQUAL : cell.operator(),
                cell.targetValue(), cell.occurrenceTarget(), cell.options(), clean(cell.customTitle()))));
    }
    private String title(BingoCell cell) {
        if (cell.getCustomTitle() != null && !cell.getCustomTitle().isBlank()) return cell.getCustomTitle();
        Map<String,Object> options = cell.getOptions();
        if (cell.getMissionType() == BingoMissionType.KILL_BET_WIN)
            return "킬내기 " + display(cell.getTargetValue()) + "회 승리";
        if (cell.getMissionType() == BingoMissionType.ITEM_PICKUP || cell.getMissionType() == BingoMissionType.ITEM_USE) {
            String action = cell.getMissionType() == BingoMissionType.ITEM_PICKUP ? "획득" : "사용";
            String phrase = BingoItemCatalog.displayName(displayOption(options,"itemId")) + " " + display(cell.getTargetValue()) + "회 " + action;
            return cell.getAggregationType() == BingoAggregationType.EVENT_TOTAL ? phrase
                    : cell.getAggregationType() == BingoAggregationType.SINGLE_MATCH ? "한 경기 " + phrase
                    : "한 경기 " + phrase + " " + cell.getOccurrenceTarget() + "경기";
        }
        String base = switch (cell.getMissionType()) {
            case KILLS -> "킬"; case DAMAGE_DEALT -> "딜"; case ASSISTS -> "어시스트"; case DBNOS -> "기절";
            case HEADSHOT_KILLS -> "헤드샷 킬"; case LONG_DISTANCE_KILL -> displayOption(options,"distance") + "m 이상 킬";
            case WEAPON_KILLS -> displayOption(options,"weapon") + " 킬";
            case WEAPON_CATEGORY_KILLS -> displayOption(options,"weaponCategory") + " 킬";
            case THROWABLE_KILLS -> displayOption(options,"throwable") + " 킬"; case ROAD_KILLS -> "로드킬";
            case WALL_PENETRATION_KILLS -> "벽 관통 킬"; case WINS -> "치킨"; case TOP10 -> "Top 10";
            case SURVIVAL_TIME -> "생존"; case MATCHES_PLAYED -> "경기 플레이"; case HEALS -> "회복 아이템 사용";
            case BOOSTS -> "부스트 아이템 사용"; case WALK_DISTANCE -> "도보 이동"; case RIDE_DISTANCE -> "차량 이동";
            case SWIM_DISTANCE -> "수영"; case PARACHUTE_DISTANCE -> "낙하산 이동"; case REVIVES -> "팀원 부활";
            case CARRY -> "다운된 팀원 업기"; case PLAY_WITH_CLAN_MEMBERS -> "클랜원과 같은 팀 플레이";
            case THROWABLE_USED -> displayOption(options,"throwable") + " 사용"; case CARE_PACKAGE_PICKUP -> "보급상자 아이템 획득";
            case FLARE_GUN_USED -> "플레어건 사용"; case VAULT_COUNT -> "파쿠르"; case LEDGE_GRAB_COUNT -> "난간 잡기";
            case WHEEL_DESTROY_COUNT -> "차량 타이어 파괴";
            case VEHICLE_DESTROY_COUNT -> "차량 파괴"; case VEHICLE_DAMAGE -> "차량 피해";
            case ARMOR_DESTROY_COUNT -> "적 방어구 파괴"; case FREEFALL_DISTANCE -> "자유낙하 이동";
            case ITEM_PICKUP -> BingoItemCatalog.displayName(displayOption(options,"itemId")) + " 획득";
            case ITEM_USE -> BingoItemCatalog.displayName(displayOption(options,"itemId")) + " 사용";
            case ENEMY_LOOTBOX_PICKUP -> "적 데스박스 아이템 획득";
            case EMERGENCY_PICKUP_RIDE -> "비상호출 탑승";
            case BREACHABLE_WALL_DESTROY_COUNT -> "파괴 가능한 벽 파괴";
            case RIDE_WITH_CLAN_MEMBERS -> "클랜원 " + displayOption(options,"clanMemberCount") + "명과 같은 차량 탑승";
            case KILL_BET_WIN -> "킬내기 승리";
        };
        String target = displayTarget(cell);
        return switch (cell.getAggregationType()) {
            case EVENT_TOTAL -> base + " 누적 " + target;
            case SINGLE_MATCH -> "한 경기 " + base + " " + target + " 이상";
            case MATCH_OCCURRENCES -> "한 경기 " + base + " " + target + " 이상 " + cell.getOccurrenceTarget() + "회";
        };
    }
    private String displayTarget(BingoCell cell) {
        return switch (cell.getMissionType()) {
            case WALK_DISTANCE, RIDE_DISTANCE, PARACHUTE_DISTANCE, FREEFALL_DISTANCE -> display(cell.getTargetValue().divide(BigDecimal.valueOf(1000))) + "km";
            case SWIM_DISTANCE -> display(cell.getTargetValue()) + "m";
            case SURVIVAL_TIME -> display(cell.getTargetValue().divide(BigDecimal.valueOf(60))) + "분";
            default -> display(cell.getTargetValue());
        };
    }
    private String displayOption(Map<String,Object> options, String key) { return Objects.toString(options.get(key), ""); }
    private int optionNumber(Map<String,Object> options, String key, int fallback) {
        Object value = options.get(key); return value instanceof Number number ? number.intValue() : fallback;
    }
    private String display(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private BingoEvent requireEvent(Long communityId, Long communityGameId, Long id) { return events.findWithCellsByIdAndCommunityGameId(id, communityGameId).filter(e -> e.getCommunity().getId().equals(communityId)).orElseThrow(this::notFound); }
    private BingoEvent requireEventForUpdate(Long communityId, Long communityGameId, Long id) { return events.findForUpdate(id).filter(e -> e.getCommunity().getId().equals(communityId) && e.getCommunityGame().getId().equals(communityGameId)).orElseThrow(this::notFound); }
    private boolean isAdmin(CommunityUser user) { return user.getRole() == CommunityUserRole.OWNER || user.getRole() == CommunityUserRole.ADMIN; }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."); }
}
