export const LOGIN_METHODS = [
  ["ALL", "전체 로그인 방식"], ["EMAIL", "이메일 로그인"], ["DISCORD", "Discord 로그인"],
  ["EMAIL_DISCORD", "이메일 + Discord 연결"], ["NONE", "연결된 로그인 없음"],
];

export function userDate(value) {
  return value ? new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul" })
    .format(new Date(value)) : "기록 없음";
}

export function LoginMethodBadge({ value }) {
  const label = LOGIN_METHODS.find(([key]) => key === value)?.[1] || "연결된 로그인 없음";
  return <span className={`developer-status login-method-${String(value || "none").toLowerCase()}`}>{label}</span>;
}

export function AccountStatus({ value }) {
  return <span className={`developer-status status-${String(value).toLowerCase()}`}>
    {value === "ACTIVE" ? "정상" : value === "WITHDRAWN" ? "탈퇴" : value || "기록 없음"}
  </span>;
}

export function LoginIdentifiers({ user }) {
  return <div className="developer-login-identifiers">
    {user.email && <span>이메일: {user.email}</span>}
    {user.discordUserId && <span>Discord: {user.discordUsername || "이름 기록 없음"}<small>ID: {user.discordUserId}</small></span>}
    {!user.email && !user.discordUserId && <span>연결된 로그인 없음</span>}
  </div>;
}
