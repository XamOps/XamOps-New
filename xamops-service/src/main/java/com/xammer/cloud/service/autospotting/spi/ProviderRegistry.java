package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.Provider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@link SpotProvider} for a {@link Provider} (design-core.md §9.1: one bean per
 * {@code Provider}). Every {@code SpotProvider} bean on the context is indexed by its
 * {@link SpotProvider#provider()}; a duplicate provider is a wiring error and fails fast.
 *
 * <p>Dark by default: no {@code SpotProvider} bean exists yet (AWS ships in Phase 12, GCP in Phase
 * 26), so the registry is empty in production. The {@code FakeSpotProvider} is test scope only. This
 * registry registers no caller and mutates no cloud.
 */
@Component
public class ProviderRegistry {

    private final Map<Provider, SpotProvider> byProvider;

    public ProviderRegistry(List<SpotProvider> providers) {
        Map<Provider, SpotProvider> map = new EnumMap<>(Provider.class);
        for (SpotProvider provider : providers) {
            SpotProvider existing = map.putIfAbsent(provider.provider(), provider);
            if (existing != null) {
                throw new IllegalStateException(
                        "two SpotProvider beans for " + provider.provider() + ": "
                                + existing.getClass().getName() + " and " + provider.getClass().getName());
            }
        }
        this.byProvider = Map.copyOf(map);
    }

    /** The provider for {@code provider}, or empty when none is registered (dark until Phases 12/26). */
    public Optional<SpotProvider> find(Provider provider) {
        return Optional.ofNullable(byProvider.get(provider));
    }

    /** The provider for {@code provider}, or throw when none is registered. */
    public SpotProvider require(Provider provider) {
        SpotProvider found = byProvider.get(provider);
        if (found == null) {
            throw new IllegalStateException("no SpotProvider registered for " + provider);
        }
        return found;
    }
}
