package com.guildup.bingo;

import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.BingoEventRequest;
import com.guildup.bingo.dto.BingoCurrentResponse;
import com.guildup.bingo.repository.BingoEventRepository;
import com.guildup.bingo.service.BingoEventService;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bingo-event;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class BingoEventFlowTests {
    @Autowired BingoEventService service; @Autowired BingoEventRepository events;
    @Autowired CommunityRepository communities; @Autowired CommunityUserRepository memberships;
    @Autowired CommunityGameRepository games; @Autowired UserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mvc;
    @MockitoBean JDA jda; @MockitoBean DiscordBot discordBot;
    Community community; User owner; User admin; User member;

    @BeforeEach void setUp() {
        community = communities.save(new Community("치즈"));
        games.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
        owner = users.save(new User("소유자")); admin = users.save(new User("관리자")); member = users.save(new User("멤버"));
        memberships.save(new CommunityUser(community, owner, CommunityUserRole.OWNER));
        memberships.save(new CommunityUser(community, admin, CommunityUserRole.ADMIN));
        memberships.save(new CommunityUser(community, member, CommunityUserRole.MEMBER));
    }

    @Test void createsThreeFourAndFiveBoardsWithExactCells() {
        for (int size : List.of(3,4,5)) {
            var created = service.create(owner.getId(), community.getId(), withStatus(request(size, size*size), BingoStatus.DRAFT));
            assertThat(created.boardSize()).isEqualTo(size);
            assertThat(created.cells()).hasSize(size*size);
        }
    }

    @Test void persistsTheExplicitCommunityGameInsteadOfTheFirstGame() {
        CommunityGame steam = games.save(new CommunityGame(community, GameType.BATTLEGROUNDS_STEAM));

        var created = service.create(owner.getId(), community.getId(), steam.getId(), request(3, 9));

        assertThat(events.findById(created.id()).orElseThrow().getCommunityGame().getId())
                .isEqualTo(steam.getId());
    }

    @Test void storesMissionSpecificOptionsAsJson() {
        BingoEventRequest base = request(3, 9);
        List<BingoEventRequest.Cell> cells = new ArrayList<>(base.cells());
        cells.set(0, new BingoEventRequest.Cell(0, BingoMissionType.WEAPON_KILLS,
                BingoAggregationType.EVENT_TOTAL, BingoOperator.GREATER_THAN_OR_EQUAL,
                BigDecimal.valueOf(3), null, Map.of("weapon", "M416"), null));
        var created = service.create(owner.getId(), community.getId(), new BingoEventRequest(base.title(), base.description(),
                base.startsAt(), base.endsAt(), 3, 1, false, false, BingoStatus.SCHEDULED, cells));
        events.flush(); entityManager.clear();
        assertThat(service.get(owner.getId(), community.getId(), created.id()).cells().getFirst().options())
                .containsEntry("weapon", "M416");
    }

    @Test void rejectsWrongMissionCountAndInvalidTime() {
        assertBadRequest(() -> service.create(owner.getId(), community.getId(), request(3, 8)));
        BingoEventRequest valid = request(3,9);
        BingoEventRequest invalid = new BingoEventRequest(valid.title(), valid.description(), valid.endsAt(), valid.startsAt(),
                3,1,false,false,BingoStatus.SCHEDULED,valid.cells());
        assertBadRequest(() -> service.create(owner.getId(), community.getId(), invalid));
    }

    @Test void memberCannotCreateAndOtherCommunityCannotReadById() {
        assertStatus(HttpStatus.FORBIDDEN, () -> service.create(member.getId(), community.getId(), request(3,9)));
        var created = service.create(owner.getId(), community.getId(), request(3,9));
        Community other = communities.save(new Community("다른 곳"));
        games.save(new CommunityGame(other, GameType.BATTLEGROUNDS_KAKAO));
        memberships.save(new CommunityUser(other, owner, CommunityUserRole.OWNER));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(owner.getId(), other.getId(), created.id()));
    }

    @Test void completedEventCannotBeModified() {
        var created = service.create(owner.getId(), community.getId(), request(3,9));
        BingoEvent event = events.findForUpdate(created.id()).orElseThrow(); event.complete(Instant.now());
        assertStatus(HttpStatus.CONFLICT, () -> service.update(owner.getId(), community.getId(), created.id(), request(3,9)));
    }

    @Test void allowsOnlyOneActiveAndOneScheduledButMultipleDrafts() {
        service.create(owner.getId(), community.getId(), activeRequest("진행 1"));
        assertStatus(HttpStatus.CONFLICT, () -> service.create(owner.getId(), community.getId(), activeRequest("진행 2")));

        service.create(owner.getId(), community.getId(), scheduledRequest("예정 1", 2));
        assertStatus(HttpStatus.CONFLICT, () -> service.create(owner.getId(), community.getId(), scheduledRequest("예정 2", 3)));

        service.create(owner.getId(), community.getId(), draftRequest("초안 1"));
        service.create(owner.getId(), community.getId(), draftRequest("초안 2"));
        assertThat(events.findByCommunityIdOrderByStartsAtDesc(community.getId()))
                .filteredOn(event -> event.getStatus() == BingoStatus.DRAFT).hasSize(2);
    }

    @Test void currentPrefersActiveThenNearestScheduledAndReturnsNone() {
        var later = service.create(owner.getId(), community.getId(), scheduledRequest("다음 빙고", 2));
        BingoCurrentResponse scheduled = service.current(member.getId(), community.getId());
        assertThat(scheduled.type()).isEqualTo("SCHEDULED");
        assertThat(scheduled.bingo().id()).isEqualTo(later.id());

        var active = service.create(owner.getId(), community.getId(), activeRequest("현재 빙고"));
        BingoCurrentResponse current = service.current(member.getId(), community.getId());
        assertThat(current.type()).isEqualTo("ACTIVE");
        assertThat(current.bingo().id()).isEqualTo(active.id());

        Community empty = communities.save(new Community("빈 커뮤니티"));
        games.save(new CommunityGame(empty, GameType.BATTLEGROUNDS_KAKAO));
        memberships.save(new CommunityUser(empty, member, CommunityUserRole.MEMBER));
        assertThat(service.current(member.getId(), empty.getId()).type()).isEqualTo("NONE");
    }

    @Test void currentStaticRouteIsNotParsedAsNumericBingoId() throws Exception {
        Long gameId = games.findByCommunityIdOrderByIdAsc(community.getId()).get(0).getId();
        mvc.perform(get("/api/communities/{communityId}/games/{communityGameId}/bingos/current", community.getId(), gameId))
                .andExpect(status().isUnauthorized());
    }

    @Test void rejectsDraftTransitionWhenActiveOrScheduledSlotIsOccupied() {
        service.create(owner.getId(), community.getId(), activeRequest("진행"));
        service.create(owner.getId(), community.getId(), scheduledRequest("예정", 2));
        var activeDraft = service.create(owner.getId(), community.getId(), draftRequest("진행 전환 후보"));
        var scheduledDraft = service.create(owner.getId(), community.getId(), draftRequest("예정 전환 후보"));

        assertStatus(HttpStatus.CONFLICT, () -> service.update(owner.getId(), community.getId(),
                activeDraft.id(), activeRequest("진행 전환 후보")));
        assertStatus(HttpStatus.CONFLICT, () -> service.update(owner.getId(), community.getId(),
                scheduledDraft.id(), scheduledRequest("예정 전환 후보", 3)));
    }

    @Test void memberCannotReadManagedListOrHiddenStatusesButAdminsCan() {
        var draft = service.create(owner.getId(), community.getId(), draftRequest("초안"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.list(member.getId(), community.getId()));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(member.getId(), community.getId(), draft.id()));

        var completed = service.create(owner.getId(), community.getId(), activeRequest("완료"));
        events.findForUpdate(completed.id()).orElseThrow().complete(Instant.now());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(member.getId(), community.getId(), completed.id()));
        var completedForOwner = service.get(owner.getId(), community.getId(), completed.id());
        assertThat(completedForOwner.status()).isEqualTo("COMPLETED");
        assertStatus(HttpStatus.NOT_FOUND, () -> service.completions(member.getId(), community.getId(),
                completed.id(), completedForOwner.cells().getFirst().id()));

        var cancelled = service.create(owner.getId(), community.getId(), activeRequest("취소"));
        service.deleteOrCancel(owner.getId(), community.getId(), cancelled.id());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(member.getId(), community.getId(), cancelled.id()));
        assertThat(service.get(admin.getId(), community.getId(), cancelled.id()).status()).isEqualTo("CANCELLED");
    }

    private BingoEventRequest request(int size, int count) {
        Instant start = Instant.now().plus(Duration.ofDays(1)), end = start.plus(Duration.ofDays(10));
        List<BingoEventRequest.Cell> cells = new ArrayList<>();
        for (int i=0;i<count;i++) cells.add(new BingoEventRequest.Cell(i, BingoMissionType.KILLS,
                BingoAggregationType.EVENT_TOTAL, BingoOperator.GREATER_THAN_OR_EQUAL,
                BigDecimal.valueOf(5), null, Map.of(), null));
        return new BingoEventRequest("가을 빙고", "설명", start, end, size, 1, true, false, BingoStatus.SCHEDULED, cells);
    }
    private BingoEventRequest activeRequest(String title) {
        BingoEventRequest base = request(3, 9);
        return new BingoEventRequest(title, base.description(), Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofDays(1)), 3, 1, true, false, BingoStatus.ACTIVE, base.cells());
    }
    private BingoEventRequest scheduledRequest(String title, int days) {
        BingoEventRequest base = request(3, 9); Instant start = Instant.now().plus(Duration.ofDays(days));
        return new BingoEventRequest(title, base.description(), start, start.plus(Duration.ofDays(1)),
                3, 1, true, false, BingoStatus.SCHEDULED, base.cells());
    }
    private BingoEventRequest draftRequest(String title) {
        BingoEventRequest base = request(3, 9);
        return new BingoEventRequest(title, base.description(), base.startsAt(), base.endsAt(), 3, 1,
                true, false, BingoStatus.DRAFT, base.cells());
    }
    private BingoEventRequest withStatus(BingoEventRequest request, BingoStatus status) {
        return new BingoEventRequest(request.title(), request.description(), request.startsAt(), request.endsAt(),
                request.boardSize(), request.targetLines(), request.blackoutEnabled(), request.allowLateJoin(),
                status, request.cells());
    }
    private void assertBadRequest(ThrowingCallable call) { assertStatus(HttpStatus.BAD_REQUEST, call); }
    private void assertStatus(HttpStatus status, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
