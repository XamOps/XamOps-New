package com.xammer.cloud.permissions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A second load of {@link XamOpsExecutionAttributes} in the same JVM — what a Spring Boot DevTools
 * restart does. The SDK's attribute-name registry lives in the parent classloader and survives the
 * restart; with fixed names the second load threw, and every AWS client built afterwards failed
 * with "Could not initialize class XamOpsExecutionAttributes".
 */
class XamOpsExecutionAttributesReloadTest {

    @Test
    void loadingTheClassAgainInAFreshClassloader_doesNotThrow() throws Exception {
        // First load, as the app does on startup.
        assertThat(XamOpsExecutionAttributes.ACCOUNT_ID).isNotNull();

        // Second load, as a DevTools restart does.
        Class<?> reloaded = Class.forName(XamOpsExecutionAttributes.class.getName(), true, new ChildFirst());

        assertThat(reloaded).isNotSameAs(XamOpsExecutionAttributes.class);
        assertThat(reloaded.getField("ACCOUNT_ID").get(null)).isNotNull();
        assertThat(reloaded.getField("REGION").get(null)).isNotNull();
    }

    /** Defines XamOpsExecutionAttributes afresh and delegates everything else, the SDK included, to the parent. */
    private static final class ChildFirst extends ClassLoader {

        ChildFirst() {
            super(XamOpsExecutionAttributes.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(XamOpsExecutionAttributes.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] bytes = in.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                return loaded;
            }
        }
    }
}
