package com.xammer.cloud.xampulse.domain;

import com.xammer.cloud.domain.converter.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_channel. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_channel")
@Getter
@Setter
@NoArgsConstructor
public class XpChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "kind")
    private String kind;

    @Column(name = "name")
    private String name;

    @Column(name = "config_enc", columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    private String configEnc;

}
