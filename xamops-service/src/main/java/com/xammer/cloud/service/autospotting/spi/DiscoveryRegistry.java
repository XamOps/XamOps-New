package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.Provider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@link GroupDiscovery} for a {@link Provider} (design-core.md §2.5: one discovery
 * SPI per provider, next to {@link SpotProvider}). Mirrors {@link ProviderRegistry}: every
 * {@code GroupDiscovery} bean on the context is indexed by its {@link GroupDiscovery#provider()},
 * and a duplicate provider fails fast.
 *
 * <p>Dark by default: no {@code GroupDiscovery} bean exists yet (AWS ships in Phase 12, GCP in
 * Phase 26), so the registry is empty in production and the discovery job finds nothing to run in
 * any environment. A fake discovery is test scope only.
 */
@Component
public class DiscoveryRegistry {

    private final Map<Provider, GroupDiscovery> byProvider;

    public DiscoveryRegistry(List<GroupDiscovery> discoveries) {
        Map<Provider, GroupDiscovery> map = new EnumMap<>(Provider.class);
        for (GroupDiscovery discovery : discoveries) {
            GroupDiscovery existing = map.putIfAbsent(discovery.provider(), discovery);
            if (existing != null) {
                throw new IllegalStateException(
                        "two GroupDiscovery beans for " + discovery.provider() + ": "
                                + existing.getClass().getName() + " and " + discovery.getClass().getName());
            }
        }
        this.byProvider = Map.copyOf(map);
    }

    /** The discovery for {@code provider}, or empty when none is registered (dark until Phases 12/26). */
    public Optional<GroupDiscovery> find(Provider provider) {
        return Optional.ofNullable(byProvider.get(provider));
    }
}
