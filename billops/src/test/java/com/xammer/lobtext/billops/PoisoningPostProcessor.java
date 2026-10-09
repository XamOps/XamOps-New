package com.xammer.lobtext.billops;

import com.xammer.billops.repository.BillOpsCachedDataRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps {@link BillOpsCachedDataRepository} in a JDK proxy (design §7.2). {@code findById} of a key
 * listed by {@link #poison(String...)} counts the call and runs {@link Poisoner#poison()}, whose
 * PostgreSQL error propagates to the caller; every other call goes to the real repository.
 *
 * <p>Registered through a static {@code @Bean} method ({@link DashboardFaultInjectionConfig}), and
 * the {@link Poisoner} is looked up lazily, so neither forces early creation of JPA beans.
 */
public class PoisoningPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<Poisoner> poisoner;
    private final Set<String> poisonedKeys = ConcurrentHashMap.newKeySet();
    private final List<String> poisonedCalls = new CopyOnWriteArrayList<>();

    public PoisoningPostProcessor(ObjectProvider<Poisoner> poisoner) {
        this.poisoner = poisoner;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof BillOpsCachedDataRepository repository)) {
            return bean;
        }
        return Proxy.newProxyInstance(BillOpsCachedDataRepository.class.getClassLoader(),
                new Class<?>[] {BillOpsCachedDataRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName()) && args != null && args.length == 1
                            && poisonedKeys.contains(args[0])) {
                        poisonedCalls.add((String) args[0]);
                        poisoner.getObject().poison();       // raises; the error reaches the caller as is
                        throw new IllegalStateException("SELECT lo_get(0) did not raise for " + args[0]);
                    }
                    try {
                        return method.invoke(repository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** From now on, {@code findById} of these keys raises. */
    public void poison(String... keys) {
        poisonedKeys.addAll(List.of(keys));
    }

    /** Stops poisoning; the recorded calls are kept. */
    public void stop() {
        poisonedKeys.clear();
    }

    /** Stops poisoning and forgets the recorded calls. */
    public void reset() {
        poisonedKeys.clear();
        poisonedCalls.clear();
    }

    /** The keys of the poisoned {@code findById} calls so far, in call order. */
    public List<String> poisonedCalls() {
        return List.copyOf(poisonedCalls);
    }
}
