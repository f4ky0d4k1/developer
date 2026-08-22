package ru.allstreets.developer.checkpoint;

import org.hibernate.proxy.HibernateProxy;

import java.util.Objects;
import java.util.function.Function;

/**
 * Утилитный класс для работы с JPA-сущностями с учётом Hibernate-проксирования.
 * Предоставляет корректные equals/hashCode для сущностей по их ID.
 */
public final class EntityUtil {

    private EntityUtil() {
    }

    /**
     * Получает реальный (непроксированный) класс объекта.
     */
    public static Class<?> getEffectiveClass(Object object) {
        return object instanceof HibernateProxy
                ? ((HibernateProxy) object).getHibernateLazyInitializer().getPersistentClass()
                : object.getClass();
    }

    /**
     * Сравнение двух сущностей по произвольному ключу (ID).
     * Корректно работает с Hibernate-прокси.
     */
    @SuppressWarnings("unchecked")
    public static <K, T1> boolean equals(T1 object1,
                                         Object object2,
                                         Function<T1, K> keyProvider) {
        if (object2 == null) return false;
        if (object1 == object2) return true;
        if (getEffectiveClass(object1) != getEffectiveClass(object2)) return false;
        K key1 = keyProvider.apply(object1);
        return key1 != null && Objects.equals(key1, keyProvider.apply((T1) object2));
    }

    /**
     * Хэш-код на основе реального класса сущности (без полей).
     */
    public static int hashCode(Object object) {
        return getEffectiveClass(object).hashCode();
    }
}
