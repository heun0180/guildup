import { Link } from "react-router-dom";

export default function AppLink({ href, children, ...props }) {
  if (typeof href === "string" && href.startsWith("/") && !href.startsWith("//")) {
    return <Link to={href} {...props}>{children}</Link>;
  }
  return <a href={href} {...props}>{children}</a>;
}
