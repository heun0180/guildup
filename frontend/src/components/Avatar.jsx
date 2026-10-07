import { useState } from "react";

export default function Avatar({ src, name, className = "member-avatar" }) {
  const [failedSource, setFailedSource] = useState(null);
  if (src && failedSource !== src) return <img className={className} src={src} alt="" onError={() => setFailedSource(src)} />;
  return <span className={className} aria-hidden="true">{name?.charAt(0).toUpperCase()}</span>;
}
