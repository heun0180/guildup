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
        owner=users.save(new User("애플")); memberships.save(new CommunityUser(community,owner,CommunityUserRole.OWNER));
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
        Instant before=Instant.parse("2026-09-22T10:59:59Z"), inside=Instant.parse("2026-09-22T11:30:00Z"), after=Instant.parse("2026-09-22T14:00:01Z");
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
                new PubgPlayer("account-a","ApplePUBG",List.of("solo","clan"))));
        when(pubgMatches.findUniqueMatches(eq("kakao"),anyCollection())).thenReturn(Map.of(
                "solo",match("solo",soloAt),"clan",match("clan",clanAt)));
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
    }

    private BingoEventRequest request(){
        List<BingoEventRequest.Cell> cells=new ArrayList<>();
        for(int i=0;i<9;i++) cells.add(new BingoEventRequest.Cell(i,BingoMissionType.KILLS,BingoAggregationType.EVENT_TOTAL,
                BingoOperator.GREATER_THAN_OR_EQUAL,BigDecimal.valueOf(5),null,Map.of(),null));
        return new BingoEventRequest("진행 빙고",null,clock.instant().minus(Duration.ofHours(1)),clock.instant().plus(Duration.ofHours(2)),3,3,true,false,BingoStatus.ACTIVE,cells);
    }
    private PubgMatch match(String id,Instant at){return new PubgMatch(id,at,"squad",List.of(new PubgTeam(List.of(new PubgParticipant("account-a","ApplePUBG",5)))));}
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
