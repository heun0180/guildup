import { createContext, useContext, useEffect, useMemo } from "react";
import { api } from "../api/http.js";
import { createRequestScope } from "../api/requestScope.js";

const GameRequestContext = createContext(api);

/** Mounted with community/game/page identity: forms and results never carry to another platform. */
export default function GameScopeBoundary({ children }) {
  const scope = useMemo(() => createRequestScope(api), []);
  useEffect(() => () => scope.cancel(), [scope]);
  return <GameRequestContext.Provider value={scope.request}>{children}</GameRequestContext.Provider>;
}

export const useScopedApi = () => useContext(GameRequestContext);
