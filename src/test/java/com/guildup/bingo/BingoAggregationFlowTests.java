package com.guildup.bingo;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.BingoEventRequest;
import com.guildup.bingo.mission.*;
import com.guildup.bingo.repository.*;
import com.guildup.bingo.service.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.*;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:bingo-aggregation;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@Import(BingoAggregationFlowTests.ClockConfiguration.class)
@Transactional
class BingoAggregationFlowTests {
    @Autowired BingoEventService events; @Autowired BingoAggregationService aggregation;
    @Autowired BingoParticipantRepository participants; @Autowired BingoProgressRepository progress;
    @Autowired CommunityRepository communities; @Autowired CommunityUserRepository memberships;
    @Autowired CommunityMemberRepository members; @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired CommunityGameRepository games; @Autowired UserRepository users; @Autowired UserExternalAccountRepository userAccounts;
    @Autowired MutableClock clock;
    @MockitoBean PubgPlayerService pubgPlayers; @MockitoBean PubgMatchService pubgMatches;
    @MockitoBean PubgBingoFactService factService; @MockitoBean JDA jda; @MockitoBean DiscordBot bot;
    Community community; User owner;

    @BeforeEach void setUp(){
        reset(pubgPlayers,pubgMatches,factService); clock.set(Instant.parse("2026-09-22T12:00:00Z"));
        community=communities.save(new Community("치즈")); games.save(new CommunityGame(community,GameType.BATTLEGROUNDS_KAKAO));
        owner=users.save(new User("애플"));
        CommunityUser membership=new CommunityUser(community,owner,CommunityUserRole.OWNER);
        ReflectionTestUtils.setField(membership,"joinedAt",clock.instant().minus(Duration.ofHours(2)));
        memberships.save(membership);
        userAccounts.save(new UserExternalAccount(owner,ExternalAccountProvider.DISCORD,"discord-a","애플"));
        CommunityMember member=members.save(new CommunityMember(community,"애플"));
        memberAccounts.save(new CommunityMemberAccount(member,ExternalAccountProvider.DISCORD,"discord-a","애플"));
        memberAccounts.save(new CommunityMemberAccount(member,ExternalAccountProvider.PUBG,"account-a","ApplePUBG"));
    }

    @Test void aggregatesOnlyActualMatchTimeOnceAndCompletesLinesAndBlackout(){
        var event=events.create(owner.getId(),community.getId(),request());
        var detail=events.get(owner.getId(),community.getId(),event.id());
        assertThat(detail.participants()).singleElement().satisfies(participant -> {
            assertThat(participant.nickname()).isEqualTo("애플");
            assertThat(participant.pubgNickname()).isEqualTo("ApplePUBG");
        });
        Instant before=Instant.parse("2026-09-22T10:59:59Z"), inside=Instant.parse("2026-09-22T11:30:00Z"), after=Instant.parse("2026-09-22T14:01:00Z");
        List<PubgPlayer> playerRows=List.of(new PubgPlayer("account-a","ApplePUBG",List.of("before","late-api","after")));
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(playerRows);
        Map<String,PubgMatch> matchRows=new LinkedHashMap<>();
        matchRows.put("before",match("before",before)); matchRows.put("late-api",match("late-api",inside)); matchRows.put("after",match("after",after));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenReturn(matchRows);
        when(factService.facts(argThat(m->m.matchId().equals("late-api")),anySet())).thenReturn(Map.of("account-a",facts("late-api",inside,5)));

        var first=aggregation.aggregate(owner.getId(),community.getId(),event.id());
        clock.set(clock.instant().plus(Duration.ofMinutes(30)));
        var second=aggregation.aggregate(owner.getId(),community.getId(),event.id());

        assertThat(first.processedMatches()).isEqualTo(1); assertThat(second.processedMatches()).isZero();
        BingoParticipant participant=participants.findByEventIdOrderByIdAsc(event.id()).getFirst();
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant.getId())).hasSize(9).allMatch(BingoProgress::isCompleted);
        assertThat(participant.getLineCount()).isEqualTo(8);
        assertThat(participant.getTargetLinesCompletedAt()).isNotNull(); assertThat(participant.getBlackoutCompletedAt()).isNotNull();
        verify(factService,times(1)).facts(any(),anySet());
    }

    @Test void aggregationUsesTheGameStoredOnTheEvent(){
        CommunityGame steam = games.save(new CommunityGame(community,GameType.BATTLEGROUNDS_STEAM));
        var event=events.create(owner.getId(),community.getId(),steam.getId(),request());
        when(pubgPlayers.findByAccountIdsFresh(eq("steam"),anyList())).thenReturn(List.of());

        aggregation.aggregate(owner.getId(),community.getId(),event.id());

        verify(pubgPlayers).findByAccountIdsFresh(eq("steam"),anyList());
    }

    @Test void rejectsAggregationUntilThirtyMinutesHavePassed(){
        var event=events.create(owner.getId(),community.getId(),request());
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(List.of());

        aggregation.aggregate(owner.getId(),community.getId(),event.id());

        assertThatThrownBy(() -> aggregation.aggregate(owner.getId(),community.getId(),event.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.getReason()).isEqualTo("빙고 집계는 30분에 한 번만 실행할 수 있습니다.");
                });
        verify(pubgPlayers,times(1)).findByAccountIdsFresh(eq("kakao"),anyList());

        clock.set(clock.instant().plus(Duration.ofMinutes(30)));
        aggregation.aggregate(owner.getId(),community.getId(),event.id());
        verify(pubgPlayers,times(2)).findByAccountIdsFresh(eq("kakao"),anyList());
    }

    @Test void lateParticipantIgnoresMatchesBeforeEligibleFrom(){
        CommunityUser membership=memberships.findByCommunityIdAndUserId(community.getId(),owner.getId()).orElseThrow();
        ReflectionTestUtils.setField(membership,"joinedAt",clock.instant().minus(Duration.ofMinutes(30)));
        BingoEventRequest base=request();
        var event=events.create(owner.getId(),community.getId(),new BingoEventRequest(base.title(),base.description(),
                base.startsAt(),base.endsAt(),base.boardSize(),base.targetLines(),base.blackoutEnabled(),true,base.status(),base.cells()));
        Instant before=clock.instant().minus(Duration.ofMinutes(15)), after=clock.instant();
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(List.of(
                new PubgPlayer("account-a","ApplePUBG",List.of("before-join","after-join"))));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenReturn(Map.of(
                "before-join",match("before-join",before),"after-join",match("after-join",after)));
        when(factService.facts(argThat(m->m.matchId().equals("after-join")),anySet()))
                .thenReturn(Map.of("account-a",facts("after-join",after,5)));

        assertThat(aggregation.aggregate(owner.getId(),community.getId(),event.id()).processedMatches()).isEqualTo(1);
        verify(factService,never()).facts(argThat(m->m.matchId().equals("before-join")),anySet());
    }

    @Test void participantWithoutPubgAccountKeepsBoardAndSkipsAggregation(){
        CommunityMember member=members.findByCommunityIdOrderByIdAsc(community.getId()).getFirst();
        memberAccounts.delete(memberAccounts.findByCommunityMemberIdAndProvider(member.getId(),ExternalAccountProvider.PUBG).orElseThrow());
        memberAccounts.flush();
        var event=events.create(owner.getId(),community.getId(),request());
        assertThat(event.me()).isNotNull(); assertThat(event.me().pubgConnected()).isFalse();
        assertThat(aggregation.aggregate(owner.getId(),community.getId(),event.id()).processedMatches()).isZero();
        verifyNoInteractions(pubgPlayers,pubgMatches,factService);
    }

    @Test void aggregationRefreshesTheParticipantAccountSnapshotFromTheCurrentStableAccountId(){
        var event=events.create(owner.getId(),community.getId(),request());
        CommunityMember member=members.findByCommunityIdOrderByIdAsc(community.getId()).getFirst();
        memberAccounts.delete(memberAccounts.findByCommunityMemberIdAndProvider(
                member.getId(),ExternalAccountProvider.PUBG).orElseThrow());
        memberAccounts.flush();
        memberAccounts.saveAndFlush(new CommunityMemberAccount(
                member,ExternalAccountProvider.PUBG,"account-new","RenamedPUBG"));
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(List.of());

        aggregation.aggregate(owner.getId(),community.getId(),event.id());

        verify(pubgPlayers).findByAccountIdsFresh("kakao",List.of("account-new"));
        BingoParticipant participant=participants.findByEventIdOrderByIdAsc(event.id()).getFirst();
        assertThat(participant.getPubgAccountId()).isEqualTo("account-new");
        assertThat(participant.getPubgNickname()).isEqualTo("RenamedPUBG");
    }

    @Test void cellClanPlayRequirementIgnoresPersonalStatsFromSoloMatches(){
        BingoEventRequest base=request();
        List<BingoEventRequest.Cell> cells=base.cells().stream().map(cell -> new BingoEventRequest.Cell(
                cell.position(),cell.missionType(),cell.aggregationType(),cell.operator(),
                BigDecimal.TEN,cell.occurrenceTarget(),Map.of("clanPlayRequired",true),cell.customTitle())).toList();
        BingoEventRequest clanOnly=new BingoEventRequest(base.title(),base.description(),base.startsAt(),base.endsAt(),
                base.boardSize(),base.targetLines(),base.blackoutEnabled(),base.allowLateJoin(),base.status(),cells);
        var event=events.create(owner.getId(),community.getId(),clanOnly);
        Instant soloAt=clock.instant().minus(Duration.ofMinutes(30));
        Instant clanAt=clock.instant().minus(Duration.ofMinutes(10));
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(List.of(
                new PubgPlayer("account-a","ApplePUBG",List.of("solo","casual-clan","clan"))));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenReturn(Map.of(
                "solo",match("solo",soloAt),
                "casual-clan",match("casual-clan",clanAt.minusSeconds(30),"airoyale",false),
                "clan",match("clan",clanAt)));
        when(factService.facts(argThat(m->m != null && m.matchId().equals("solo")),anySet()))
                .thenReturn(Map.of("account-a",facts("solo",soloAt,5,0)));
        when(factService.facts(argThat(m->m != null && m.matchId().equals("clan")),anySet()))
                .thenReturn(Map.of("account-a",facts("clan",clanAt,5,1)));

        var result=aggregation.aggregate(owner.getId(),community.getId(),event.id());

        BingoParticipant participant=participants.findByEventIdOrderByIdAsc(event.id()).getFirst();
        assertThat(result.processedMatches()).isEqualTo(2);
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant.getId()))
                .allSatisfy(row -> {
                    assertThat(row.getCurrentValue()).isEqualByComparingTo("5");
                    assertThat(row.isCompleted()).isFalse();
                });
        verify(factService,never()).facts(argThat(m->m != null && m.matchId().equals("casual-clan")),anySet());
    }

    @Test void aggregatesNormalAndRankedMatchesTogether(){
        var event=events.create(owner.getId(),community.getId(),request());
        Instant normalAt=clock.instant().minus(Duration.ofMinutes(30));
        Instant rankedAt=clock.instant().minus(Duration.ofMinutes(10));
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(List.of(
                new PubgPlayer("account-a","ApplePUBG",List.of("normal","ranked"))));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenReturn(Map.of(
                "normal",match("normal",normalAt,"official",false),
                "ranked",match("ranked",rankedAt,"competitive",false)));
        when(factService.facts(argThat(m->m != null && m.matchId().equals("normal")),anySet()))
                .thenReturn(Map.of("account-a",facts("normal",normalAt,2)));
        when(factService.facts(argThat(m->m != null && m.matchId().equals("ranked")),anySet()))
                .thenReturn(Map.of("account-a",facts("ranked",rankedAt,3)));

        var result=aggregation.aggregate(owner.getId(),community.getId(),event.id());

        BingoParticipant participant=participants.findByEventIdOrderByIdAsc(event.id()).getFirst();
        assertThat(result.processedMatches()).isEqualTo(2);
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant.getId()))
                .allSatisfy(row -> assertThat(row.getCurrentValue()).isEqualByComparingTo("5"));
        verify(factService).facts(argThat(m->m.matchType().equals("official")),anySet());
        verify(factService).facts(argThat(m->m.matchType().equals("competitive")),anySet());
    }

    @Test void excludedMatchesNeverReachFactsMissionEngineOrTelemetryBasedMissions(){
        var event=events.create(owner.getId(),community.getId(),mixedMissionRequest());
        Instant at=clock.instant().minus(Duration.ofMinutes(10));
        Map<String,PubgMatch> rejected=new LinkedHashMap<>();
        rejected.put("casual",match("casual",at,"airoyale",false));
        rejected.put("custom-flag",match("custom-flag",at,"official",true));
        rejected.put("custom-type",match("custom-type",at,"custom",false));
        rejected.put("arcade",match("arcade",at,"arcade",false));
        rejected.put("event",match("event",at,"event",false));
        rejected.put("unknown",match("unknown",at,"brand-new-mode",false));
        rejected.put("missing",match("missing",at,null,false));
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(List.of(
                new PubgPlayer("account-a","ApplePUBG",new ArrayList<>(rejected.keySet()))));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenReturn(rejected);

        var result=aggregation.aggregate(owner.getId(),community.getId(),event.id());

        BingoParticipant participant=participants.findByEventIdOrderByIdAsc(event.id()).getFirst();
        assertThat(result.processedMatches()).isZero();
        assertThat(progress.findByParticipantIdOrderByCellPositionAsc(participant.getId()))
                .allSatisfy(row -> assertThat(row.getCurrentValue()).isEqualByComparingTo("0"));
        verifyNoInteractions(factService);
    }

    @Test void matchStartUsesInclusiveStartAndTheWholeInclusiveUiEndMinuteOnly(){
        var event=events.create(owner.getId(),community.getId(),request());
        Instant start=clock.instant().minus(Duration.ofHours(1));
        Instant endMinute=clock.instant().plus(Duration.ofHours(2));
        Map<String,PubgMatch> rows=new LinkedHashMap<>();
        rows.put("before",match("before",start.minusSeconds(1)));
        rows.put("start",match("start",start));
        rows.put("end-minute",match("end-minute",endMinute.plusSeconds(59)));
        rows.put("next-minute",match("next-minute",endMinute.plusSeconds(60)));
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(List.of(
                new PubgPlayer("account-a","ApplePUBG",new ArrayList<>(rows.keySet()))));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenReturn(rows);
        when(factService.facts(argThat(m->m != null && m.matchId().equals("start")),anySet())).thenReturn(Map.of(
                "account-a",facts("start",endMinute.plus(Duration.ofHours(1)),1)));
        when(factService.facts(argThat(m->m != null && m.matchId().equals("end-minute")),anySet())).thenReturn(Map.of(
                "account-a",facts("end-minute",endMinute.plus(Duration.ofHours(3)),1)));

        var result=aggregation.aggregate(owner.getId(),community.getId(),event.id());

        assertThat(result.processedMatches()).isEqualTo(2);
        verify(factService,never()).facts(argThat(m->m != null && Set.of("before","next-minute").contains(m.matchId())),anySet());
    }

    @Test void eligibleNormalAndRankedMatchesDoNotRequirePerspectiveTeamSizeOrClanMate(){
        var event=events.create(owner.getId(),community.getId(),request());
        Instant at=clock.instant().minus(Duration.ofMinutes(10));
        List<PubgMatch> rows=List.of(
                new PubgMatch("solo",at,"solo","Erangel_Main","official",false,null,
                        List.of(new PubgTeam(List.of(new PubgParticipant("account-a","ApplePUBG"))))),
                new PubgMatch("duo-fpp",at.plusSeconds(1),"duo-fpp","Erangel_Main","official",false,null,
                        List.of(new PubgTeam(List.of(new PubgParticipant("account-a","ApplePUBG"))))),
                new PubgMatch("squad-fpp",at.plusSeconds(2),"squad-fpp","Erangel_Main","competitive",false,null,
                        List.of(new PubgTeam(List.of(new PubgParticipant("account-a","ApplePUBG")))))
        );
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenReturn(List.of(
                new PubgPlayer("account-a","ApplePUBG",rows.stream().map(PubgMatch::matchId).toList())));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenReturn(rows.stream().collect(
                java.util.stream.Collectors.toMap(PubgMatch::matchId,java.util.function.Function.identity())));
        when(factService.facts(any(),anySet())).thenAnswer(invocation -> {
            PubgMatch match=invocation.getArgument(0);
            return Map.of("account-a",facts(match.matchId(),match.playedAt(),1,0));
        });

        assertThat(aggregation.aggregate(owner.getId(),community.getId(),event.id()).processedMatches()).isEqualTo(3);
    }

    @Test void repeatedTenMatchWindowsAccumulateAllFortyMatches(){
        assertThat(simulateRecentMatchWindows(10,10,10,10)).isEqualTo(40);
    }

    @Test void repeatedTwentyMatchWindowsAccumulateAllFortyMatches(){
        assertThat(simulateRecentMatchWindows(20,20)).isEqualTo(40);
    }

    @Test void repeatedThirtyOneMatchWindowsAccumulateAllSixtyTwoMatches(){
        assertThat(simulateRecentMatchWindows(31,31)).isEqualTo(62);
    }

    @Test void firstUnaggregatedGapOfThirtyThreeMatchesLosesTheOldestMatch(){
        assertThat(simulateRecentMatchWindows(33)).isEqualTo(32);
    }

    private int simulateRecentMatchWindows(int... batches){
        var event=events.create(owner.getId(),community.getId(),request());
        List<String> visible=new ArrayList<>();
        when(pubgPlayers.findByAccountIdsFresh(eq("kakao"),anyList())).thenAnswer(ignored -> List.of(
                new PubgPlayer("account-a","ApplePUBG",List.copyOf(visible))));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenAnswer(invocation -> {
            Collection<String> ids=invocation.getArgument(1); Map<String,PubgMatch> result=new LinkedHashMap<>();
            ids.forEach(id -> result.put(id,match(id,Instant.parse("2026-09-22T11:00:00Z").plusSeconds(index(id)))));
            return result;
        });
        when(factService.facts(any(),anySet())).thenAnswer(invocation -> {
            PubgMatch match=invocation.getArgument(0); return Map.of("account-a",facts(match.matchId(),match.playedAt(),1));
        });
        int total=0;
        for(int batch:batches){
            total+=batch; List<String> all=java.util.stream.IntStream.rangeClosed(1,total).mapToObj(i->"m"+i).toList();
            visible.clear(); visible.addAll(all.subList(Math.max(0,all.size()-32),all.size()));
            aggregation.aggregate(owner.getId(),community.getId(),event.id());
            clock.set(clock.instant().plus(Duration.ofMinutes(30)));
        }
        BingoParticipant participant=participants.findByEventIdOrderByIdAsc(event.id()).getFirst();
        return progress.findByParticipantIdOrderByCellPositionAsc(participant.getId()).getFirst().getCurrentValue().intValue();
    }

    private long index(String id){return Long.parseLong(id.substring(1));}

    private BingoEventRequest request(){
        List<BingoEventRequest.Cell> cells=new ArrayList<>();
        for(int i=0;i<9;i++) cells.add(new BingoEventRequest.Cell(i,BingoMissionType.KILLS,BingoAggregationType.EVENT_TOTAL,
                BingoOperator.GREATER_THAN_OR_EQUAL,BigDecimal.valueOf(5),null,Map.of(),null));
        return new BingoEventRequest("진행 빙고",null,clock.instant().minus(Duration.ofHours(1)),clock.instant().plus(Duration.ofHours(2)),3,3,true,false,BingoStatus.ACTIVE,cells);
    }
    private BingoEventRequest mixedMissionRequest(){
        List<BingoMissionType> types=List.of(BingoMissionType.KILLS,BingoMissionType.DAMAGE_DEALT,
                BingoMissionType.MATCHES_PLAYED,BingoMissionType.WALK_DISTANCE,BingoMissionType.CARE_PACKAGE_PICKUP,
                BingoMissionType.KILLS,BingoMissionType.DAMAGE_DEALT,BingoMissionType.MATCHES_PLAYED,
                BingoMissionType.WALK_DISTANCE);
        List<BingoEventRequest.Cell> cells=new ArrayList<>();
        for(int i=0;i<types.size();i++) cells.add(new BingoEventRequest.Cell(i,types.get(i),BingoAggregationType.EVENT_TOTAL,
                BingoOperator.GREATER_THAN_OR_EQUAL,BigDecimal.ONE,null,Map.of(),null));
        return new BingoEventRequest("모드 필터 빙고",null,clock.instant().minus(Duration.ofHours(1)),
                clock.instant().plus(Duration.ofHours(2)),3,3,true,false,BingoStatus.ACTIVE,cells);
    }
    private PubgMatch match(String id,Instant at){return match(id,at,"official",false);}
    private PubgMatch match(String id,Instant at,String matchType,boolean custom){
        return new PubgMatch(id,at,"squad","Erangel_Main",matchType,custom,null,
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a","ApplePUBG",5)))));
    }
    private PlayerMatchFacts facts(String id,Instant at,int kills){return new PlayerMatchFacts(id,at,"Erangel_Main","squad",Map.of("KILLS",BigDecimal.valueOf(kills)),List.of(),Map.of(),0,at);}
    private PlayerMatchFacts facts(String id,Instant at,int kills,int clanMembersInTeam){return new PlayerMatchFacts(id,at,"Erangel_Main","squad",Map.of("KILLS",BigDecimal.valueOf(kills)),List.of(),Map.of(),clanMembersInTeam,at);}

    @TestConfiguration static class ClockConfiguration {
        @Bean @Primary MutableClock bingoClock(){return new MutableClock();}
    }
    static class MutableClock extends Clock {
        private Instant instant=Instant.EPOCH; void set(Instant value){instant=value;}
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return instant;}
    }
}
