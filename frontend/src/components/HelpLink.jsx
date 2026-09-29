import AppLink from "./AppLink.jsx";
import Icon from "./Icon.jsx";

export default function HelpLink({ href, label = "사용 방법 보기", className = "" }) {
  return (
    <AppLink className={`help-icon-link${className ? ` ${className}` : ""}`} href={href}
             aria-label={label} title={label}>
      <Icon name="help" size={20} />
    </AppLink>
  );
}
