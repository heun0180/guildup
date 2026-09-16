import { useLocation } from "react-router-dom";
import { requiredRolesForLocation } from "../communityAccess.js";
import { useCommunity } from "../community/CommunityContext.jsx";
import AppLink from "./AppLink.jsx";

export default function CommunityRouteGuard({ children }) {
  const location = useLocation();
  const { communityId, validId, community, loading, error } = useCommunity();
  const requiredRoles = requiredRolesForLocation(location.pathname, location.search);
  const state = !validId ? "invalid"
    : loading ? "loading"
    : error ? "error"
    : requiredRoles && !requiredRoles.has(community?.role) ? "forbidden"
    : "allowed";

  if (state === "allowed") return children;
  const messages = {
    loading: "커뮤니티 권한을 확인하는 중입니다.",
    invalid: "올바른 커뮤니티를 선택해 주세요.",
    forbidden: "OWNER 또는 ADMIN만 이 화면에 접근할 수 있습니다.",
    error: "커뮤니티 권한을 확인하지 못했습니다.",
  };
  return <section className="public-main login-main">
    <section className="login-card">
      <h1>{state === "forbidden" ? "접근 권한이 없습니다." : "페이지를 열 수 없습니다."}</h1>
      <p className="login-description">{messages[state]}</p>
      {state !== "loading" && <AppLink href={validId
        ? `/community-dashboard.html?communityId=${encodeURIComponent(communityId)}`
        : "/communities.html"}>커뮤니티로 돌아가기</AppLink>}
    </section>
  </section>;
}
