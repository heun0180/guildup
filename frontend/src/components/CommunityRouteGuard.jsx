import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import { requiredRolesForLocation } from "../communityAccess.js";

export default function CommunityRouteGuard({ children }) {
  const requiredRoles = requiredRolesForLocation(window.location.pathname, window.location.search);
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [state, setState] = useState(requiredRoles ? "loading" : "allowed");

  useEffect(() => {
    if (!requiredRoles) return;
    if (!validId) {
      setState("invalid");
      return;
    }
    let cancelled = false;
    api(`/api/communities/${encodeURIComponent(communityId)}`)
      .then((community) => {
        if (!cancelled) setState(requiredRoles.has(community.role) ? "allowed" : "forbidden");
      })
      .catch((error) => {
        if (!redirectToLogin(error) && !cancelled) setState(error.status === 403 ? "forbidden" : "error");
      });
    return () => { cancelled = true; };
  }, [communityId, requiredRoles, validId]);

  if (state === "allowed") return children;
  const messages = {
    loading: "커뮤니티 권한을 확인하는 중입니다.",
    invalid: "올바른 커뮤니티를 선택해 주세요.",
    forbidden: "OWNER 또는 ADMIN만 이 화면에 접근할 수 있습니다.",
    error: "커뮤니티 권한을 확인하지 못했습니다.",
  };
  return <main className="public-main login-main">
    <section className="login-card">
      <h1>{state === "forbidden" ? "접근 권한이 없습니다." : "페이지를 열 수 없습니다."}</h1>
      <p className="login-description">{messages[state]}</p>
      {state !== "loading" && <a href={validId
        ? `/community-dashboard.html?communityId=${encodeURIComponent(communityId)}`
        : "/communities.html"}>커뮤니티로 돌아가기</a>}
    </section>
  </main>;
}
