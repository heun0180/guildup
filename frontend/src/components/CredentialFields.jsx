export const PASSWORD_HINT = "영문과 숫자를 포함해 8자 이상 입력해 주세요. 최대 72바이트로, 영문·숫자는 72자까지 사용할 수 있습니다.";

export default function CredentialFields({ values, onChange, signup = false, nickname = false, disabled = false }) {
  const fields = [
    { name: "email", label: "이메일", type: "email", autoComplete: "email", maxLength: 254 },
    { name: "password", label: "비밀번호", type: "password", autoComplete: signup ? "new-password" : "current-password", maxLength: 72 },
    ...(signup ? [{ name: "passwordConfirmation", label: "비밀번호 확인", type: "password", autoComplete: "new-password", maxLength: 72 }] : []),
    ...(nickname ? [{ name: "nickname", label: "닉네임", type: "text", autoComplete: "nickname", maxLength: 50 }] : []),
  ];
  return fields.map((field) => <div className="auth-field" key={field.name}>
    <label htmlFor={`auth-${field.name}`}>{field.label}</label>
    <input id={`auth-${field.name}`} name={field.name} type={field.type} autoComplete={field.autoComplete}
      maxLength={field.maxLength} required disabled={disabled} value={values[field.name] || ""}
      autoCapitalize={field.name === "email" ? "none" : undefined} spellCheck={false}
      aria-describedby={signup && field.name === "password" ? "auth-password-hint" : undefined}
      onChange={(event) => onChange(field.name, event.target.value)} />
    {signup && field.name === "password" && <p id="auth-password-hint" className="auth-hint">{PASSWORD_HINT}</p>}
  </div>);
}
