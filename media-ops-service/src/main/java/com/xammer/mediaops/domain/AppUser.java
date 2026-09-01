package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only view of the shared {@code app_user} table (owned by xamops-service).
 * media-ops only needs to turn the numeric subject of a billops impersonation JWT
 * into the principal name and owning client id that every controller here scopes
 * its queries by. This entity is never written by media-ops.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "app_user")
public class AppUser {

    @Id
    private Long id;

    @Column(name = "username")
    private String username;

    // Foreign key to client — mapped as a plain column so media-ops needn't pull in
    // the full Client entity graph, matching how CloudAccount maps the same FK.
    @Column(name = "client_id")
    private Long clientId;
}
