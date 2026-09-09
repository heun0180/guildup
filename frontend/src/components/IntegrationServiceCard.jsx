import Icon from "./Icon.jsx";

export default function IntegrationServiceCard({
  icon,
  name,
  description,
  connected,
  connectedLabel,
  connectHref,
  features = [],
}) {
  return (
    <article className="panel integration-service-card">
      <div className="integration-service-heading">
        <span className={`integration-service-icon ${icon}`}><Icon name={icon} size={26} /></span>
        <div>
          <div className="integration-service-title-row">
            <h2>{name}</h2>
            <span className={`status-badge ${connected ? "connected" : "disconnected"}`}>
              <span aria-hidden="true" />{connected ? "연결됨" : "연결 안 됨"}
            </span>
          </div>
          <p>{description}</p>
        </div>
      </div>

      {connected ? (
        <>
          <div className="connected-service-summary">
            <span>연결된 서버</span>
            <strong>{connectedLabel}</strong>
          </div>
          <div className="integration-feature-grid">
            {features.map((feature) => (
              feature.href ? (
                <a className="integration-feature-card" href={feature.href} key={feature.id}>
                  <span className="integration-feature-icon"><Icon name={feature.icon} size={20} /></span>
                  <span className="integration-feature-copy">
                    <strong>{feature.title}</strong>
                    <span>{feature.description}</span>
                  </span>
                  <Icon name="arrow" size={17} />
                </a>
              ) : (
                <div className="integration-feature-card is-placeholder" key={feature.id}>
                  <span className="integration-feature-icon"><Icon name={feature.icon} size={20} /></span>
                  <span className="integration-feature-copy">
                    <strong>{feature.title}</strong>
                    <span>{feature.description}</span>
                  </span>
                  <small>준비 중</small>
                </div>
              )
            ))}
          </div>
        </>
      ) : (
        <div className="integration-disconnected-state">
          <div>
            <h3>{name}가 아직 연결되지 않았습니다.</h3>
            <p>외부 서비스의 기능을 사용하려면 먼저 연결을 완료해 주세요.</p>
          </div>
          <a className="button-link" href={connectHref}>
            {name} 연결하기 <Icon name="arrow" size={17} />
          </a>
        </div>
      )}
    </article>
  );
}
