import { useEffect, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import AppLink from "../components/AppLink.jsx";
import { CopyId, PageHeading, Status, useApi } from "./DeveloperPages.jsx";
import { AccountStatus, LOGIN_METHODS, LoginMethodBadge, userDate } from "./userView.jsx";

const PAGE_SIZE = 20;

function UserState({ loading, error, reload, empty, children }) {
  if (loading) return <p className="panel page-state" role="status">회원 정보를 불러오는 중입니다.</p>;
  if (error) return <div className="panel developer-user-error" role="alert"><p>{error}</p>
    <button type="button" onClick={reload}>다시 시도</button></div>;
  if (empty) return <p className="panel page-state">조회된 회원이 없습니다. 검색어와 필터를 확인해 주세요.</p>;
  return children;
}

function UserPager({ data, params, setParams }) {
  if (!data || data.totalPages === 0) return null;
  function change(page) {
    const next = new URLSearchParams(params); next.set("page", String(page)); setParams(next);
  }
  return <nav className="developer-pager" aria-label="회원 목록 페이지 이동">
    <button type="button" disabled={data.page === 0} onClick={() => change(data.page - 1)}>이전</button>
    <span aria-live="polite">{data.page + 1} / {data.totalPages} · 전체 {data.totalElements.toLocaleString()}명</span>
    <button type="button" disabled={data.page + 1 >= data.totalPages} onClick={() => change(data.page + 1)}>다음</button>
  </nav>;
}

export function DeveloperUsersPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const q = params.get("q") || "";
  const method = LOGIN_METHODS.some(([id]) => id === params.get("loginMethod")) ? params.get("loginMethod") : "ALL";
  const sort = params.get("sort") === "lastLoginAt" ? "lastLoginAt" : "createdAt";
  const direction = params.get("direction") === "asc" ? "asc" : "desc";
  const pageValue = Number(params.get("page"));
  const page = Number.isSafeInteger(pageValue) && pageValue >= 0 && pageValue <= 2147483647 ? pageValue : 0;
  const [input, setInput] = useState(q);
  useEffect(() => setInput(q), [q]);
  const query = new URLSearchParams({ q, loginMethod: method, sort, direction, page: String(page), size: String(PAGE_SIZE) });
  const list = useApi(`/api/developer/users?${query}`);
  const statistics = useApi("/api/developer/users/statistics");
  // A bookmarked page can become empty after its filters/data change. Keep controls available to recover.
  useEffect(() => {
    if (!list.loading && list.data && page > 0 && page >= list.data.totalPages) {
      const next = new URLSearchParams(params); next.set("page", String(Math.max(0, list.data.totalPages - 1))); setParams(next, { replace: true });
    }
  }, [list.data, list.loading, page, params, setParams]);

  function update(key, value) {
    const next = new URLSearchParams(params); next.set(key, value); next.delete("page"); setParams(next);
  }
  function submit(event) { event.preventDefault(); update("q", input.trim()); }
  const cards = statistics.data && [["전체 회원 수", statistics.data.totalUsers], ["오늘 신규 가입", statistics.data.newUsersToday],
    ["최근 7일 신규 가입", statistics.data.newUsersLast7Days], ["최근 30일 활동 회원", statistics.data.activeUsersLast30Days],
    ["현재 정상 회원 수", statistics.data.normalUsers]];
  return <div className="dashboard-content developer-content">
    <PageHeading title="회원 관리" description="전체 GuildUp 회원과 연결된 계정, 커뮤니티 관계를 조회합니다." />
    <UserState {...statistics}>{cards && <section className="developer-stat-grid developer-user-stats" aria-label="회원 통계">
      {cards.map(([label, value]) => <article className="panel developer-stat" key={label}><span>{label}</span><strong>{value.toLocaleString()}</strong></article>)}
    </section>}</UserState>
    <p className="developer-user-note">신규 가입은 한국 시간 기준입니다. 현재 가입한 커뮤니티 수는 활성 멤버십을 집계합니다.</p>
    <form className="panel developer-user-filters" onSubmit={submit}>
      <label className="developer-user-search">회원 검색<input aria-label="회원 ID, 닉네임, 이메일 검색" maxLength={254}
        value={input} onChange={(event) => setInput(event.target.value)} placeholder="회원 ID, 닉네임, 이메일" /></label>
      <label>로그인 방식<select value={method} onChange={(event) => update("loginMethod", event.target.value)}>
        {LOGIN_METHODS.map(([value, label]) => <option value={value} key={value}>{label}</option>)}
      </select></label>
      <label>정렬 기준<select value={sort} onChange={(event) => update("sort", event.target.value)}>
        <option value="createdAt">가입일</option><option value="lastLoginAt">마지막 접속일 (로그인)</option>
      </select></label>
      <label>정렬 방향<select value={direction} onChange={(event) => update("direction", event.target.value)}>
        <option value="desc">최신순</option><option value="asc">오래된순</option>
      </select></label>
      <button type="submit">검색</button>
      <button className="secondary-button" type="button" onClick={() => { setInput(""); setParams({}); }}>초기화</button>
    </form>
    <UserState {...list} empty={list.data?.content.length === 0}>{list.data && <>
      <div className="panel developer-table-wrap" role="region" aria-label="회원 목록" tabIndex={0}>
        <table className="developer-table developer-user-table"><thead><tr><th>회원 ID</th><th>닉네임</th><th>로그인 방식</th>
          <th>가입한 커뮤니티</th><th>마지막 로그인</th><th>마지막 활동</th><th>가입일</th><th>계정 상태</th></tr></thead>
          <tbody>{list.data.content.map((item) => <tr key={item.id} className="developer-user-row" onClick={(event) => {
            if (!event.target.closest("a, button")) navigate(`/developer/users/${item.id}`);
          }}>
            <td data-label="회원 ID"><AppLink href={`/developer/users/${item.id}`}>{item.id}</AppLink></td>
            <td data-label="닉네임"><AppLink href={`/developer/users/${item.id}`}><strong>{item.nickname}</strong></AppLink></td>
            <td data-label="로그인 방식"><LoginMethodBadge value={item.loginMethod} /></td>
            <td data-label="가입한 커뮤니티">{item.communityCount}개</td><td data-label="마지막 로그인">{userDate(item.lastLoginAt)}</td>
            <td data-label="마지막 활동">{userDate(item.lastActiveAt)}</td><td data-label="가입일">{userDate(item.createdAt)}</td>
            <td data-label="계정 상태"><AccountStatus value={item.status} /></td>
          </tr>)}</tbody></table>
      </div>
    </>}</UserState>
    {!list.loading && !list.error && <UserPager data={list.data} params={params} setParams={setParams} />}
  </div>;
}

export function DeveloperUserDetailPage() {
  const { userId } = useParams();
  const detail = useApi(`/api/developer/users/${encodeURIComponent(userId)}`);
  const data = detail.data, user = data?.user;
  return <div className="dashboard-content developer-content">
    <PageHeading title="회원 상세" description={user ? `${user.nickname} · 회원 ID ${user.id}` : ""} back="/developer/users" />
    <UserState {...detail}>{user && <>
      <section className="developer-user-section"><h2>기본 정보</h2>
        <dl className="panel developer-definition-grid">
          {[["내부 회원 ID", <CopyId value={user.id} label="회원 ID" />], ["닉네임", user.nickname], ["이메일", user.email || "연결된 이메일 없음"],
            ["가입일", userDate(user.createdAt)], ["마지막 로그인", userDate(user.lastLoginAt)], ["마지막 활동", userDate(user.lastActiveAt)],
            ["계정 상태", <AccountStatus value={user.status} />], ["로그인 방식", <LoginMethodBadge value={user.loginMethod} />],
            ["현재 가입한 커뮤니티", `${user.communityCount}개`]].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
        </dl>
      </section>
      <section className="developer-user-section"><h2>연결된 로그인 계정</h2>
        {data.loginAccounts.length === 0 ? <p className="panel page-state">현재 연결된 로그인 계정이 없습니다.</p>
          : <div className="panel developer-table-wrap" role="region" aria-label="연결된 로그인 계정" tabIndex={0}>
            <table className="developer-table developer-account-table"><thead><tr><th>로그인 제공자</th><th>연결된 계정 아이디</th><th>외부 계정 고유 ID</th><th>연결일</th></tr></thead>
              <tbody>{data.loginAccounts.map((account) => <tr key={account.provider}>
                <td><LoginMethodBadge value={account.provider} /></td><td>{account.accountId || "기록 없음"}</td>
                <td>{account.externalUserId ? <CopyId value={account.externalUserId} label="외부 계정 ID" /> : "해당 없음"}</td>
                <td>{userDate(account.linkedAt)}</td>
              </tr>)}</tbody></table>
          </div>}
      </section>
      <section className="developer-user-section"><h2>가입한 커뮤니티</h2>
        {data.communities.length === 0 ? <p className="panel page-state">가입한 커뮤니티가 없습니다.</p>
          : <div className="panel developer-table-wrap" role="region" aria-label="회원 커뮤니티 관계" tabIndex={0}>
            <table className="developer-table"><thead><tr><th>커뮤니티 ID</th><th>커뮤니티 이름</th><th>커뮤니티 닉네임</th>
              <th>내부 역할</th><th>커뮤니티 가입일</th><th>멤버십 상태</th><th>클랜원 상태</th></tr></thead>
              <tbody>{data.communities.map((membership) => <tr key={membership.communityId}>
                <td><CopyId value={membership.communityId} label="커뮤니티 ID" /></td>
                <td><AppLink href={`/developer/communities/${membership.communityId}`}>{membership.communityName}</AppLink></td>
                <td>{membership.nickname || "연결된 닉네임 없음"}</td><td><Status value={membership.role} /></td>
                <td>{userDate(membership.joinedAt)}</td><td><span className={`developer-status status-${membership.status === "ACTIVE" ? "active" : "left"}`}>
                  {membership.status === "ACTIVE" ? "활성" : "종료"}</span>{membership.endedAt && <small>종료일: {userDate(membership.endedAt)}</small>}</td>
                <td>{membership.memberStatus ? <Status value={membership.memberStatus} /> : "연결 없음"}</td>
              </tr>)}</tbody></table>
          </div>}
      </section>
    </>}</UserState>
  </div>;
}
