import { useEffect, useMemo, useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import DashboardLayout from "../components/DashboardLayout.jsx";
import { useCommunity } from "../community/CommunityContext.jsx";
import { canManageCommunity } from "../communityAccess.js";
import { api, redirectToLogin } from "../api/http.js";
import { BOARD_CATEGORIES, CATEGORY_LABELS, ROLE_LABELS, boardError, boardUrl, formatBoardDate } from "../communityBoard.js";

const emptyForm = { category: "FREE", title: "", content: "", notice: false, pinned: false };

function Author({ name, role }) {
  return <span>{name} <small>· {ROLE_LABELS[role] ?? role}</small></span>;
}

function PostForm({ initial, manager, busy, onSubmit, onCancel }) {
  const [form, setForm] = useState(initial ?? emptyForm);
  function change(event) {
    const { name, value, type, checked } = event.target;
    setForm((current) => ({ ...current, [name]: type === "checkbox" ? checked : value }));
  }
  return <form className="panel board-form" onSubmit={(event) => { event.preventDefault(); onSubmit(form); }}>
    <label>카테고리<select name="category" value={form.category} onChange={change} required>
      {BOARD_CATEGORIES.slice(1).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label>
    <label>제목<input name="title" value={form.title} onChange={change} minLength="2" maxLength="200" required /></label>
    <label className="board-form-content">내용<textarea name="content" value={form.content} onChange={change}
      minLength="2" maxLength="20000" required /></label>
    {manager && <div className="board-management-options">
      <label><input type="checkbox" name="notice" checked={form.notice} onChange={change} /> 공지 등록</label>
      <label><input type="checkbox" name="pinned" checked={form.pinned} onChange={change} /> 상단 고정</label>
    </div>}
    <div className="board-form-actions">
      <button type="button" className="secondary-button" onClick={onCancel} disabled={busy}>취소</button>
      <button disabled={busy}>{busy ? "저장 중…" : "저장"}</button>
    </div>
  </form>;
}

function CommentItem({ comment, busy, onUpdate, onDelete }) {
  const [editing, setEditing] = useState(false);
  const [content, setContent] = useState(comment.content);
  return <li className="board-comment">
    <div className="board-comment-head"><strong><Author name={comment.authorName} role={comment.authorRole} /></strong>
      <span>{formatBoardDate(comment.createdAt, true)}{comment.edited ? " · 수정됨" : ""}</span></div>
    {editing ? <form onSubmit={async (event) => { event.preventDefault(); if (await onUpdate(comment.id, content)) setEditing(false); }}>
      <textarea value={content} onChange={(event) => setContent(event.target.value)} maxLength="2000" required />
      <div className="board-inline-actions"><button type="button" className="secondary-button" onClick={() => setEditing(false)}>취소</button>
        <button disabled={busy}>수정</button></div>
    </form> : <p>{comment.content}</p>}
    {!editing && (comment.canEdit || comment.canDelete) && <div className="board-inline-actions">
      {comment.canEdit && <button className="text-button" onClick={() => setEditing(true)}>수정</button>}
      {comment.canDelete && <button className="text-button danger-text" disabled={busy} onClick={() => onDelete(comment.id)}>삭제</button>}
    </div>}
  </li>;
}

export default function CommunityBoardPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { community, communityId } = useCommunity();
  const query = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const category = BOARD_CATEGORIES.some(([value]) => value === query.get("category")) ? query.get("category") : "ALL";
  const page = /^\d+$/.test(query.get("page") ?? "") ? Number(query.get("page")) : 0;
  const postId = /^\d+$/.test(query.get("postId") ?? "") ? query.get("postId") : null;
  const mode = query.get("mode");
  const manager = canManageCommunity(community?.role);
  const [pageData, setPageData] = useState(null);
  const [post, setPost] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const viewToken = useMemo(() => postId
    ? (globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`) : null, [postId]);
  const base = `/api/communities/${encodeURIComponent(communityId)}/posts`;
  const editing = mode === "edit" && postId;
  const creating = mode === "new" && !postId;
  const showEditor = Boolean(editing && post?.canEdit);

  useEffect(() => {
    if (!/^\d+$/.test(communityId ?? "") || creating) { setLoading(false); return; }
    let cancelled = false;
    setLoading(true); setError("");
    if (postId) setPost(null); else setPageData(null);
    const url = postId ? `${base}/${postId}` : `${base}?page=${page}&size=20${category === "ALL" ? "" : `&category=${category}`}`;
    api(url, postId ? { headers: { "X-View-Token": viewToken } } : undefined)
      .then((result) => { if (!cancelled) postId ? setPost(result) : setPageData(result); })
      .catch((failure) => { if (!cancelled && !redirectToLogin(failure)) setError(boardError(failure)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [communityId, base, category, page, postId, creating, retry, viewToken]);

  async function savePost(form) {
    setBusy(true); setError("");
    try {
      const payload = { category: form.category, title: form.title, content: form.content,
        ...(manager ? { notice: form.notice, pinned: form.pinned } : {}) };
      const result = await api(editing ? `${base}/${postId}` : base, {
        method: editing ? "PATCH" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
      });
      setPost(result);
      navigate(boardUrl(communityId, { postId: result.id }));
    } catch (failure) { if (!redirectToLogin(failure)) setError(boardError(failure)); }
    finally { setBusy(false); }
  }

  async function patchManagement(change) {
    setBusy(true); setError("");
    try {
      const result = await api(`${base}/${postId}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(change) });
      setPost(result);
    } catch (failure) { if (!redirectToLogin(failure)) setError(boardError(failure)); }
    finally { setBusy(false); }
  }

  async function removePost() {
    if (!window.confirm("게시글을 삭제하시겠습니까?")) return;
    setBusy(true);
    try { await api(`${base}/${postId}`, { method: "DELETE" }); navigate(boardUrl(communityId)); }
    catch (failure) { if (!redirectToLogin(failure)) setError(boardError(failure)); setBusy(false); }
  }

  async function addComment(event) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setBusy(true); setError("");
    try {
      const comment = await api(`${base}/${postId}/comments`, { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: form.get("content") }) });
      setPost((current) => ({ ...current, comments: [...current.comments, comment] }));
      formElement.reset();
    } catch (failure) { if (!redirectToLogin(failure)) setError(boardError(failure)); }
    finally { setBusy(false); }
  }

  async function updateComment(commentId, content) {
    setBusy(true); setError("");
    try {
      const updated = await api(`${base}/${postId}/comments/${commentId}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ content }) });
      setPost((current) => ({ ...current, comments: current.comments.map((item) => item.id === commentId ? updated : item) }));
      return true;
    } catch (failure) { if (!redirectToLogin(failure)) setError(boardError(failure)); return false; }
    finally { setBusy(false); }
  }

  async function deleteComment(commentId) {
    if (!window.confirm("댓글을 삭제하시겠습니까?")) return;
    setBusy(true); setError("");
    try { await api(`${base}/${postId}/comments/${commentId}`, { method: "DELETE" });
      setPost((current) => ({ ...current, comments: current.comments.filter((item) => item.id !== commentId) })); }
    catch (failure) { if (!redirectToLogin(failure)) setError(boardError(failure)); }
    finally { setBusy(false); }
  }

  const editInitial = post && { category: post.category, title: post.title, content: post.content, notice: post.notice, pinned: post.pinned };
  return <DashboardLayout active="board" communityId={communityId} community={community}>
    <div className="dashboard-content board-content">
      <div className="page-heading is-compact"><p className="eyebrow">{community?.name}</p><h1>게시판</h1>
        <p>커뮤니티 클랜원들과 이야기와 정보를 나눠보세요.</p></div>
      {error && <div className="message" role="alert">{error} <button className="secondary-button" onClick={() => setRetry((value) => value + 1)}>다시 시도</button></div>}
      {loading && <p className="panel page-state" role="status">게시판을 불러오는 중입니다.</p>}
      {!loading && creating && <PostForm manager={manager} busy={busy} onSubmit={savePost} onCancel={() => navigate(boardUrl(communityId))} />}
      {!loading && showEditor && <PostForm initial={editInitial} manager={manager} busy={busy} onSubmit={savePost}
        onCancel={() => navigate(boardUrl(communityId, { postId }))} />}
      {!loading && postId && post && !showEditor && <article className="panel board-detail">
        <div className="board-detail-head"><div className="board-badges">
          {post.pinned && <span className="board-badge pinned">고정</span>}{post.notice && <span className="board-badge notice">공지</span>}
          <span className="board-badge">{CATEGORY_LABELS[post.category]}</span></div><h2>{post.title}</h2>
          <p><Author name={post.authorName} role={post.authorRole} /> · {formatBoardDate(post.createdAt, true)}
            {post.edited ? " · 수정됨" : ""} · 조회 {post.viewCount}</p></div>
        <div className="board-body">{post.content}</div>
        <div className="board-detail-actions"><a className="secondary-button" href={boardUrl(communityId)}>목록</a><div>
          {post.canManage && <><button className="secondary-button" disabled={busy} onClick={() => patchManagement({ notice: !post.notice })}>{post.notice ? "공지 해제" : "공지 등록"}</button>
            <button className="secondary-button" disabled={busy} onClick={() => patchManagement({ pinned: !post.pinned })}>{post.pinned ? "고정 해제" : "상단 고정"}</button></>}
          {post.canEdit && <button className="secondary-button" onClick={() => navigate(boardUrl(communityId, { postId, mode: "edit" }))}>수정</button>}
          {post.canDelete && <button className="secondary-button danger-text" disabled={busy} onClick={removePost}>삭제</button>}
        </div></div>
        <section className="board-comments"><h3>댓글 <span>{post.comments.length}</span></h3>
          <form className="board-comment-form" onSubmit={addComment}><textarea name="content" maxLength="2000" placeholder="댓글을 입력해 주세요." required />
            <button disabled={busy}>{busy ? "등록 중…" : "댓글 등록"}</button></form>
          {post.comments.length === 0 ? <p className="board-empty-comments">첫 댓글을 남겨보세요.</p> : <ul>
            {post.comments.map((comment) => <CommentItem key={comment.id} comment={comment} busy={busy} onUpdate={updateComment} onDelete={deleteComment} />)}
          </ul>}
        </section>
      </article>}
      {!loading && !postId && !creating && pageData && <>
        <div className="board-toolbar"><div className="role-tabs" role="tablist" aria-label="게시글 카테고리">
          {BOARD_CATEGORIES.map(([value, label]) => <button className="role-tab" role="tab" aria-selected={category === value} key={value}
            onClick={() => navigate(boardUrl(communityId, { category: value }))}>{label}</button>)}</div>
          <button onClick={() => navigate(boardUrl(communityId, { mode: "new" }))}>글쓰기</button></div>
        {pageData.content.length === 0 ? <div className="panel empty-state"><p>등록된 게시글이 없습니다.</p><p>첫 글을 작성해보세요.</p></div>
          : <div className="panel board-list"><div className="board-list-head"><span>분류</span><span>제목</span><span>작성자</span><span>작성일</span><span>조회</span></div>
            {pageData.content.map((item) => <a className={`board-row${item.pinned || item.notice ? " is-highlighted" : ""}`} key={item.id}
              href={boardUrl(communityId, { postId: item.id })}><span className="board-category">{item.pinned ? "고정" : item.notice ? "공지" : CATEGORY_LABELS[item.category]}</span>
              <strong>{item.title}{item.commentCount > 0 && <small>[{item.commentCount}]</small>}</strong><Author name={item.authorName} role={item.authorRole} />
              <span>{formatBoardDate(item.createdAt)}</span><span>{item.viewCount}</span></a>)}</div>}
        {pageData.totalPages > 1 && <nav className="board-pagination" aria-label="게시글 페이지">
          <button className="secondary-button" disabled={pageData.first} onClick={() => navigate(boardUrl(communityId, { category, page: page - 1 }))}>이전</button>
          <span>{page + 1} / {pageData.totalPages}</span>
          <button className="secondary-button" disabled={pageData.last} onClick={() => navigate(boardUrl(communityId, { category, page: page + 1 }))}>다음</button></nav>}
      </>}
    </div>
  </DashboardLayout>;
}
