package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The databases of a Databricks or Snowflake datastore, for Scan Settings (design 5.4). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreDatabases(List<String> databases) {
}
