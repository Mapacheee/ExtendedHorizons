package me.mapacheee.extendedhorizons;

import com.thewinterframework.configurate.Container;

import java.lang.reflect.Constructor;

public final class TestContainers {

    private TestContainers() {}

    @SuppressWarnings("unchecked")
    public static <T> Container<T> containing(T value) {
        try {
            Constructor<?> constructor = Container.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] arguments = new Object[constructor.getParameterCount()];
            arguments[0] = value;
            arguments[1] = value.getClass();
            return (Container<T>) constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create test configuration container", exception);
        }
    }
}
