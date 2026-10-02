import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import AppLink from "../components/AppLink.jsx";
import Icon from "../components/Icon.jsx";

const PAGE_SIZE = 20;

function useApi(url) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(Boolean(url));
  useEffect(() => {
    if (!url) return;
    let cancelled = false;
    setLoading(true);
    setError("");
    api(url).then((result) => { if (!cancelled) setData(result); })
      .catch((failure) => { if (!cancelled && !redirectToLogin(failure)) setError(failure.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [url]);
  return { data, error, loading };
}

function formatDate(value) {
  return value ? new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "-";
}

function CopyId({ value, label }) {
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState(false);
  if (value === null || value === undefined || value === "") return <span>-</span>;
  async function copy(event) {
    event.preventDefault();
    event.stopPropagation();
    try {
      await navigator.clipboard.writeText(String(value));
      setCopied(true);
      setCopyError(false);
      window.setTimeout(() => setCopied(false), 1200);
    } catch {
      setCopyError(true);
    }
  }
  return <span className="developer-id"><code title={String(value)}>{value}</code>
    <button type="button" onClick={copy} aria-label={`${label || "ID"} 복사`}
      title={copyError ? "클립보드 권한을 확인하거나 ID를 직접 선택해 복사해 주세요." : undefined}>{copyError ? "복사 실패" : copied ? "복사됨" : "복사"}</button>
  </span>;
}

function Status({ value }) {
  return <span className={`developer-status status-${String(value || "unknown").toLowerCase()}`}>{value || "-"}</span>;
}

function State({ loading, error, empty, children }) {
  if (loading) return <p className="panel page-state">데이터를 불러오는 중입니다.</p>;
  if (error) return <p className="message" role="alert">{error}</p>;
  if (empty) return <p className="panel page-state">조회된 데이터가 없습니다.</p>;
  return children;
}

function Pager({ page }) {
  if (!page || page.totalPages <= 1) return null;
  const params = new URLSearchParams(window.location.search);
  function href(number) {
    params.set("page", String(number));
    return `${window.location.pathname}?${params}`;
  }
  return <nav className="developer-pager" aria-label="페이지 이동">
    <AppLink className={page.page === 0 ? "is-disabled" : ""} href={href(Math.max(0, page.page - 1))}>이전</AppLink>
    <span>{page.page + 1} / {page.totalPages}</span>
    <AppLink className={page.page + 1 >= page.totalPages ? "is-disabled" : ""} href={href(Math.min(page.totalPages - 1, page.page + 1))}>다음</AppLink>
  </nav>;
}

function GlobalSearch() {
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const { data, loading, error } = useApi(submitted ? `/api/developer/search?q=${encodeURIComponent(submitted)}` : null);
  return <section className="panel developer-search-panel">
    <form onSubmit={(event) => { event.preventDefault(); setSubmitted(query.trim()); }}>
      <Icon name="search" />
      <input value={query} onChange={(event) => setQuery(event.target.value)}
             placeholder="Community, 사용자 닉네임, Discord ID, PUBG 닉네임/Account ID" aria-label="개발자 통합 검색" />
      <button type="submit">검색</button>
    </form>
    {loading && <p>검색 중...</p>}
    {error && <p className="message">{error}</p>}
    {data && <div className="developer-search-results">
      {data.results.length === 0 ? <p>검색 결과가 없습니다.</p> : data.results.map((item, index) => {
        const href = item.type === "COMMUNITY" ? `/developer/communities/${item.id}`
          : item.communityId ? `/developer/communities/${item.communityId}` : null;
        const body = <><Status value={item.type} /><strong>{item.label}</strong><CopyId value={item.id} label={item.type} />
          {item.secondaryId && <CopyId value={item.secondaryId} label="연결 ID" />}</>;
        return href ? <AppLink href={href} key={`${item.type}-${item.id}-${index}`}>{body}</AppLink>
          : <div key={`${item.type}-${item.id}-${index}`}>{body}</div>;
      })}
    </div>}
  </section>;
}

function PageHeading({ eyebrow = "Developer", title, description, back }) {
  return <div className="page-heading developer-heading">
    {back && <AppLink className="developer-back" href={back}>← 돌아가기</AppLink>}
    <p className="eyebrow">{eyebrow}</p><h1>{title}</h1>{description && <p>{description}</p>}
  </div>;
}

export function DeveloperDashboardPage() {
  const { data, loading, error } = useApi("/api/developer/dashboard");
  return <div className="dashboard-content developer-content">
    <PageHeading title="서비스 현황" description="GuildUp 운영 데이터의 읽기 전용 요약입니다." />
    <GlobalSearch />
    <State loading={loading} error={error} empty={false}>{data && <>
      <section className="developer-stat-grid">
        {[["전체 커뮤니티", data.communityCount], ["전체 GuildUp 사용자", data.userCount],
          ["전체 게임 클랜원", data.clanMemberCount], ["Discord 연결 커뮤니티", data.discordConnectedCommunityCount],
          ["진행 중 빙고", data.activeBingoCount], ["진행 중 킬내기", data.activeKillCompetitionCount]].map(([label, value]) =>
          <article className="panel developer-stat" key={label}><span>{label}</span><strong>{value.toLocaleString()}</strong></article>)}
      </section>
      <section className="developer-dashboard-lists">
        <article className="panel developer-list-card"><header><h2>최근 생성 커뮤니티</h2><AppLink href="/developer/communities">전체 보기</AppLink></header>
          {data.recentCommunities.map((item) => <AppLink key={item.id} href={`/developer/communities/${item.id}`}>
            <span><strong>{item.name}</strong><small>{formatDate(item.createdAt)}</small></span><CopyId value={item.id} label="Community ID" />
          </AppLink>)}</article>
        <article className="panel developer-list-card"><header><h2>최근 가입 사용자</h2></header>
          {data.recentUsers.map((item) => <div key={item.id}>
            <span><strong>{item.nickname}</strong><small>{formatDate(item.createdAt)}</small></span><CopyId value={item.id} label="User ID" />
          </div>)}</article>
      </section>
    </>}</State>
  </div>;
}

export function DeveloperCommunitiesPage() {
  const [params, setParams] = useSearchParams();
  const query = params.get("q") || "";
  const page = Math.max(0, Number(params.get("page")) || 0);
  const [input, setInput] = useState(query);
  const url = `/api/developer/communities?q=${encodeURIComponent(query)}&page=${page}&size=${PAGE_SIZE}`;
  const { data, loading, error } = useApi(url);
  function submit(event) {
    event.preventDefault();
    const next = new URLSearchParams();
    if (input.trim()) next.set("q", input.trim());
    setParams(next);
  }
  return <div className="dashboard-content developer-content">
    <PageHeading title="커뮤니티" description="이름, Community ID, 생성자로 검색할 수 있습니다." />
    <form className="panel developer-table-search" onSubmit={submit}><Icon name="search" />
      <input value={input} onChange={(event) => setInput(event.target.value)} placeholder="커뮤니티명, ID, 생성자" />
      <button type="submit">검색</button></form>
    <State loading={loading} error={error} empty={data?.content.length === 0}>{data && <>
      <div className="panel developer-table-wrap"><table className="developer-table"><thead><tr>
        <th>Community ID</th><th>커뮤니티명</th><th>게임</th><th>생성자</th><th>멤버 수</th><th>Discord</th><th>생성일</th>
      </tr></thead><tbody>{data.content.map((item) => <tr key={item.id}>
        <td><CopyId value={item.id} label="Community ID" /></td>
        <td><AppLink href={`/developer/communities/${item.id}`}><strong>{item.name}</strong></AppLink></td>
        <td>{item.games.join(", ") || "-"}</td><td>{item.creatorNickname || "-"}{item.creatorUserId && <CopyId value={item.creatorUserId} label="User ID" />}</td>
        <td>{item.memberCount}</td><td><Status value={item.discordConnected ? "CONNECTED" : "NOT_CONNECTED"} /></td><td>{formatDate(item.createdAt)}</td>
      </tr>)}</tbody></table></div><Pager page={data} />
    </>}</State>
  </div>;
}

const communityTabs = [
  ["basic", "기본정보"], ["users", "사용자"], ["members", "클랜원"], ["bingos", "빙고"],
  ["kills", "킬내기"], ["discord", "Discord"], ["data", "데이터"],
];

export function DeveloperCommunityDetailPage() {
  const { communityId } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = communityTabs.some(([id]) => id === params.get("tab")) ? params.get("tab") : "basic";
  const { data, loading, error } = useApi(`/api/developer/communities/${encodeURIComponent(communityId)}`);
  return <div className="dashboard-content developer-content">
    <PageHeading title={data?.name || "커뮤니티 상세"} description={data ? `Community ID ${data.id}` : ""}
                 back="/developer/communities" />
    <State loading={loading} error={error} empty={false}>{data && <>
      <nav className="developer-tabs" aria-label="커뮤니티 상세 탭">{communityTabs.map(([id, label]) =>
        <button key={id} type="button" className={tab === id ? "is-active" : ""}
                onClick={() => setParams(id === "basic" ? {} : { tab: id })}>{label}</button>)}</nav>
      {tab === "basic" && <CommunityBasic data={data} />}
      {tab === "users" && <CommunityUsers communityId={communityId} />}
      {tab === "members" && <CommunityMembers communityId={communityId} />}
      {tab === "bingos" && <CommunityBingos communityId={communityId} />}
      {tab === "kills" && <CommunityKills communityId={communityId} />}
      {tab === "discord" && <CommunityDiscord data={data} />}
      {tab === "data" && <CommunityData data={data} />}
    </>}</State>
  </div>;
}

function DefinitionGrid({ items }) {
  return <dl className="panel developer-definition-grid">{items.map(([label, value]) =>
    <div key={label}><dt>{label}</dt><dd>{value ?? "-"}</dd></div>)}</dl>;
}

function CommunityBasic({ data }) {
  return <DefinitionGrid items={[
    ["Community ID", <CopyId value={data.id} label="Community ID" />], ["커뮤니티명", data.name],
    ["게임", data.games.map((game) => `${game.type} (#${game.id})`).join(", ") || "-"],
    ["생성자", <>{data.creatorNickname || "-"} {data.creatorUserId && <CopyId value={data.creatorUserId} label="User ID" />}</>],
    ["GuildUp 사용자", data.userCount], ["클랜원", data.memberCount], ["생성일", formatDate(data.createdAt)],
  ]} />;
}

function CommunityDiscord({ data }) {
  return <DefinitionGrid items={[
    ["연결 상태", <Status value={data.discordConnected ? "CONNECTED" : "NOT_CONNECTED"} />],
    ["Discord Guild ID", <CopyId value={data.discordGuildId} label="Discord Guild ID" />],
    ["Discord 서버명", data.discordGuildName], ["마지막 멤버 동기화", formatDate(data.discordLastMemberSyncedAt)],
  ]} />;
}

function CommunityData({ data }) {
  return <DefinitionGrid items={[
    ["Community ID", <CopyId value={data.id} label="Community ID" />],
    ["생성자 User ID", <CopyId value={data.creatorUserId} label="User ID" />],
    ["Discord Guild ID", <CopyId value={data.discordGuildId} label="Discord Guild ID" />],
    ...data.games.map((game) => [`Community Game ID (${game.type})`, <CopyId value={game.id} label="Community Game ID" />]),
  ]} />;
}

function TabPage({ url, columns, row }) {
  const [params] = useSearchParams();
  const page = Math.max(0, Number(params.get("page")) || 0);
  const pagedUrl = `${url}${url.includes("?") ? "&" : "?"}page=${page}`;
  const { data, loading, error } = useApi(pagedUrl);
  return <State loading={loading} error={error} empty={data?.content.length === 0}>{data && <>
    <div className="panel developer-table-wrap"><table className="developer-table"><thead><tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr></thead>
      <tbody>{data.content.map(row)}</tbody></table></div><Pager page={data} /></>}</State>;
}

function CommunityUsers({ communityId }) {
  return <TabPage url={`/api/developer/communities/${communityId}/users?size=${PAGE_SIZE}`} columns={["User ID", "닉네임", "커뮤니티 권한", "Discord", "가입일"]}
    row={(item) => <tr key={item.userId}><td><CopyId value={item.userId} label="User ID" /></td><td>{item.nickname}</td>
      <td><Status value={item.communityRole} /></td><td>{item.discordConnected ? <><CopyId value={item.discordUserId} label="Discord User ID" /><small>{item.discordUsername}</small></> : "연결 없음"}</td>
      <td>{formatDate(item.joinedAt)}</td></tr>} />;
}

function CommunityMembers({ communityId }) {
  return <TabPage url={`/api/developer/communities/${communityId}/members?size=${PAGE_SIZE}`} columns={["Member ID", "인게임 닉네임", "PUBG Account ID", "연결된 GuildUp User", "상태", "Discord"]}
    row={(item) => <tr key={item.memberId}><td><CopyId value={item.memberId} label="Community Member ID" /></td><td><strong>{item.nickname}</strong>{(item.pubgAccounts ?? []).map((account) => <small key={account.platform}>{account.platform === "KAKAO" ? "Kakao" : "Steam"} · {account.nickname || "-"}</small>)}</td>
      <td>{(item.pubgAccounts ?? []).map((account) => <div key={account.platform}><small>{account.platform === "KAKAO" ? "Kakao" : "Steam"}</small><CopyId value={account.accountId} label="PUBG Account ID" /></div>)}</td><td>{item.linkedUserId ? <>{item.linkedUserNickname}<CopyId value={item.linkedUserId} label="User ID" /></> : <Status value="UNLINKED" />}</td>
      <td><Status value={item.status} /></td><td><CopyId value={item.discordUserId} label="Discord User ID" /></td></tr>} />;
}

function CommunityBingos({ communityId }) {
  return <TabPage url={`/api/developer/communities/${communityId}/bingos?size=${PAGE_SIZE}`} columns={["Bingo ID", "제목", "상태", "기간", "크기", "참가자", "최근 집계"]}
    row={(item) => <tr key={item.id}><td><CopyId value={item.id} label="Bingo ID" /></td><td><AppLink href={`/developer/communities/${communityId}/bingos/${item.id}`}><strong>{item.title}</strong></AppLink></td>
      <td><Status value={item.status} /></td><td>{formatDate(item.startsAt)}<small>~ {formatDate(item.endsAt)}</small></td><td>{item.boardSize}×{item.boardSize}</td><td>{item.participantCount}</td><td>{formatDate(item.lastAggregatedAt)}</td></tr>} />;
}

function CommunityKills({ communityId }) {
  return <TabPage url={`/api/developer/communities/${communityId}/kill-competitions?size=${PAGE_SIZE}`} columns={["ID", "제목", "상태", "모드", "시작", "종료", "참가자"]}
    row={(item) => <tr key={item.id}><td><CopyId value={item.id} label="Kill Competition ID" /></td><td><AppLink href={`/developer/communities/${communityId}/kill-competitions/${item.id}`}><strong>{item.title}</strong></AppLink></td>
      <td><Status value={item.status} /></td><td>{item.gameMode}</td><td>{formatDate(item.startedAt)}</td><td>{formatDate(item.endsAt)}</td><td>{item.participantCount}</td></tr>} />;
}

export function DeveloperBingoDetailPage() {
  const { communityId, bingoId } = useParams();
  const [tab, setTab] = useState("settings");
  const { data, loading, error } = useApi(`/api/developer/communities/${communityId}/bingos/${bingoId}`);
  return <div className="dashboard-content developer-content"><PageHeading title={data?.title || "빙고 상세"}
    description="빙고 설정, 미션, 참가자 진행도와 처리 Match 원장을 조회합니다."
    back={`/developer/communities/${communityId}?tab=bingos`} />
    <State loading={loading} error={error} empty={false}>{data && <>
      <nav className="developer-tabs">{[["settings", "설정 · 미션"], ["participants", "참가자"], ["matches", "처리 Match"]].map(([id, label]) =>
        <button key={id} type="button" className={tab === id ? "is-active" : ""} onClick={() => setTab(id)}>{label}</button>)}</nav>
      {tab === "settings" && <><DefinitionGrid items={[
        ["Bingo ID", <CopyId value={data.id} label="Bingo ID" />], ["상태", <Status value={data.status} />], ["게임", data.game],
        ["기간", `${formatDate(data.startsAt)} ~ ${formatDate(data.endsAt)}`], ["보드", `${data.boardSize}×${data.boardSize}`],
        ["목표 라인", data.targetLines], ["블랙아웃", data.blackoutEnabled ? "사용" : "미사용"], ["중도 참가", data.allowLateJoin ? "허용" : "불가"],
        ["봇 전투 제외", data.excludeBotCombatStats ? "사용" : "미사용"], ["클랜 플레이 필수", data.clanPlayRequired ? "사용" : "미사용"],
        ["참가자", data.participantCount], ["처리 행", data.processedMatchCount], ["마지막 집계", formatDate(data.lastAggregatedAt)],
      ]} /><div className="panel developer-table-wrap"><table className="developer-table"><thead><tr><th>Cell ID</th><th>위치</th><th>미션</th><th>집계</th><th>조건</th><th>목표</th><th>옵션</th></tr></thead>
        <tbody>{data.missions.map((mission) => <tr key={mission.id}><td><CopyId value={mission.id} label="Bingo Cell ID" /></td><td>{mission.position + 1}</td><td>{mission.customTitle || mission.missionType}<small>{mission.missionType}</small></td><td>{mission.aggregationType}</td><td>{mission.operator}</td><td>{mission.targetValue}{mission.occurrenceTarget ? ` / ${mission.occurrenceTarget}회` : ""}</td><td><code>{mission.optionsJson || "{}"}</code></td></tr>)}</tbody></table></div></>}
      {tab === "participants" && <BingoParticipants communityId={communityId} bingoId={bingoId} />}
      {tab === "matches" && <BingoMatches communityId={communityId} bingoId={bingoId} />}
    </>}</State></div>;
}

function BingoParticipants({ communityId, bingoId }) {
  return <TabPage url={`/api/developer/communities/${communityId}/bingos/${bingoId}/participants?size=${PAGE_SIZE}`} columns={["Participant ID", "User", "Member ID", "PUBG", "진행도", "라인", "마지막 집계"]}
    row={(item) => <tr key={item.participantId}><td><CopyId value={item.participantId} label="Bingo Participant ID" /></td><td>{item.userNickname}<CopyId value={item.userId} label="User ID" /></td><td><CopyId value={item.communityMemberId} label="Community Member ID" /></td>
      <td>{item.pubgNickname}<CopyId value={item.pubgAccountId} label="PUBG Account ID" /></td><td>{item.completedMissionCount} / {item.missionCount}</td><td>{item.lineCount}</td><td>{formatDate(item.lastAggregatedAt)}</td></tr>} />;
}

function BingoMatches({ communityId, bingoId }) {
  return <TabPage url={`/api/developer/communities/${communityId}/bingos/${bingoId}/processed-matches?size=${PAGE_SIZE}`} columns={["원장 ID", "참가자", "Match ID", "경기 시작", "처리 시각"]}
    row={(item) => <tr key={item.id}><td><CopyId value={item.id} label="Processed Match ID" /></td><td>{item.participantNickname}<CopyId value={item.participantId} label="Participant ID" /></td>
      <td><CopyId value={item.matchId} label="Match ID" /></td><td>{formatDate(item.matchStartedAt)}</td><td>{formatDate(item.processedAt)}</td></tr>} />;
}

export function DeveloperKillCompetitionDetailPage() {
  const { communityId, competitionId } = useParams();
  const [tab, setTab] = useState("basic");
  const { data, loading, error } = useApi(`/api/developer/communities/${communityId}/kill-competitions/${competitionId}`);
  return <div className="dashboard-content developer-content"><PageHeading title={data?.title || "킬내기 상세"}
    description="참가자, 팀, 점수와 집계 상태를 조회합니다." back={`/developer/communities/${communityId}?tab=kills`} />
    <State loading={loading} error={error} empty={false}>{data && <>
      <nav className="developer-tabs">{[["basic", "기본 · 팀"], ["participants", "참가자 · 점수"], ["matches", "Match 결과"]].map(([id, label]) =>
        <button key={id} type="button" className={tab === id ? "is-active" : ""} onClick={() => setTab(id)}>{label}</button>)}</nav>
      {tab === "basic" && <><DefinitionGrid items={[
        ["Kill Competition ID", <CopyId value={data.id} label="Kill Competition ID" />], ["상태", <Status value={data.status} />],
        ["게임 / 모드", `${data.game || "-"} / ${data.gameMode}`], ["생성자 Member ID", <>{data.createdByNickname}<CopyId value={data.createdByMemberId} label="Community Member ID" /></>],
        ["모집", data.recruitmentOpen ? "OPEN" : `CLOSED (${formatDate(data.recruitmentClosedAt)})`], ["기간", `${formatDate(data.startedAt)} ~ ${formatDate(data.endsAt)}`],
        ["참가자", data.participantCount], ["Match 결과 행", data.matchResultCount], ["1킬 점수", data.killPoint],
        ["등수 점수", data.placementPointEnabled ? data.placementPoints.join(" / ") : "미사용"], ["최근 중간 집계", formatDate(data.lastInterimCalculatedAt)],
        ["집계 실행 시작", formatDate(data.interimCalculationStartedAt)], ["최종 집계 시작", formatDate(data.finalizationStartedAt)],
        ["최종 집계 Claim", <CopyId value={data.finalizationClaimToken} label="Finalization Claim Token" />], ["결과 공개 예정", formatDate(data.resultPublishAt)],
        ["최근 오류", data.resultLastError], ["완료", formatDate(data.completedAt)],
      ]} /><div className="developer-team-grid">{data.teams.length ? data.teams.map((team) => <article className="panel" key={team.id}><strong>{team.name}</strong><CopyId value={team.id} label="Team ID" /><small>표시 순서 {team.displayOrder}</small></article>) : <p className="panel page-state">등록된 팀이 없습니다.</p>}</div></>}
      {tab === "participants" && <KillParticipants communityId={communityId} competitionId={competitionId} />}
      {tab === "matches" && <KillMatches communityId={communityId} competitionId={competitionId} />}
    </>}</State></div>;
}

function KillParticipants({ communityId, competitionId }) {
  return <TabPage url={`/api/developer/communities/${communityId}/kill-competitions/${competitionId}/participants?size=${PAGE_SIZE}`} columns={["Participant ID", "클랜원", "팀", "PUBG", "승인", "중간 점수", "최종 점수"]}
    row={(item) => <tr key={item.participantId}><td><CopyId value={item.participantId} label="Kill Participant ID" /></td><td>{item.memberNickname}<CopyId value={item.communityMemberId} label="Community Member ID" /></td><td>{item.teamName || "-"}{item.teamId && <CopyId value={item.teamId} label="Team ID" />}</td>
      <td>{item.pubgNickname}<CopyId value={item.pubgAccountId} label="PUBG Account ID" /></td><td><Status value={item.participationStatus} /></td><td>{item.interimPoints}점<small>{item.interimKills}킬 · {item.interimMatchCount}경기</small></td><td>{item.finalPoints ?? "-"}<small>{item.finalKills ?? "-"}킬 · {item.finalMatchCount ?? "-"}경기</small></td></tr>} />;
}

function KillMatches({ communityId, competitionId }) {
  return <TabPage url={`/api/developer/communities/${communityId}/kill-competitions/${competitionId}/matches?size=${PAGE_SIZE}`} columns={["결과 ID", "참가자", "Match ID", "경기 시각", "킬", "등수", "킬 점수", "등수 점수", "합계"]}
    row={(item) => <tr key={item.id}><td><CopyId value={item.id} label="Match Result ID" /></td><td>{item.participantNickname}<CopyId value={item.participantId} label="Participant ID" /></td><td><CopyId value={item.matchId} label="Match ID" /></td>
      <td>{formatDate(item.matchStartedAt)}</td><td>{item.kills}</td><td>{item.placement ?? "-"}</td><td>{item.killPoints}</td><td>{item.placementPoints}</td><td><strong>{item.totalPoints}</strong></td></tr>} />;
}
