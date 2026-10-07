import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import Avatar from "./Avatar.jsx";

export default function AccountProfilePanel({ discordConnected, onSaved }) {
  const [profile, setProfile] = useState(null);
  const [values, setValues] = useState({ nickname: "", birthDate: "" });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [success, setSuccess] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    api("/api/account/profile", { signal: controller.signal }).then((value) => {
      setProfile(value);
      setValues({ nickname: value.nickname, birthDate: value.birthDate || "" });
    }).catch((error) => {
      if (error.name !== "AbortError" && !redirectToLogin(error)) setMessage(error.message);
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, []);

  async function save(event) {
    event.preventDefault();
    if (saving) return;
    setMessage(""); setSuccess("");
    const nickname = values.nickname.trim();
    if (!nickname || nickname.length > 50 || /[\u0000-\u001f\u007f-\u009f]/.test(nickname)) {
      setMessage("닉네임은 제어 문자를 제외한 1~50자로 입력해 주세요."); return;
    }
    setSaving(true);
    try {
      const updated = await api("/api/account/profile", { method: "PATCH", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nickname, birthDate: values.birthDate || null }) });
      setProfile(updated);
      setValues({ nickname: updated.nickname, birthDate: updated.birthDate || "" });
      onSaved(updated);
      setSuccess("프로필 변경사항을 저장했습니다.");
    } catch (error) { if (!redirectToLogin(error)) setMessage(error.message); }
    finally { setSaving(false); }
  }

  const today = new Intl.DateTimeFormat("en", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" })
    .formatToParts(new Date());
  const part = (type) => today.find((value) => value.type === type).value;
  const maxDate = `${part("year")}-${part("month")}-${part("day")}`;
  const avatarUrl = discordConnected ? profile?.avatarUrl : null;
  const changed = profile && (values.nickname !== profile.nickname || values.birthDate !== (profile.birthDate || ""));

  return <section className="panel account-section" aria-labelledby="account-profile-title">
    <div className="account-section-heading"><h2 id="account-profile-title">프로필</h2>
      <p>GuildUp에서 사용할 기본 프로필을 설정합니다.</p></div>
    {loading && <p role="status">프로필을 불러오는 중입니다.</p>}
    {message && <p className="message" role="alert">{message}</p>}
    {success && <p className="auth-success" role="status">{success}</p>}
    {profile && <>
      <div className="account-profile-image"><Avatar src={avatarUrl} name={profile.nickname || "G"} className="avatar account-avatar" />
        <div><strong>프로필 이미지</strong><p className="auth-hint">{avatarUrl
          ? "연결된 Discord 프로필 이미지를 사용합니다." : "GuildUp 기본 아바타를 사용합니다."}</p></div></div>
      <form id="account-profile-form" className="account-profile-form" onSubmit={save} aria-busy={saving}>
        <div className="account-profile-fields">
          <div className="auth-field"><label htmlFor="account-nickname">GuildUp 닉네임</label>
            <input id="account-nickname" name="profileNickname" autoComplete="nickname" value={values.nickname}
              maxLength={50} required disabled={saving} aria-describedby="account-nickname-hint"
              onChange={(event) => setValues((current) => ({ ...current, nickname: event.target.value }))} />
            <p id="account-nickname-hint" className="auth-hint">GuildUp에서 표시되는 이름입니다. Discord·PUBG 닉네임과 별도로 관리합니다.</p></div>
          <div className="auth-field"><label htmlFor="account-birth-date">생년월일 <span className="account-optional">(선택)</span></label>
            <input id="account-birth-date" name="birthDate" type="date" value={values.birthDate} min="0001-01-01" max={maxDate}
              disabled={saving} aria-describedby="account-birth-date-hint"
              onChange={(event) => setValues((current) => ({ ...current, birthDate: event.target.value }))} />
            <p id="account-birth-date-hint" className="auth-hint">입력하지 않아도 GuildUp을 이용할 수 있습니다. 비워서 저장하면 삭제됩니다.</p></div>
        </div>
        <div className="account-save-bar"><button disabled={saving || !changed}>{saving ? "저장 중..." : "변경사항 저장"}</button></div>
      </form>
    </>}
  </section>;
}
