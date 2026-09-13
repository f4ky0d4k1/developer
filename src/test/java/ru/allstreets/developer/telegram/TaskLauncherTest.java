package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Нормализация целевого репозитория — дедупликация per-chat памяти проектов.
 */
class TaskLauncherTest {

    @Test
    void normalizeRepo_trimsAndLowercases() {
        assertEquals("allstreets/backend", TaskLauncher.normalizeRepo("  AllStreets/Backend "));
        assertEquals("allstreets/backend", TaskLauncher.normalizeRepo("allstreets/backend"));
    }

    @Test
    void normalizeRepo_blankIsNull() {
        assertNull(TaskLauncher.normalizeRepo(null));
        assertNull(TaskLauncher.normalizeRepo(""));
        assertNull(TaskLauncher.normalizeRepo("   "));
    }
}
