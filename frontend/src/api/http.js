export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export async function api(url, options = {}) {
  const response = await fetch(url, {
    credentials: "same-origin",
    ...options,
  });

  if (!response.ok) {
    let message = `요청을 처리하지 못했습니다. (HTTP ${response.status})`;
    const body = await response.json().catch(() => null);
    if (body && typeof body.message === "string") message = body.message;
    throw new ApiError(response.status, message);
  }

  return response.status === 204 ? null : response.json();
}

export function redirectToLogin(error) {
  if (error instanceof ApiError && error.status === 401) {
    window.location.replace("/login.html");
    return true;
  }
  return false;
}
