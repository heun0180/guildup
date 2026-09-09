import { useEffect, useMemo, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import Avatar from "../components/Avatar.jsx";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";

const MESSAGE_MAX_LENGTH = 2000;

const failureLabel = {
  DM_NOT_AVAILABLE: "DM 수신이 불가능한 멤버",
  USER_NOT_FOUND: "서버에서 찾을 수 없는 멤버",
  DISCORD_API_ERROR: "Discord 오류로 전송하지 못한 멤버",
};

export default function DiscordDmPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const encodedCommunityId = encodeURIComponent(communityId || "");
  const [members, setMembers] = useState([]);
  const [roles, setRoles] = useState([]);
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [selectedRoleId, setSelectedRoleId] = useState("");
  const [message, setMessage] = useState("");
  const [search, setSearch] = useState("");
  const [maxRecipients, setMaxRecipients] = useState(50);
  const [loading, setLoading] = useState(true);
  const [loadingRole, setLoadingRole] = useState(false);
  const [sending, setSending] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [notice, setNotice] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");
  const [result, setResult] = useState(null);

  useEffect(() => {
    if (!validId) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    Promise.all([
      api(`/api/communities/${encodedCommunityId}/discord/dm/recipients`),
      api(`/api/communities/${encodedCommunityId}/discord/roles`),
    ]).then(([recipientData, roleData]) => {
      if (cancelled) return;
      setMembers(recipientData.members);
      setMaxRecipients(recipientData.maxRecipients);
      setRoles(roleData);
    }).catch((error) => {
      if (redirectToLogin(error) || cancelled) return;
      if (error.status === 403) setNotice("DM 보내기는 커뮤니티 운영진과 관리자만 사용할 수 있습니다.");
      else if (error.status === 404) setNotice("연결된 Discord 서버를 찾을 수 없습니다. 연동 상태를 확인해 주세요.");
      else setNotice("Discord 멤버 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [encodedCommunityId, validId]);

  const memberById = useMemo(() => new Map(
    members.map((member) => [member.id, member]),
  ), [members]);
  const normalizedSearch = search.trim().toLocaleLowerCase();
  const filteredMembers = members.filter((member) => !normalizedSearch ||
    [member.displayName, member.username].some((value) =>
      value?.toLocaleLowerCase().includes(normalizedSearch)));
  const selectedCount = selectedIds.size;
  const validMessage = message.trim().length > 0 && message.length <= MESSAGE_MAX_LENGTH;
  const withinRecipientLimit = selectedCount <= maxRecipients;
  const canSend = selectedCount > 0 && withinRecipientLimit && validMessage && !sending;

  function updateSelection(updater) {
    setSelectedIds(updater);
    setResult(null);
    setNotice("");
  }

  function toggleMember(userId) {
    updateSelection((current) => {
      const next = new Set(current);
      if (next.has(userId)) next.delete(userId);
      else next.add(userId);
      return next;
    });
  }

  async function selectRole(event) {
    const roleId = event.target.value;
    setSelectedRoleId(roleId);
    if (!roleId) return;
    setLoadingRole(true);
    setNotice("");
    try {
      const roleMembers = await api(
        `/api/communities/${encodedCommunityId}/discord/roles/${encodeURIComponent(roleId)}/members`,
      );
      updateSelection((current) => new Set([
        ...current,
        ...roleMembers.map((member) => member.id).filter((id) => memberById.has(id)),
      ]));
    } catch (error) {
      if (!redirectToLogin(error)) setNotice("선택한 역할의 멤버를 불러오지 못했습니다.");
    } finally {
      setLoadingRole(false);
      setSelectedRoleId("");
    }
  }

  function openConfirmation() {
    if (!canSend) {
      if (selectedCount === 0) setNotice("받는 사람을 한 명 이상 선택해 주세요.");
      else if (!withinRecipientLimit) setNotice(`한 번에 최대 ${maxRecipients}명에게 보낼 수 있습니다.`);
      else setNotice("메시지를 입력해 주세요.");
      return;
    }
    setNotice("");
    setConfirming(true);
  }

  async function sendDm() {
    if (sending) return;
    setSending(true);
    setNotice("");
    setResult(null);
    try {
      const response = await api(`/api/communities/${encodedCommunityId}/discord/dm`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ discordUserIds: [...selectedIds], message }),
      });
      setResult(response);
      setConfirming(false);
    } catch (error) {
      if (!redirectToLogin(error)) {
        setConfirming(false);
        setNotice(error.status === 409
          ? "같은 내용의 DM이 방금 처리되었습니다. 잠시 후 다시 시도해 주세요."
          : error.status === 403
            ? "DM을 보낼 관리 권한이 없습니다."
            : error.status === 400
              ? `수신자와 메시지를 확인해 주세요. 한 번에 최대 ${maxRecipients}명까지 보낼 수 있습니다.`
              : "Discord DM 발송 요청을 처리하지 못했습니다.");
      }
    } finally {
      setSending(false);
    }
  }

  const failedResults = result?.results?.filter((item) => !item.success) ?? [];
  const failedGroups = Object.entries(failureLabel).map(([reason, label]) => ({
    reason,
    label,
    members: failedResults.filter((item) => item.reason === reason),
  })).filter((group) => group.members.length > 0);

  return (
    <DashboardLayout active="integrations" communityId={communityId} onError={setNotice}>
      <div className="dashboard-content discord-dm-content">
        <div className="page-heading">
          <p className="eyebrow">Discord</p>
          <h1>DM 보내기</h1>
          <p>Discord 서버 멤버를 선택해 운영 공지와 알림을 보냅니다.</p>
        </div>

        {notice && <p className="message" role="alert">{notice}</p>}
        {result && <section className="success-message dm-result" role="status">
          <strong>총 {result.total}명 중 {result.success}명 전송 완료</strong>
          {result.failed > 0 && <p>{result.failed}명에게는 전송하지 못했습니다.</p>}
          {failedGroups.map((group) => <div key={group.reason}>
            <span>{group.label}</span>
            <strong>{group.members.map((item) => item.displayName || item.discordUserId).join(", ")}</strong>
          </div>)}
        </section>}

        <section className="panel dm-recipient-panel" aria-labelledby="dm-recipient-title">
          <div className="dm-panel-heading">
            <div><h2 id="dm-recipient-title">받는 사람</h2><p>선택된 멤버 {selectedCount}명 · 최대 {maxRecipients}명</p></div>
            <div className="team-selection-actions">
              <button type="button" className="secondary-button" disabled={loading || sending}
                      onClick={() => updateSelection(new Set(members.map((member) => member.id)))}>
                전체 선택
              </button>
              <button type="button" className="secondary-button" disabled={sending || selectedCount === 0}
                      onClick={() => updateSelection(new Set())}>전체 해제</button>
            </div>
          </div>

          <div className="dm-recipient-tools">
            <label><span>역할 기준 선택</span>
              <select value={selectedRoleId} onChange={selectRole} disabled={loadingRole || sending || roles.length === 0}>
                <option value="">{loadingRole ? "역할 멤버 선택 중..." : "역할 선택"}</option>
                {roles.map((role) => <option value={role.id} key={role.id}>{role.name}</option>)}
              </select>
            </label>
            <label className="search-field"><span className="sr-only">멤버 검색</span><Icon name="search" size={18} />
              <input value={search} onChange={(event) => setSearch(event.target.value)}
                     placeholder="멤버 검색" autoComplete="off" />
            </label>
          </div>

          {loading && <p className="empty-state">Discord 멤버 목록을 불러오는 중입니다.</p>}
          {!loading && members.length === 0 && !notice && <p className="empty-state">선택할 수 있는 Discord 멤버가 없습니다.</p>}
          {!loading && members.length > 0 && <div className="dm-member-list">
            {filteredMembers.map((member) => {
              const checked = selectedIds.has(member.id);
              return <label className={`dm-member-item${checked ? " is-selected" : ""}`} key={member.id}>
                <input type="checkbox" checked={checked} disabled={sending}
                       onChange={() => toggleMember(member.id)} />
                <span className="custom-checkbox"><Icon name="check" size={15} /></span>
                <Avatar src={member.avatarUrl} name={member.displayName} />
                <span><strong>{member.displayName}</strong><small>@{member.username}</small></span>
              </label>;
            })}
            {filteredMembers.length === 0 && <p className="empty-state">검색 결과가 없습니다.</p>}
          </div>}
          {!withinRecipientLimit && <p className="dm-limit-message" role="alert">
            선택 인원이 발송 상한을 초과했습니다. {selectedCount - maxRecipients}명을 선택 해제해 주세요.
          </p>}
        </section>

        <section className="panel dm-compose-panel" aria-labelledby="dm-compose-title">
          <div className="dm-panel-heading"><div><h2 id="dm-compose-title">메시지 작성</h2><p>Discord 메시지는 최대 {MESSAGE_MAX_LENGTH.toLocaleString()}자입니다.</p></div></div>
          <label className="dm-message-field">
            <span className="sr-only">보낼 메시지</span>
            <textarea value={message} maxLength={MESSAGE_MAX_LENGTH} disabled={sending}
                      onChange={(event) => { setMessage(event.target.value); setResult(null); setNotice(""); }}
                      placeholder={"안녕하세요.\n커뮤니티 공지 내용을 입력해 주세요."} />
            <small>{message.length.toLocaleString()} / {MESSAGE_MAX_LENGTH.toLocaleString()}</small>
          </label>
          <div className="dm-send-actions">
            <span>{sending ? "DM을 보내는 중입니다..." : `선택된 멤버 ${selectedCount}명`}</span>
            <button type="button" disabled={!canSend} onClick={openConfirmation}>
              <Icon name="discord" size={18} />{sending ? "발송 중..." : "DM 보내기"}
            </button>
          </div>
        </section>
      </div>

      {confirming && <div className="modal-backdrop" role="presentation">
        <section className="confirm-modal" role="dialog" aria-modal="true" aria-labelledby="dm-confirm-title">
          <span className="confirm-modal-icon"><Icon name="discord" size={25} /></span>
          <h2 id="dm-confirm-title">{selectedCount}명에게 Discord DM을 보내시겠습니까?</h2>
          <p>발송을 시작하면 각 멤버에게 순서대로 메시지가 전송됩니다.</p>
          {sending && <p className="dm-sending-status" role="status">DM을 보내는 중입니다...</p>}
          <div className="confirm-modal-actions">
            <button type="button" className="secondary-button" disabled={sending} onClick={() => setConfirming(false)}>취소</button>
            <button type="button" disabled={sending} onClick={sendDm}>{sending ? "보내는 중..." : "보내기"}</button>
          </div>
        </section>
      </div>}
    </DashboardLayout>
  );
}
