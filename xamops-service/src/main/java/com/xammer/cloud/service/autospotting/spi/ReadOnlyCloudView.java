package com.xammer.cloud.service.autospotting.spi;

/**
 * Marker interface for a provider's read-only view of the cloud (design-core.md §7.2, §9.1). Each
 * provider's view exposes only Describe/List/Get operations: eligibility, plan creation and snapshot
 * capture take a {@code ReadOnlyCloudView} and therefore cannot mutate the cloud by construction
 * (AC-PLN-01, enforced structurally by {@code AutoSpottingArchitectureTest}).
 *
 * <p>No method is declared here: an implementation adds the provider's own describe/list/get methods,
 * and the ArchUnit rule refuses any method whose name reads like a mutation on a type reachable from
 * a {@code ReadOnlyCloudView} implementation, so a mutating call cannot compile in planning.
 */
public interface ReadOnlyCloudView {
}
