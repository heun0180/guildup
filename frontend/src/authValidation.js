export function credentialError(values) {
  if (!values.email?.trim()) return "이메일을 입력해 주세요.";
  if (!values.password || values.password.length < 8 || !/[a-zA-Z]/.test(values.password)
      || !/[0-9]/.test(values.password) || new TextEncoder().encode(values.password).length > 72
      || values.password.includes("\0")) {
    return "비밀번호는 영문과 숫자를 포함한 8자 이상, UTF-8 기준 72바이트 이하여야 합니다.";
  }
  if (values.password !== values.passwordConfirmation) return "비밀번호가 일치하지 않습니다.";
  return "";
}
