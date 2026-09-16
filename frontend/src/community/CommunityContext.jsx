import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useLocation } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";

const CommunityContext = createContext(null);
const communityCache = new Map();
const communityRequests = new Map();

function loadCommunity(communityId) {
  if (communityCache.has(communityId)) return Promise.resolve(communityCache.get(communityId));
  if (communityRequests.has(communityId)) return communityRequests.get(communityId);

  const request = api(`/api/communities/${encodeURIComponent(communityId)}`)
    .then((community) => {
      communityCache.set(communityId, community);
      return community;
    })
    .finally(() => communityRequests.delete(communityId));
  communityRequests.set(communityId, request);
  return request;
}

export function CommunityProvider({ children }) {
  const location = useLocation();
  const communityId = new URLSearchParams(location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const cached = validId ? communityCache.get(communityId) ?? null : null;
  const [community, setCommunityState] = useState(cached);
  const [loading, setLoading] = useState(validId && !cached);
  const [error, setError] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");

  useEffect(() => {
    if (!validId) {
      setCommunityState(null);
      setLoading(false);
      setError("올바른 커뮤니티를 선택해 주세요.");
      return;
    }

    const nextCached = communityCache.get(communityId);
    if (nextCached) {
      setCommunityState(nextCached);
      setLoading(false);
      setError("");
      return;
    }

    let cancelled = false;
    setCommunityState(null);
    setLoading(true);
    setError("");
    loadCommunity(communityId)
      .then((result) => {
        if (!cancelled) setCommunityState(result);
      })
      .catch((failure) => {
        if (!cancelled && !redirectToLogin(failure)) {
          setError(failure.status === 403
            ? "이 커뮤니티에 접근할 권한이 없습니다."
            : failure.message || "커뮤니티 정보를 불러오지 못했습니다.");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [communityId, validId]);

  const setCommunity = useCallback((nextCommunity) => {
    if (!nextCommunity || !validId) return;
    communityCache.set(communityId, nextCommunity);
    setCommunityState((current) => current === nextCommunity ? current : nextCommunity);
  }, [communityId, validId]);

  const refreshCommunity = useCallback(async () => {
    if (!validId) return null;
    const result = await api(`/api/communities/${encodeURIComponent(communityId)}`);
    communityCache.set(communityId, result);
    setCommunityState(result);
    return result;
  }, [communityId, validId]);

  const value = useMemo(() => ({
    communityId, validId, community, loading, error, setCommunity, refreshCommunity,
  }), [communityId, validId, community, loading, error, setCommunity, refreshCommunity]);

  return <CommunityContext.Provider value={value}>{children}</CommunityContext.Provider>;
}

export function useCommunity() {
  return useContext(CommunityContext);
}
