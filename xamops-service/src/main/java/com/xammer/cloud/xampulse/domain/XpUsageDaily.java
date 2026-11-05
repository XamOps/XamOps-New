package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_usage_daily. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_usage_daily")
@Getter
@Setter
@NoArgsConstructor
public class XpUsageDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "day")
    private LocalDate day;

    @Column(name = "signal")
    private String signal;

    @Column(name = "source")
    private String source;

    @Column(name = "bytes_in")
    private long bytesIn;

    @Column(name = "bytes_stored")
    private long bytesStored;

    @Column(name = "row_count")
    private long rowCount;

}
