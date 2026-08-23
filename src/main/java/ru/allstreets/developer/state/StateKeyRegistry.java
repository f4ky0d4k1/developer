package ru.allstreets.developer.state;

import io.github.asekka.springai.agents.core.StateKey;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Реестр всех {@link StateKey} задачи по их {@code name()}, построенный рефлексией
 * по константам {@link TaskState}.
 * <p>
 * Нужен для checkpoint-сериализации: {@code StateBag} хранит значения по ключу
 * {@code StateKey<T>}, но при восстановлении из JSON известно только имя ключа.
 * Раньше тип восстанавливался по рантайм-классу значения ({@code value.getClass()}),
 * что ломалось на дженериках (например {@code List<Feedback>} после Jackson-десериализации
 * превращался в {@code ImmutableCollections$ListN}, не равный объявленному {@code List.class}
 * с точки зрения {@code StateKey.equals()}) — такой ключ переставал находиться через
 * {@code ctx.get(TaskState.FEEDBACK)}.
 * <p>
 * Вместо этого здесь используется объявленный в {@link TaskState} generic-тип поля
 * (через {@link Field#getGenericType()}), что даёт Jackson точный {@code JavaType}
 * для десериализации коллекций и вложенных объектов.
 */
public final class StateKeyRegistry {

    private static final Map<String, StateKey<?>> KEYS_BY_NAME = new HashMap<>();
    private static final Map<String, Type> GENERIC_TYPES_BY_NAME = new HashMap<>();

    static {
        register();
    }

    private StateKeyRegistry() {
    }

    private static void register() {
        for (Field field : TaskState.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !StateKey.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                StateKey<?> key = (StateKey<?>) field.get(null);
                if (key == null) {
                    continue;
                }
                if (KEYS_BY_NAME.put(key.name(), key) != null) {
                    throw new IllegalStateException("Duplicate StateKey name: " + key.name());
                }
                GENERIC_TYPES_BY_NAME.put(key.name(), resolveGenericValueType(field));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Не удалось прочитать StateKey из поля " + field.getName(), e);
            }
        }
    }

    /**
     * Извлекает T из объявления {@code StateKey<T>} поля, включая generic-параметры
     * (например {@code List<Feedback>} для {@code StateKey<List<Feedback>>}).
     */
    private static Type resolveGenericValueType(Field field) {
        Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
            return pt.getActualTypeArguments()[0];
        }
        StateKey<?> key = null;
        try {
            key = (StateKey<?>) field.get(null);
        } catch (IllegalAccessException ignored) {
            // недостижимо — field уже setAccessible и прочитан выше
        }
        return key != null ? key.type() : Object.class;
    }

    /**
     * Найти {@link StateKey} по его имени. Возвращает {@code null}, если ключ
     * не объявлен в {@link TaskState} (устарел/переименован) — вызывающий код
     * должен пропустить такую запись при восстановлении checkpoint.
     */
    public static StateKey<?> byName(String name) {
        return KEYS_BY_NAME.get(name);
    }

    /**
     * Точный generic-тип значения ключа для десериализации Jackson-ом.
     */
    public static Type genericType(String name) {
        return GENERIC_TYPES_BY_NAME.get(name);
    }
}
