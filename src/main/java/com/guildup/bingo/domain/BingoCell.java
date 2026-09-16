package com.guildup.bingo.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;

@Entity
@Table(name = "bingo_cells", uniqueConstraints = @UniqueConstraint(
        name = "uk_bingo_cell_position", columnNames = {"bingo_event_id", "position"}))
public class BingoCell {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "bingo_event_id", nullable = false)
    private BingoEvent event;
    @Column(nullable = false) private int position;
    @Enumerated(EnumType.STRING) @Column(name = "mission_type", nullable = false, length = 40)
    private BingoMissionType missionType;
    @Enumerated(EnumType.STRING) @Column(name = "aggregation_type", nullable = false, length = 30)
    private BingoAggregationType aggregationType;
    @Enumerated(EnumType.STRING) @Column(name = "comparison_operator", nullable = false, length = 30)
    private BingoOperator operator;
    @Column(name = "target_value", nullable = false, precision = 16, scale = 3) private BigDecimal targetValue;
    @Column(name = "occurrence_target") private Integer occurrenceTarget;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "options_json") private Map<String, Object> options;
    @Column(name = "custom_title", length = 120) private String customTitle;

    protected BingoCell() {}
    public BingoCell(BingoEvent event, int position, BingoMissionType missionType,
                     BingoAggregationType aggregationType, BingoOperator operator,
                     BigDecimal targetValue, Integer occurrenceTarget, Map<String, Object> options, String customTitle) {
        this.event = event; this.position = position; this.missionType = missionType;
        this.aggregationType = aggregationType; this.operator = operator; this.targetValue = targetValue;
        this.occurrenceTarget = occurrenceTarget; this.options = options == null ? Map.of() : Map.copyOf(options); this.customTitle = customTitle;
    }
    public Long getId() { return id; }
    public int getPosition() { return position; }
    public BingoMissionType getMissionType() { return missionType; }
    public BingoAggregationType getAggregationType() { return aggregationType; }
    public BingoOperator getOperator() { return operator; }
    public BigDecimal getTargetValue() { return targetValue; }
    public Integer getOccurrenceTarget() { return occurrenceTarget; }
    public Map<String, Object> getOptions() { return options == null ? Map.of() : Map.copyOf(options); }
    public String getCustomTitle() { return customTitle; }
}
